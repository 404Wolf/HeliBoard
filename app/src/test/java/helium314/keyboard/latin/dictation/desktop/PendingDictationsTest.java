// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The courier against the desktop's answers: a clip the desktop errs on never holds up newer
 * ones and stops after its tries, one with nothing to transcribe is dropped, and the upload reads
 * the codes as agreed. Temporary folders only: never the phone's own queue.
 */
@RunWith(RobolectricTestRunner.class) // org.json is Android's
public final class PendingDictationsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private FakeServer server;

    @After public void stop() throws IOException {
        worker.shutdownNow();
        if (server != null) server.close();
    }

    private ClipStore store() {
        return new ClipStore(new File(dir.getRoot(), "pending"));
    }

    private static PendingClip record(ClipStore store, String target, int bytes) {
        ClipStore.Recording r = store.record(target, 1234);
        byte[] chunk = new byte[bytes];
        java.util.Arrays.fill(chunk, (byte) 7);
        assertTrue(r.write(chunk, chunk.length));
        return r.keep();
    }

    /** Collects deliveries and delivers back at once. */
    private static class Composer implements PendingDictations.Listener {
        final List<String> got = new ArrayList<>();
        final List<DictationException.Reason> nothing = new ArrayList<>();
        PendingDictations from;

        @Override public void onTranscribed(PendingClip clip, String text) {
            got.add(clip.id + "=" + text);
            from.delivered(clip.id);
        }

        @Override public void onChanged() { }

        @Override public void onNothing(PendingClip clip, DictationException.Reason reason) {
            nothing.add(reason);
        }
    }

    @Test public void aClipTheDesktopErrsOnNeverHoldsUpNewerOnesAndStopsAfterItsTries() throws Exception {
        ClipStore store = store();
        PendingClip bad = record(store, "1", 10);
        PendingClip good = record(store, "2", 10);
        List<String> tried = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (clip, file) -> {
            tried.add(clip.id);
            if (clip.id.equals(bad.id)) throw new PendingDictations.DesktopError("");
            return PendingDictations.Transcript.of("words");
        }, worker, Runnable::run);
        Composer composer = new Composer();
        composer.from = pending;
        pending.setListener(composer);
        pending.pass();
        assertEquals("the newer clip went past the failing one", List.of(bad.id, good.id), tried);
        assertEquals(List.of(good.id + "=words"), composer.got);
        for (int i = 1; i < ClipStore.MAX_SERVER_ERRORS; i++) pending.pass();
        assertEquals(1, pending.stuck());
        assertEquals("kept, with its audio", 1, pending.waiting().size());
        assertTrue(store.audio(bad.id).exists());
        tried.clear();
        pending.pass();
        assertTrue("a stuck clip is not tried by itself", tried.isEmpty());
        pending.retryStuck(); // the next tap on the mic
        worker.submit(() -> { }).get();
        assertEquals(0, pending.stuck());
    }

    @Test public void anUnreachableDesktopStillStopsThePassAndCountsNothing() {
        ClipStore store = store();
        PendingClip a = record(store, "1", 10);
        record(store, "2", 10);
        int[] tries = {0};
        PendingDictations pending = new PendingDictations(store, (clip, file) -> {
            tries[0]++;
            throw new IOException("refused");
        }, worker, Runnable::run);
        for (int i = 0; i < 5; i++) pending.pass();
        assertEquals(5, tries[0]);
        assertEquals(0, pending.stuck());
        assertEquals(a.id, pending.waiting().get(0).id);
    }

    @Test public void theErrorCountSurvivesARestart() {
        PendingClip a = record(store(), "1", 10);
        ClipStore store = store();
        for (int i = 0; i < ClipStore.MAX_SERVER_ERRORS; i++) store.failed(a.id);
        ClipStore again = store();
        assertEquals(1, again.stuck());
        assertTrue(again.due().isEmpty());
        assertTrue(again.retryStuck());
        assertEquals(List.of(a), store().due());
    }

    @Test public void aClipWithNothingToTranscribeIsDroppedAndTheNextIsSent() {
        ClipStore store = store();
        PendingClip silent = record(store, "1", 10);
        PendingClip spoken = record(store, "2", 10);
        PendingDictations pending = new PendingDictations(store, (clip, file) -> clip.id.equals(silent.id)
                ? PendingDictations.Transcript.nothing(DictationException.Reason.SILENT_AUDIO, "silent_audio")
                : PendingDictations.Transcript.of("words"), worker, Runnable::run);
        Composer composer = new Composer();
        composer.from = pending;
        pending.setListener(composer);
        pending.pass();
        assertEquals(List.of(silent.id + "=", spoken.id + "=words"), composer.got);
        assertEquals(List.of(DictationException.Reason.SILENT_AUDIO), composer.nothing);
        assertEquals(0, pending.count());
        assertFalse(store.audio(silent.id).exists());
    }

    @Test public void codesAreReadAsAgreed() {
        assertEquals(DictationException.Reason.SILENT_AUDIO, DictationException.Reason.ofFinalCode("silent_audio"));
        assertEquals(DictationException.Reason.QUIET_AUDIO, DictationException.Reason.ofFinalCode("quiet_audio"));
        assertEquals(DictationException.Reason.NO_AUDIO, DictationException.Reason.ofFinalCode("no_audio"));
        assertEquals(DictationException.Reason.NOTHING, DictationException.Reason.ofFinalCode("future_code"));
        assertNull(DictationException.Reason.ofFinalCode(""));
        assertNull(DictationException.Reason.ofErrorCode("backend_slow"));
        assertNull(DictationException.Reason.ofErrorCode(""));
        assertEquals(DictationException.Reason.QUIET_AUDIO, DictationException.Reason.ofErrorCode("quiet_audio"));
    }

    @Test public void anUploadReadsTheDesktopsAnswerAsAgreed() throws Exception {
        File clip = dir.newFile("clip.pcm");
        Files.write(clip.toPath(), new byte[ClipStore.BYTES_PER_SECOND]);
        server = new FakeServer("{\"type\":\"final\",\"text\":\"\",\"code\":\"quiet_audio\",\"message\":\"m\"}");
        PendingDictations.Transcript t = PendingDictations.upload(server.uri(), clip);
        assertEquals(DictationException.Reason.QUIET_AUDIO, t.nothing);
        assertEquals("the start message carries no capabilities",
                "{\"type\":\"start\",\"sample_rate\":16000}", server.start);
        server.close();
        server = new FakeServer("{\"type\":\"error\",\"message\":\"x\"}");
        try {
            PendingDictations.upload(server.uri(), clip);
            throw new AssertionError("an error without a code is the desktop's, to retry");
        } catch (PendingDictations.DesktopError expected) {
            assertEquals("", expected.code);
        }
        server.close();
        server = new FakeServer("{\"type\":\"final\",\"text\":\"words\"}");
        assertEquals("words", PendingDictations.upload(server.uri(), clip).text);
    }

    /** One session: reads the start and the audio, answers stop with {@code answer}. */
    private static final class FakeServer implements java.io.Closeable {
        private final ServerSocket socket = new ServerSocket(0);
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
        volatile String start;

        FakeServer(String answer) throws IOException {
            Thread t = new Thread(() -> {
                try (Socket s = socket.accept()) {
                    open.add(s);
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    String key = null;
                    StringBuilder line = new StringBuilder();
                    for (int c; (c = in.read()) >= 0; ) {
                        if (c == '\r') continue;
                        if (c != '\n') {
                            line.append((char) c);
                            continue;
                        }
                        if (line.length() == 0) break;
                        if (line.toString().startsWith("Sec-WebSocket-Key:")) key = line.substring(18).trim();
                        line.setLength(0);
                    }
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptFor(key) + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    WebSocketFrames.Reader reader = new WebSocketFrames.Reader(in, 1 << 20);
                    start = reader.next().text();
                    while (true) {
                        WebSocketFrames.Message m = reader.next();
                        if (m.opcode == WebSocketFrames.TEXT
                                && new JSONObject(m.text()).getString("type").equals("stop")) break;
                    }
                    byte[] b = answer.getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream f = new ByteArrayOutputStream();
                    f.write(0x80 | WebSocketFrames.TEXT);
                    f.write(b.length);
                    f.write(b, 0, b.length);
                    out.write(f.toByteArray());
                    out.flush();
                    Thread.sleep(200);
                } catch (Exception gone) {
                    // The phone hung up.
                }
            }, "fake-dictation-server");
            t.setDaemon(true);
            t.start();
        }

        URI uri() {
            return URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/v1/dictate");
        }

        @Override public void close() throws IOException {
            socket.close();
            synchronized (open) {
                for (Socket s : open) s.close();
            }
        }
    }
}
