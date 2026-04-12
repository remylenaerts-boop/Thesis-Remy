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
    Handles the WebSocket connection on the /frames endpoint.

    Session lifecycle:
      1. Glasses connect         → afterConnectionEstablished()
      2. Frames arrive as bytes  → handleBinaryMessage()  — saved to frames/
      3. Glasses disconnect      → afterConnectionClosed() — stitch video → save to videos/ → clear frames/

    After each session the frames/ folder is empty and a timestamped .mp4 is added to videos/.
    The frame counter resets to 0 so the next session always starts from frame_00000.jpg.

    Requires ffmpeg — either on the system PATH, or installed via:
        winget install ffmpeg   (Windows)
        brew install ffmpeg     (macOS)
        apt install ffmpeg      (Linux)
*/
@Component
public class FrameWebSocketHandler extends BinaryWebSocketHandler {

    private static final String FRAMES_DIR = "frames/";
    private static final String VIDEOS_DIR = "videos/";

    // AtomicInteger so the counter stays correct even if two frames arrive at the same time
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    public FrameWebSocketHandler() {
        // Create both directories on startup if they don't exist yet
        new File(FRAMES_DIR).mkdirs();
        new File(VIDEOS_DIR).mkdirs();
    }

    // Called once when the glasses successfully open the WebSocket connection
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        System.out.println("[WS] Glasses connected — session: " + session.getId());
    }

    // Called every time a frame arrives from the glasses
    @Override
    protected void handleBinaryMessage(WebSocketSession session,
                                       BinaryMessage message) throws IOException {

        // Copy the payload bytes out of the ByteBuffer
        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);

        // Build the filename: frame_00000.jpg, frame_00001.jpg, ...
        int    index    = frameCounter.getAndIncrement();
        String filename = String.format(FRAMES_DIR + "frame_%05d.jpg", index);

        Files.write(Paths.get(filename), bytes);
        System.out.println("[WS] Saved: " + filename + "  (" + bytes.length + " bytes)");
    }

    // Called when the glasses disconnect — triggers stitch + cleanup
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("[WS] Glasses disconnected — status: " + status);

        int total = frameCounter.get();
        if (total == 0) {
            // Nothing to stitch if no frames were received this session
            System.out.println("[WS] No frames received, skipping video stitch.");
            return;
        }

        try {
            stitchAndClean(total);
        } catch (Exception e) {
            System.err.println("[WS] Video stitch failed: " + e.getMessage());
        }
    }

    /*
        Runs ffmpeg to encode all saved JPEG frames into a single MP4, then deletes the frames.

        Output filename is timestamped so every session produces a unique file, e.g.:
            videos/video_20260412_143022.mp4

        ffmpeg flags used:
          -y               overwrite output file without asking (safety net)
          -framerate 30    treat the image sequence as 30 fps
          -i frame_%05d    read frame_00000.jpg, frame_00001.jpg, ... in order
          -c:v libx264     encode with H.264 (widely supported)
          -pix_fmt yuv420p pixel format required for compatibility with most players
    */
    private void stitchAndClean(int frameCount) throws IOException, InterruptedException {
        String timestamp  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = VIDEOS_DIR + "video_" + timestamp + ".mp4";

        System.out.println("[WS] Stitching " + frameCount + " frames → " + outputPath);

        ProcessBuilder pb = new ProcessBuilder(
            resolveFfmpeg(), "-y",
            "-framerate", "30",
            "-i", FRAMES_DIR + "frame_%05d.jpg",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            outputPath
        );
        pb.redirectErrorStream(true); // merge stdout + stderr into one stream

        Process process = pb.start();

        // Drain ffmpeg output so the process doesn't block on a full pipe buffer
        try (OutputStream sink = OutputStream.nullOutputStream()) {
            process.getInputStream().transferTo(sink);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("[WS] ffmpeg exited with code " + exitCode + " — video may be incomplete.");
            return; // keep the frames so nothing is lost
        }

        System.out.println("[WS] Video saved: " + outputPath);

        // Delete all frame files and reset the counter so the next session starts fresh
        clearFrames();
    }

    /*
        Finds the ffmpeg executable without requiring it to be on the system PATH.

        Resolution order:
          1. "ffmpeg" — works if ffmpeg is on PATH (Linux, macOS, or Windows with PATH configured)
          2. winget Links folder — winget always creates a shortcut here for every user on Windows,
             so "winget install ffmpeg" is all that is needed, no manual PATH editing required

        If neither location works, the stitchAndClean() call will throw an IOException and
        the frames will be kept on disk so no data is lost.
    */
    private String resolveFfmpeg() {
        // 1. Check if ffmpeg is available on the system PATH
        try {
            new ProcessBuilder("ffmpeg", "-version").start().destroy();
            return "ffmpeg";
        } catch (IOException ignored) {}

        // 2. Fall back to the standard winget install location for the current user
        String wingetPath = System.getProperty("user.home")
            + "/AppData/Local/Microsoft/WinGet/Links/ffmpeg.exe";
        if (new File(wingetPath).exists()) {
            return wingetPath;
        }

        // No ffmpeg found — return "ffmpeg" anyway so the error message from the OS is clear
        return "ffmpeg";
    }

    // Deletes every frame_xxxxx.jpg in frames/ and resets the counter to 0
    private void clearFrames() {
        File   dir   = new File(FRAMES_DIR);
        File[] files = dir.listFiles((d, name) -> name.matches("frame_\\d{5}\\.jpg"));
        int deleted  = 0;
        if (files != null) {
            for (File f : files) {
                if (f.delete()) deleted++;
            }
        }
        frameCounter.set(0);
        System.out.println("[WS] Cleared " + deleted + " frames, counter reset to 0.");
    }

    // Exposed so ControllerClass can serve the frame count via GET /frames/count
    public int getFrameCount() {
        return frameCounter.get();
    }
}
