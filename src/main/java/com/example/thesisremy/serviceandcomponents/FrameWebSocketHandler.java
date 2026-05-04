package com.example.thesisremy.serviceandcomponents;

import org.springframework.scheduling.annotation.Scheduled;
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
    those 5 seconds the timer is cancelled and the session continues seamlessly.
    Frames keep their numbers and nothing is lost. Only if the glasses stay
    disconnected for the full 5 seconds is the video stitched and frames cleared.
    This solved the problem where videos would be stitched during use.

    Annotated video: if pool_detector.py saved annotated frames (with detection
    circles drawn) to annotated/, those are used for the video instead of the raw
    frames so the detection result is visible in the recording.

    Requires ffmpeg install with: winget install ffmpeg (Windows)
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

    // Timestamp of the most recently received frame. The dashboard's "streaming"
    // indicator is true when this is fresh (within STREAMING_FRESHNESS_MS).
    // Read-side liveness: no probes, no false positives — if frames are arriving,
    // the connection is by definition alive.
    private static final long STREAMING_FRESHNESS_MS = 2000;
    private volatile long lastFrameMs = 0;

    // If we have captured frames on disk but no new frame has arrived in this
    // long, treat the session as ended and stitch the video. This catches
    // ungraceful disconnects (glasses powered off, OS killed the app) where
    // afterConnectionClosed never fires.
    private static final long IDLE_STITCH_MS = 10_000;

    private final AtomicInteger frameCounter = new AtomicInteger(0);

    private final ServerState serverState;

    // Scheduler used for the delayed stitch, single thread is enough.
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
        // If a stitch was pending from a previous hiccup, cancel it then session resumes
        if (pendingStitch != null && !pendingStitch.isDone()) {
            pendingStitch.cancel(false);
            System.out.println("[WS] Glasses reconnected within timeout, stitch cancelled, session continues.");
        } else {
            System.out.println("[WS] Glasses connected, session: " + session.getId());
        }
    }

    // Each incoming frame is raw JPEG bytes, save it to disk with a numbered filename
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        // Stamp every arriving frame so isWebSocketActive() can tell that data is flowing.
        // Done BEFORE the cameraEnabled check so a glasses-keepalive (frames sent while the
        // dashboard happens to have the camera disabled) still counts as proof of life.
        lastFrameMs = System.currentTimeMillis();

        // Only save frames when camera capture is explicitly enabled from the dashboard.
        // When disabled, the glasses are also told to stop sending, so this is a safety net.
        if (!serverState.isCameraEnabled()) return;

        byte[] bytes = new byte[message.getPayload().remaining()]; //First a bytes array is made to the .remaining (amount of data in the message)
        message.getPayload().get(bytes); //and now the payload is put into the bytes array

        String filename = String.format(FRAMES_DIR + "frame_%05d.jpg", frameCounter.getAndIncrement());
        Files.write(Paths.get(filename), bytes);
    }

    /*
        When the glasses disconnect, start a 5-second countdown before stitching.
        This gives Wi-Fi hiccups time to recover without producing a premature video.
        If the glasses reconnect in time, afterConnectionEstablished() cancels the task.
    */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("[WS] Glasses disconnected: status: " + status);

        if (frameCounter.get() == 0) {
            System.out.println("[WS] No frames received, skipping video stitch.");
            return;
        }

        System.out.println("[WS] Waiting " + (STITCH_DELAY_MS / 1000) + "s before stitching, reconnect to cancel.");

        pendingStitch = scheduler.schedule(() -> {
            try {
                stitchAndClean();
            } catch (Exception e) {
                System.err.println("[WS] Video stitch failed: " + e.getMessage());
            }
        }, STITCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /*
        Backup stitch path for ungraceful disconnects.

        afterConnectionClosed only fires when Spring is told the session ended,
        either by a clean close frame from the client or by TCP detecting the
        socket is dead. If the glasses are powered off mid-session, neither of
        those happens for a long time (TCP keepalive default is hours), so the
        video would never be stitched.

        This check runs every 5 seconds and stitches whenever:
          - frames have been captured this session (frameCounter > 0), AND
          - no frame has arrived in IDLE_STITCH_MS, AND
          - no other stitch is already pending.

        It coexists with afterConnectionClosed cleanly: a graceful disconnect
        schedules pendingStitch first, and this check skips while it's pending.
        Once stitch runs, frameCounter resets to 0 and the check skips again.
    */
    @Scheduled(fixedRate = 5000)
    public void stitchOnIdle() {
        if (frameCounter.get() == 0) return;
        if (pendingStitch != null && !pendingStitch.isDone()) return;
        if (System.currentTimeMillis() - lastFrameMs < IDLE_STITCH_MS) return;

        System.out.println("[WS] No frames for " + (IDLE_STITCH_MS / 1000) + "s with frames on disk, stitching idle session.");
        pendingStitch = scheduler.schedule(() -> {
            try {
                stitchAndClean();
            } catch (Exception e) {
                System.err.println("[WS] Idle stitch failed: " + e.getMessage());
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    // Runs ffmpeg to turn the saved JPEG sequence into an MP4, then clears the frames
    private void stitchAndClean() throws IOException, InterruptedException {
        String timestamp  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = VIDEOS_DIR + "video_" + timestamp + ".mp4";

        // Use annotated frames if pool_detector.py produced them, they show the detection circle.
        // Fall back to raw frames if the detector was not running this session.
        File[] annotatedFrames = new File(ANNOTATED_DIR).listFiles((d, n) -> n.matches("frame_\\d{5}\\.jpg"));
        boolean hasAnnotated   = annotatedFrames != null && annotatedFrames.length > 0;
        String  sourceDir      = hasAnnotated ? ANNOTATED_DIR : FRAMES_DIR;

        System.out.println("[WS] Stitching " + frameCounter.get() + " frames from " + sourceDir + " --> " + outputPath);
        if (hasAnnotated) System.out.println("[WS] Using annotated frames: detection circles will be visible.");

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
            System.err.println("[WS] ffmpeg failed: frames kept in " + sourceDir + " so nothing is lost.");
            return;
        }

        System.out.println("[WS] Video saved: " + outputPath);
        clearFrames();
    }

    /*
        Tries to find ffmpeg in two places:
          1. The system PATH, works on any OS where ffmpeg was installed normally
          2. The winget shortcut folder, Windows users who ran "winget install ffmpeg"
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

    /*
        Returns true when a frame has arrived recently (within STREAMING_FRESHNESS_MS).
        This is a read-side liveness check: we trust observed traffic, not the underlying
        socket state. session.isOpen() can lie for several minutes on dead-but-not-detected
        TCP connections (glasses powered off without a clean close); a fresh frame timestamp
        cannot.
    */
    public boolean isWebSocketActive() {
        return System.currentTimeMillis() - lastFrameMs < STREAMING_FRESHNESS_MS;
    }
}
