// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation.desktop;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.Closeable;

/** The microphone as 16 kHz mono PCM16, read in 100 ms chunks on the caller's thread. */
final class Microphone implements Closeable {
    static final int RATE = 16000;
    static final int CHUNK_BYTES = RATE * 2 / 10;

    /** The microphone failed after opening; kept apart from network failures. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AudioRecord record;

    private Microphone(AudioRecord record) {
        this.record = record;
    }

    /**
     * Opens and starts recording; the caller must hold RECORD_AUDIO. With {@code context} (Android
     * 12+), the recording is attributed through it, e.g. to the app a recognition service serves;
     * if that is refused, the app records as itself.
     */
    static Microphone open(Context context) throws Failure {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return start(() -> new AudioRecord.Builder()
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
        return start(() -> new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes()));
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
        } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException e) {
            throw new Failure("The microphone could not be opened", e);
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new Failure("The microphone could not be opened");
        }
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            record.release();
            throw new Failure("The microphone could not start", e);
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
            int r = record.read(chunk, n, chunk.length - n);
            if (r < 0) throw new Failure("The microphone stopped (" + r + ")");
            n += r;
        }
        return n;
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
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Never started or already stopped: release is what matters.
        }
        record.release();
    }
}
