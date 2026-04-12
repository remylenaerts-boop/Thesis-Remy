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
import java.util.concurrent.atomic.AtomicInteger;

/*
    Receives the camera feed from the Android glasses over WebSocket.

    Each incoming frame is saved as a JPEG to frames/.
    When the glasses disconnect, all saved frames are automatically stitched
    into a timestamped MP4 in videos/, and the frames/ folder is cleared
    so the next session always starts fresh.

    Requires ffmpeg — install with: winget install ffmpeg (Windows)
                                     brew install ffmpeg  (macOS/Linux)
*/
@Component
public class FrameWebSocketHandler extends BinaryWebSocketHandler {

    private static final String FRAMES_DIR = "frames/";
    private static final String VIDEOS_DIR = "videos/";

    // Counts incoming frames — AtomicInteger keeps the count correct if frames arrive simultaneously
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    public FrameWebSocketHandler() {
        new File(FRAMES_DIR).mkdirs();
        new File(VIDEOS_DIR).mkdirs();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        System.out.println("[WS] Glasses connected — session: " + session.getId());
    }

    // Each incoming frame is raw JPEG bytes — save it to disk with a numbered filename
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);

        String filename = String.format(FRAMES_DIR + "frame_%05d.jpg", frameCounter.getAndIncrement());
        Files.write(Paths.get(filename), bytes);
        System.out.println("[WS] Saved: " + filename + " (" + bytes.length + " bytes)");
    }

    // When the glasses disconnect, stitch everything into a video and clean up
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("[WS] Glasses disconnected — status: " + status);

        if (frameCounter.get() == 0) {
            System.out.println("[WS] No frames received, skipping video stitch.");
            return;
        }

        try {
            stitchAndClean();
        } catch (Exception e) {
            System.err.println("[WS] Video stitch failed: " + e.getMessage());
        }
    }

    // Runs ffmpeg to turn the saved JPEG sequence into an MP4, then clears the frames
    private void stitchAndClean() throws IOException, InterruptedException {
        String timestamp  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = VIDEOS_DIR + "video_" + timestamp + ".mp4";

        System.out.println("[WS] Stitching " + frameCounter.get() + " frames → " + outputPath);

        Process process = new ProcessBuilder(
                resolveFfmpeg(), "-y",
                "-framerate", "30",
                "-i", FRAMES_DIR + "frame_%05d.jpg",
                "-c:v", "libx264",    // H.264 encoding — plays on virtually any device
                "-pix_fmt", "yuv420p", // required pixel format for broad player compatibility
                outputPath)
            .redirectErrorStream(true)
            .start();

        // Drain ffmpeg output — if we don't read it, the process can freeze on a full pipe buffer
        process.getInputStream().transferTo(OutputStream.nullOutputStream());

        if (process.waitFor() != 0) {
            System.err.println("[WS] ffmpeg failed — frames kept in " + FRAMES_DIR + " so nothing is lost.");
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
            return "ffmpeg"; // found on PATH
        } catch (IOException ignored) {}

        String wingetPath = System.getProperty("user.home") + "/AppData/Local/Microsoft/WinGet/Links/ffmpeg.exe";
        return new File(wingetPath).exists() ? wingetPath : "ffmpeg";
    }

    // Deletes all saved frames and resets the counter so the next session starts from frame_00000
    private void clearFrames() {
        File[] files = new File(FRAMES_DIR).listFiles((d, name) -> name.matches("frame_\\d{5}\\.jpg"));
        int deleted = 0;
        if (files != null) for (File f : files) if (f.delete()) deleted++;
        frameCounter.set(0);
        System.out.println("[WS] Cleared " + deleted + " frames, ready for next session.");
    }

    // Used by ControllerClass to serve GET /frames/count
    public int getFrameCount() {
        return frameCounter.get();
    }
}
