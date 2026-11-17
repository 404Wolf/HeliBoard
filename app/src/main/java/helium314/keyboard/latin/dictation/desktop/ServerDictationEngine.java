// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation.desktop;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Streams the microphone to the desktop's Whisper server (Herdr's dictation server, server.py):
 * PCM16 mono at 16 kHz in 100 ms websocket frames, partials back about once a second, and the
 * final after {@link #stop}. Recording starts at once and is buffered while the socket connects,
 * so the first words are not lost.
 *
 * <p>Every session's audio is also written to app storage as it is recorded. If the desktop
 * cannot be reached, stops answering, or drops mid-session, the session carries on in
 * {@link State#OFFLINE_RECORDING} and, on stop, keeps that file as a {@link PendingClip} for
 * {@link PendingDictations}; a finished live session deletes it.
 */
public final class ServerDictationEngine implements DictationEngine {
    static final int CHUNK_BYTES = Microphone.CHUNK_BYTES;
    /** Audio kept in memory while connecting: 10 s (it is on disk as well). */
    static final int MAX_PENDING_CHUNKS = 100;
    /** The TCP connect alone; the websocket upgrade after it has {@link #HANDSHAKE_TIMEOUT_MS}. */
    static final int CONNECT_TIMEOUT_MS = 2500;
    /**
     * Each read of the TLS and websocket upgrade: a relayed tailnet path (DERP on cellular, 200
     * ms and more a round trip) needs more than the connect's 2.5 s.
     */
    static final int HANDSHAKE_TIMEOUT_MS = 8000;
    /**
     * From the tap until the server says ready, else the session records offline: room for a
     * slow handshake, within the {@link #MAX_PENDING_CHUNKS} held for sending.
     */
    static final int READY_TIMEOUT_MS = HANDSHAKE_TIMEOUT_MS + 1000;
    /** The start message: the same for a live session and an upload. */
    static final String START = "{\"type\":\"start\",\"sample_rate\":" + Microphone.RATE + "}";
    /** Microphone open failures this process, by cause: the log carries counts, never audio. */
    private static final java.util.concurrent.atomic.AtomicIntegerArray OPEN_FAILURES =
            new java.util.concurrent.atomic.AtomicIntegerArray(Microphone.Failure.Cause.values().length);
    static final int FINAL_TIMEOUT_MS = 20000;

    /** The microphone's loudness, about every 100 ms on the mic thread. */
    public interface LevelListener {
        /** RMS of the last chunk in dB below full scale: 0 is the loudest, silence about -90. */
        void onLevel(float dbfs);
    }

    private final Context context;
    /** Opens the microphone: the app's own, or one attributed to the app being served. */
    private final Context micContext;
    private final URI uri;
    private final PendingDictations pending;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Main thread only. */
    private Session session;
    private volatile LevelListener levels;

    public ServerDictationEngine(Context context, URI uri, PendingDictations pending) {
        this(context, null, uri, pending);
    }

    /**
     * {@code micContext}, when not null, records on behalf of another app (a recognition service's
     * caller, see {@code ContextParams.Builder#setNextAttributionSource}).
     */
    public ServerDictationEngine(Context context, Context micContext, URI uri, PendingDictations pending) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.micContext = micContext;
        this.uri = uri;
        this.pending = pending;
    }

    /** Hears the microphone's level from the next chunk on; null to stop. */
    public void setLevelListener(LevelListener l) {
        levels = l;
    }

    /**
     * The server URL from configuration: a ws:// or wss:// URL with a host, or null. Parsed at
     * the boundary so the engine only ever holds a usable URI.
     */
    public static URI parseUrl(String text) {
        if (text == null || text.isEmpty() || text.length() > 512) return null;
        try {
            URI uri = new URI(text.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null) return null;
            return scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss") ? uri : null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    @Override
    public void start(String target, Listener listener) {
        if (session != null) return;
        if (context.checkPermission(Manifest.permission.RECORD_AUDIO, android.os.Process.myPid(),
                android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(new DictationException(
                    DictationException.Reason.PERMISSION, "Microphone permission is not granted"));
            listener.onState(State.IDLE);
            return;
        }
        session = new Session(listener, pending.store().record(target, System.currentTimeMillis()));
        session.begin();
    }

    @Override
    public void stop() {
        if (session != null) session.finish();
    }

    @Override
    public void cancel() {
        Session s = session;
        session = null;
        if (s != null) s.abort();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private final class Session {
        private final Listener listener;
        private final ClipStore.Recording recording;
        private final Runnable readyTimeout = () -> goOffline("not_ready", "not ready in time", null);
        private final Runnable finishTimeout = () -> goOffline("no_final", "no final in time", null);
        private final long startedAt = System.nanoTime();
        /** Mic thread only: whether the microphone delivers more than silence. */
        private final SilenceWatch silence = new SilenceWatch();
        /** Mic thread writes, main thread reads for the log: counts only. */
        private volatile int chunks;
        private volatile int deadChunks;
        /** Counted down once the server is ready or given up on, for a stop that came first. */
        private final CountDownLatch settled = new CountDownLatch(1);
        /** Main thread: the desktop is out of this session; its audio goes to the clip. */
        private boolean offline;
        /** Main thread: the mic thread has written its last audio. */
        private boolean micDone;
        /** Main thread: a partial is on screen. */
        private boolean shownPartial;
        private volatile boolean stopping;
        private volatile boolean cancelled;
        private volatile DictationSocket socket;
        /** The desktop is out of the session or it is over: a socket that opens late just closes. */
        private volatile boolean gaveUp;
        /** Set once the server is ready and cleared when it is given up on: where audio goes. */
        private volatile DictationSocket ready;

        Session(Listener listener, ClipStore.Recording recording) {
            this.listener = listener;
            this.recording = recording;
        }

        void begin() {
            listener.onState(State.CONNECTING);
            main.postDelayed(readyTimeout, READY_TIMEOUT_MS);
            new Thread(this::record, "dictation-mic").start();
            new Thread(this::converse, "dictation-socket").start();
        }

        /** Main thread: stop recording; then the final, or the clip once the mic is done. */
        void finish() {
            if (stopping) return;
            stopping = true;
            if (offline) {
                keepIfDone();
                return;
            }
            listener.onState(State.TRANSCRIBING);
            main.postDelayed(finishTimeout, FINAL_TIMEOUT_MS);
        }

        /** Main thread, after {@link #cancel}: release everything, no more callbacks. */
        void abort() {
            cancelled = true;
            release("cancelled");
            if (micDone) recording.discard();
        }

        /** {@code reason} for the desktop when we end a session it is still in; null when it ended it. */
        private void release(String reason) {
            stopping = true;
            main.removeCallbacks(readyTimeout);
            main.removeCallbacks(finishTimeout);
            ready = null;
            gaveUp = true;
            settled.countDown();
            hangUp(reason);
        }

        /** Main thread: lets the desktop go, telling it why when it is still listening. */
        private void hangUp(String reason) {
            DictationSocket s = socket;
            if (s == null) return;
            if (reason == null) {
                s.close();
                return;
            }
            // Sending is network I/O: off the main thread.
            Thread t = new Thread(() -> s.cancel(reason), "dictation-hangup");
            t.setDaemon(true);
            t.start();
        }

        /** Main thread: the session is over; later callbacks for it are dropped. */
        private void end() {
            end(null);
        }

        private void end(String reason) {
            if (session == this) session = null;
            release(reason);
        }

        /**
         * Main thread: the desktop is out of this session, from the start or partway. The audio
         * so far is in the recording, so nothing heard is lost; if it is not being saved, the
         * session fails instead.
         */
        private void goOffline(String reason, String why, Exception cause) {
            if (session != this || offline) return;
            offline = true;
            gaveUp = true;
            main.removeCallbacks(readyTimeout);
            main.removeCallbacks(finishTimeout);
            ready = null;
            settled.countDown();
            hangUp(reason);
            android.util.Log.i(PendingDictations.TAG, "dictation: desktop out (" + why
                    + (cause == null ? "" : ", " + cause.getClass().getSimpleName()) + ") " + sinceStart()
                    + ", recording for later");
            if (!recording.saved()) {
                fail(new DictationException(DictationException.Reason.UNREACHABLE,
                        "The desktop is offline and the phone could not save the recording", cause));
                return;
            }
            if (shownPartial) {
                shownPartial = false;
                listener.onPartial("");
            }
            if (stopping) keepIfDone();
            else listener.onState(State.OFFLINE_RECORDING);
        }

        /** Main thread: once stopped, offline and the mic has finished, keeps the clip. */
        private void keepIfDone() {
            if (session != this || !offline || !stopping || !micDone) return;
            end();
            PendingClip clip = recording.keep();
            if (clip == null) {
                listener.onError(new DictationException(DictationException.Reason.AUDIO,
                        "The recording could not be saved"));
                listener.onState(State.IDLE);
                return;
            }
            pending.saved();
            listener.onPending(clip);
            listener.onState(State.PENDING);
        }

        private void fail(DictationException error) {
            fail(error, null);
        }

        private void fail(DictationException error, String reason) {
            if (session != this) return;
            end(reason);
            if (micDone) recording.discard();
            listener.onError(error);
            listener.onState(State.IDLE);
        }

        /**
         * Main thread: the desktop says the audio holds nothing to transcribe ({@code code}).
         * Final: nothing is inserted and the recording is not kept, as no retry can help.
         */
        private void nothing(DictationException.Reason reason, String code) {
            if (session != this || offline) return;
            log("dictation: desktop had nothing to transcribe (" + code + "), " + chunks
                    + " chunks of which " + deadChunks + " digital silence, " + sinceStart());
            fail(new DictationException(reason, DictationException.nothingMessage(reason)));
        }

        /**
         * Main thread: the desktop answered with an error that a retry may cure (its Whisper slow
         * or down). Shown as a desktop error, not as away; the audio is kept as if it were.
         */
        private void desktopError(String code) {
            if (session != this || offline) return;
            log("dictation: desktop error (" + (code.isEmpty() ? "no code" : code) + ") " + sinceStart());
            listener.onDesktopError();
            goOffline(null, "desktop error", null);
        }

        /**
         * Main thread: the microphone gives only silence ({@code verdict}). With nothing heard
         * before, the session fails and keeps nothing; after real audio, what was heard is kept
         * as if stop was tapped.
         */
        private void silenced(SilenceWatch.Verdict verdict, boolean heard) {
            if (session != this || stopping) return;
            log("dictation: microphone silenced (" + verdict + "), " + chunks + " chunks of which "
                    + deadChunks + " digital silence, " + sinceStart()
                    + (heard ? ", keeping what was heard" : ", nothing kept"));
            if (heard) {
                finish();
                return;
            }
            fail(new DictationException(DictationException.Reason.SILENCED,
                    DictationException.nothingMessage(DictationException.Reason.SILENCED)), "silenced");
        }

        private String sinceStart() {
            return (System.nanoTime() - startedAt) / 1_000_000 + " ms into the session";
        }

        /** Runs {@code r} on the main thread while this is still the live session. */
        private void post(Runnable r) {
            main.post(() -> {
                if (session == this) r.run();
            });
        }

        /** Mic thread: records until stop, saving everything and sending while the server is ready. */
        private void record() {
            ArrayDeque<byte[]> unsent = new ArrayDeque<>();
            boolean opened = false;
            boolean reportedSilence = false;
            try (Microphone mic = Microphone.open(context, micContext)) {
                opened = true;
                while (!stopping) {
                    byte[] chunk = new byte[CHUNK_BYTES];
                    int n = mic.read(chunk, () -> stopping);
                    if (n == 0) continue;
                    if (!reportedSilence) {
                        SilenceWatch.Verdict v = silence.chunk(chunk, n, mic.androidSilenced());
                        chunks = silence.chunks();
                        deadChunks = silence.deadChunks();
                        if (v != SilenceWatch.Verdict.LIVE) {
                            reportedSilence = true;
                            boolean heard = silence.heard();
                            post(() -> silenced(v, heard));
                        }
                    }
                    LevelListener l = levels;
                    if (l != null) l.onLevel(Microphone.dbfs(chunk, n));
                    if (!recording.write(chunk, n)) {
                        // A storage or session limit: end the session as if tapped.
                        post(this::finish);
                        break;
                    }
                    enqueue(unsent, n == chunk.length ? chunk : Arrays.copyOf(chunk, n));
                    send(unsent);
                }
            } catch (Microphone.Failure e) {
                if (!opened) {
                    int times = OPEN_FAILURES.incrementAndGet(e.cause.ordinal());
                    log("dictation: the microphone did not open: " + e.cause + " (" + times
                            + (times == 1 ? " time" : " times") + " since the keyboard started)");
                    micFinished();
                    if (!cancelled) post(() -> fail(new DictationException(
                            e.cause == Microphone.Failure.Cause.DENIED ? DictationException.Reason.PERMISSION
                                    : DictationException.Reason.AUDIO, e.getMessage(), e)));
                    return;
                }
                // The mic failed partway: keep what was heard, as if the user tapped stop.
                log("dictation: the microphone failed partway: " + e.cause);
                post(this::finish);
            }
            micFinished();
            if (cancelled) return;
            try {
                // A stop before the server was ready waits for it, or for the session to go offline.
                if (ready == null) settled.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            DictationSocket s = ready;
            if (s == null) return;
            try {
                send(unsent);
                s.sendText("{\"type\":\"stop\"}");
            } catch (IOException e) {
                post(() -> goOffline(null, "send failed", e));
            }
        }

        /** Mic thread: tells the main thread the recording is complete. */
        private void micFinished() {
            main.post(() -> {
                micDone = true;
                if (cancelled || session != this) {
                    // Over (final, failure or cancel) before the mic finished: only keepIfDone,
                    // which needs micDone, keeps a recording, so nothing will.
                    recording.discard();
                    return;
                }
                keepIfDone();
            });
        }

        /** Mic thread: sends what is unsent while the server is ready. */
        private void send(ArrayDeque<byte[]> unsent) {
            DictationSocket s = ready;
            if (s == null) return;
            try {
                drain(unsent, s);
            } catch (IOException e) {
                ready = null;
                post(() -> goOffline(null, "send failed", e));
            }
        }

        /** Socket thread: connects, then delivers what the server says. */
        private void converse() {
            DictationSocket s;
            try {
                s = DictationSocket.connect(uri, CONNECT_TIMEOUT_MS, HANDSHAKE_TIMEOUT_MS);
            } catch (IOException e) {
                // Classes and timings only: why the desktop did not take the session.
                log("dictation: " + e.getMessage() + (cancelled ? " (already cancelled)" : ""));
                post(() -> goOffline(null, "unreachable", e));
                return;
            }
            socket = s;
            try {
                // Given up while connecting (cancel, a final, or not ready in time): nothing to say.
                if (cancelled || gaveUp) return;
                s.sendText(START);
                while (true) {
                    WebSocketFrames.Message m = s.read();
                    if (m == null) throw new IOException("the server closed the session");
                    if (m.opcode != WebSocketFrames.TEXT) continue;
                    JSONObject e = new JSONObject(m.text());
                    String type = e.optString("type");
                    String text = e.optString("text");
                    if (type.equals("ready")) {
                        post(() -> {
                            if (offline) return;
                            main.removeCallbacks(readyTimeout);
                            ready = s;
                            settled.countDown();
                            listener.onState(stopping ? State.TRANSCRIBING : State.LISTENING);
                            pending.kick(0);
                        });
                    } else if (type.equals("partial")) {
                        post(() -> {
                            if (offline) return;
                            shownPartial = !text.isEmpty();
                            listener.onPartial(text);
                        });
                    } else if (type.equals("final")) {
                        String code = e.optString("code", "");
                        DictationException.Reason nothing = DictationException.Reason.ofFinalCode(code);
                        if (nothing != null) {
                            post(() -> nothing(nothing, code));
                            return;
                        }
                        post(() -> {
                            if (offline) return;
                            end();
                            if (micDone) recording.discard();
                            listener.onFinal(text);
                            listener.onState(State.IDLE);
                        });
                        return;
                    } else if (type.equals("error")) {
                        String code = e.optString("code", "");
                        DictationException.Reason nothing = DictationException.Reason.ofErrorCode(code);
                        if (nothing != null) {
                            post(() -> nothing(nothing, code));
                        } else {
                            // e.g. the desktop's Whisper is slow or down: the recording is still good.
                            post(() -> desktopError(code));
                        }
                        return;
                    }
                }
            } catch (IOException | JSONException e) {
                post(() -> goOffline(null, "dropped", e));
            } finally {
                s.close();
            }
        }
    }

    /** Counts, classes, codes and timings only: never audio or text. */
    private static void log(String line) {
        android.util.Log.i(PendingDictations.TAG, line);
    }

    static void enqueue(ArrayDeque<byte[]> pending, byte[] chunk) {
        if (pending.size() == MAX_PENDING_CHUNKS) pending.removeFirst();
        pending.addLast(chunk);
    }

    private static void drain(ArrayDeque<byte[]> pending, DictationSocket s) throws IOException {
        while (!pending.isEmpty()) {
            byte[] p = pending.peekFirst();
            s.sendBinary(p, 0, p.length);
            pending.removeFirst();
        }
    }
}
