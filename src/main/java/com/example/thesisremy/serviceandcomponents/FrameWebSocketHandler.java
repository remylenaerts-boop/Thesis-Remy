package com.example.thesisremy.serviceandcomponents;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
    Receives the camera feed from the Android glasses over WebSocket.

    Each incoming frame is saved as a JPEG to frames/.

    Wi-Fi hiccup handling: when the glasses disconnect, stitching does NOT happen
    immediately. Instead a 5-second timer starts. If the glasses reconnect within
    those 5 seconds the timer is cancelled and the session continues seamlessly —
    frames keep their numbers and nothing is lost. Only if the glasses stay
    disconnected for the full 5 seconds is the video stitched and frames cleared.

    Annotated video: if pool_detector.py saved annotated frames (with detection
    circles drawn) to annotated/, those are used for the video instead of the raw
    frames so the detection result is visible in the recording.

    Requires ffmpeg — install with: winget install ffmpeg (Windows)
                                     brew install ffmpeg  (macOS/Linux)
*/
@Component
public class FrameWebSocketHandler extends BinaryWebSocketHandler {

    private static final String FRAMES_DIR    = "frames/";
    private static final String ANNOTATED_DIR = "annotated/";
    private static final String VIDEOS_DIR    = "videos/";

    // How long to wait after a disconnect before stitching (milliseconds).
    // If the glasses reconnect within this window, stitching is cancelled.
    private static final long STITCH_DELAY_MS = 5000;

    private final AtomicInteger frameCounter = new AtomicInteger(0);

    private volatile WebSocketSession activeSession = null;

    private final ServerState serverState;

    // Scheduler used for the delayed stitch — single thread is enough
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Reference to the pending stitch task so we can cancel it on reconnect
    private volatile ScheduledFuture<?> pendingStitch = null;

    public FrameWebSocketHandler(ServerState serverState) {
        this.serverState = serverState;
        new File(FRAMES_DIR).mkdirs();
        new File(ANNOTATED_DIR).mkdirs();
        new File(VIDEOS_DIR).mkdirs();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        activeSession = session;

        // If a stitch was pending from a previous hiccup, cancel it — session is resuming
        if (pendingStitch != null && !pendingStitch.isDone()) {
            pendingStitch.cancel(false);
            System.out.println("[WS] Glasses reconnected within timeout — stitch cancelled, session continues.");
        } else {
            System.out.println("[WS] Glasses connected — session: " + session.getId());
        }
    }

    // Each incoming frame is raw JPEG bytes — save it to disk with a numbered filename
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        // Only save frames when camera capture is explicitly enabled from the dashboard.
        // When disabled the glasses are also told to stop sending, so this is a safety net.
        if (!serverState.isCameraEnabled()) return;

        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);

        String filename = String.format(FRAMES_DIR + "frame_%05d.jpg", frameCounter.getAndIncrement());
        Files.write(Paths.get(filename), bytes);
        System.out.println("[WS] Saved: " + filename + " (" + bytes.length + " bytes)");
    }

    /*
        When the glasses disconnect, start a 5-second countdown before stitching.
        This gives Wi-Fi hiccups time to recover without producing a premature video.
        If the glasses reconnect in time, afterConnectionEstablished() cancels the task.
    */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSession = null;
        System.out.println("[WS] Glasses disconnected — status: " + status);

        if (frameCounter.get() == 0) {
            System.out.println("[WS] No frames received, skipping video stitch.");
            return;
        }

        System.out.println("[WS] Waiting " + (STITCH_DELAY_MS / 1000) + "s before stitching — reconnect to cancel.");

        pendingStitch = scheduler.schedule(() -> {
            try {
                stitchAndClean();
            } catch (Exception e) {
                System.err.println("[WS] Video stitch failed: " + e.getMessage());
            }
        }, STITCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // Runs ffmpeg to turn the saved JPEG sequence into an MP4, then clears the frames
    private void stitchAndClean() throws IOException, InterruptedException {
        String timestamp  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = VIDEOS_DIR + "video_" + timestamp + ".mp4";

        // Use annotated frames if pool_detector.py produced them — they show the detection circle.
        // Fall back to raw frames if the detector was not running this session.
        File[] annotatedFrames = new File(ANNOTATED_DIR).listFiles((d, n) -> n.matches("frame_\\d{5}\\.jpg"));
        boolean hasAnnotated   = annotatedFrames != null && annotatedFrames.length > 0;
        String  sourceDir      = hasAnnotated ? ANNOTATED_DIR : FRAMES_DIR;

        System.out.println("[WS] Stitching " + frameCounter.get() + " frames from " + sourceDir + " → " + outputPath);
        if (hasAnnotated) System.out.println("[WS] Using annotated frames — detection circles will be visible.");

        Process process = new ProcessBuilder(
                resolveFfmpeg(), "-y",
                "-framerate", String.valueOf(serverState.getVideoFramerate()),
                "-i", sourceDir + "frame_%05d.jpg",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                outputPath)
            .redirectErrorStream(true)
            .start();

        process.getInputStream().transferTo(OutputStream.nullOutputStream());

        if (process.waitFor() != 0) {
            System.err.println("[WS] ffmpeg failed — frames kept in " + sourceDir + " so nothing is lost.");
            return;
        }

        System.out.println("[WS] Video saved: " + outputPath);
        clearFrames();
    }

    /*
        Tries to find ffmpeg in two places:
          1. The system PATH — works on any OS where ffmpeg was installed normally
          2. The winget shortcut folder — Windows users who ran "winget install ffmpeg"
             have ffmpeg here automatically, no PATH setup needed
    */
    private String resolveFfmpeg() {
        try {
            new ProcessBuilder("ffmpeg", "-version").start().destroy();
            return "ffmpeg";
        } catch (IOException ignored) {}

        String wingetPath = System.getProperty("user.home") + "/AppData/Local/Microsoft/WinGet/Links/ffmpeg.exe";
        return new File(wingetPath).exists() ? wingetPath : "ffmpeg";
    }

    // Deletes all raw and annotated frames, resets the counter so the next session starts fresh
    private void clearFrames() {
        int deleted = 0;

        File[] raw = new File(FRAMES_DIR).listFiles((d, n) -> n.matches("frame_\\d{5}\\.jpg"));
        if (raw != null) for (File f : raw) if (f.delete()) deleted++;

        File[] annotated = new File(ANNOTATED_DIR).listFiles((d, n) -> n.matches("frame_\\d{5}\\.jpg"));
        if (annotated != null) for (File f : annotated) if (f.delete()) deleted++;

        frameCounter.set(0);
        System.out.println("[WS] Cleared " + deleted + " frames (raw + annotated), ready for next session.");
    }

    public int getFrameCount() {
        return frameCounter.get();
    }

    public boolean isWebSocketActive() {
        return activeSession != null && activeSession.isOpen();
    }
}
