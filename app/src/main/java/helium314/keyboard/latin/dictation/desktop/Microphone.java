// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation.desktop;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The microphone as 16 kHz mono PCM16, read in 100 ms chunks on the caller's thread. It also
 * listens for Android silencing this recording ({@link #androidSilenced}, Android 10+), which
 * Android does without an error: the recorder then delivers zeros.
 *
 * <p>One at a time in the process (the keyboard's dictation, the recognition service, other
 * apps' mic buttons): opening a microphone takes over from the one already open, whose next read
 * fails with {@link Failure.Cause#TAKEN_OVER}, and waits until it is released. Across apps
 * Android itself silences all but one recording, which the silenced session sees and ends on.
 */
final class Microphone implements Closeable {
    static final int RATE = 16000;
    static final int CHUNK_BYTES = RATE * 2 / 10;
    /** How long a new microphone waits for the one it takes over from to let go. */
    static final long TAKE_OVER_WAIT_MS = 600;

    /** The microphone failed after opening; kept apart from network failures. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        /** Why, as a class for the log: never the audio. */
        enum Cause {
            /** RECORD_AUDIO was refused when the recorder was made. */
            DENIED,
            /** The platform refused the format. */
            BAD_FORMAT,
            /** The recorder did not initialize (the microphone may be blocked or busy). */
            NOT_INITIALIZED,
            /** startRecording threw. */
            START_FAILED,
            /** Started, but not recording: usually another app (a call, the camera) has it. */
            IN_USE,
            /** A read returned an error code partway. */
            READ_ERROR,
            /** A newer recording in this app took the microphone over. */
            TAKEN_OVER
        }

        final Cause cause;

        Failure(Cause cause, String message) {
            super(message);
            this.cause = cause;
        }

        Failure(Cause cause, String message, Throwable source) {
            super(message, source);
            this.cause = cause;
        }
    }

    private static final Object OPEN = new Object();
    /** The microphone open in this process, if any; guarded by {@link #OPEN}. */
    private static Microphone current;

    private final AudioRecord record;
    private AudioManager audio;
    private Object watch;
    private volatile boolean silenced;
    private volatile boolean takenOver;
    private final CountDownLatch released = new CountDownLatch(1);

    private Microphone(AudioRecord record) {
        this.record = record;
    }

    /**
     * Opens and starts recording; the caller must hold RECORD_AUDIO. With {@code context} (Android
     * 12+), the recording is attributed through it, e.g. to the app a recognition service serves;
     * if that is refused, the app records as itself. {@code app} hears Android silencing it.
     */
    static Microphone open(Context app, Context context) throws Failure {
        takeOver();
        Microphone mic = null;
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                mic = start(() -> new AudioRecord.Builder()
                        .setContext(context)
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(bufferBytes())
                        .build());
            } catch (Failure ignored) {
                // Recorded as the app itself below.
            }
        }
        if (mic == null) {
            mic = start(() -> new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes()));
        }
        synchronized (OPEN) {
            current = mic;
        }
        mic.watch(app);
        return mic;
    }

    /**
     * Asks the microphone open in this process to let go, and waits a little for it: never two
     * recorders at once, which would leave one of them silenced.
     */
    private static void takeOver() {
        Microphone old;
        synchronized (OPEN) {
            old = current;
        }
        if (old == null) return;
        old.takenOver = true;
        try {
            old.released.await(TAKE_OVER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Listens for Android silencing this recording (Android 10+). */
    private void watch(Context app) {
        if (app == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int id = record.getAudioSessionId();
        AudioManager.AudioRecordingCallback cb = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                silenced = silencedIn(configs, id);
            }
        };
        // Callbacks on the main looper; the flag is read on the mic thread.
        am.registerAudioRecordingCallback(cb, null);
        audio = am;
        watch = cb;
        silenced = silencedIn(am.getActiveRecordingConfigurations(), id);
    }

    private static boolean silencedIn(List<AudioRecordingConfiguration> configs, int id) {
        if (configs == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        for (AudioRecordingConfiguration c : configs) {
            if (c.getClientAudioSessionId() == id) return c.isClientSilenced();
        }
        return false;
    }

    private static int bufferBytes() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        return Math.max(min, CHUNK_BYTES * 4);
    }

    private static Microphone start(java.util.function.Supplier<AudioRecord> make) throws Failure {
        AudioRecord record;
        try {
            record = make.get();
        } catch (SecurityException e) {
            throw new Failure(Failure.Cause.DENIED, "The microphone permission was refused", e);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new Failure(Failure.Cause.BAD_FORMAT, "The microphone could not be opened", e);
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new Failure(Failure.Cause.NOT_INITIALIZED, "The microphone could not be opened");
        }
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            record.release();
            throw new Failure(Failure.Cause.START_FAILED, "The microphone could not start", e);
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            throw new Failure(Failure.Cause.IN_USE, "The microphone is in use by another app");
        }
        return new Microphone(record);
    }

    /**
     * Fills {@code chunk} unless {@code stop} turns true first; returns the bytes read, which is
     * less than a chunk only when stopping.
     */
    int read(byte[] chunk, java.util.function.BooleanSupplier stop) throws Failure {
        int n = 0;
        while (n < chunk.length && !stop.getAsBoolean()) {
            if (takenOver) throw new Failure(Failure.Cause.TAKEN_OVER, "A newer recording took the microphone");
            int r = record.read(chunk, n, chunk.length - n);
            if (r < 0) throw new Failure(Failure.Cause.READ_ERROR, "The microphone stopped (" + r + ")");
            n += r;
        }
        return n;
    }

    /** Whether Android says it is silencing this recording now (privacy toggle, a call, another app). */
    boolean androidSilenced() {
        return silenced;
    }

    /** RMS of the first {@code len} bytes of little-endian PCM16, in dB below full scale. */
    static float dbfs(byte[] pcm, int len) {
        int samples = len / 2;
        if (samples == 0) return -90f;
        double sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (double) v * v;
        }
        double rms = Math.sqrt(sum / samples) / 32768.0;
        return rms < 1e-6 ? -90f : (float) Math.max(-90.0, 20 * Math.log10(rms));
    }

    @Override
    public void close() {
        if (audio != null && watch != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audio.unregisterAudioRecordingCallback((AudioManager.AudioRecordingCallback) watch);
        }
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Never started or already stopped: release is what matters.
        }
        record.release();
        synchronized (OPEN) {
            if (current == this) current = null;
        }
        released.countDown();
    }
}
