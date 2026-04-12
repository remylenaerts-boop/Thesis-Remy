package com.example.thesisremy.controller;

import com.example.thesisremy.serviceandcomponents.Broadcast;
import com.example.thesisremy.serviceandcomponents.FrameWebSocketHandler;
import com.example.thesisremy.serviceandcomponents.ServerState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.view.RedirectView;

import java.io.File;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
    Serves the dashboard web page and all its supporting API endpoints.

    The dashboard is a single HTML page at /dashboard that auto-refreshes its
    status every 2 seconds using JavaScript fetch() calls to the /api/* endpoints below.

    API overview:
      GET  /dashboard             — redirects to the static HTML page
      GET  /api/status            — current server status as JSON
      POST /api/toggle/polling    — enable / disable DataGetter polling
      POST /api/toggle/streaming  — enable / disable SSE broadcast to glasses
      POST /api/toggle/tracking   — enable / disable weld pool detection forwarding
      GET  /api/videos            — list of recorded MP4 files in videos/
      GET  /api/settings          — current detector settings (also read by pool_detector.py)
      POST /api/settings          — update detector settings from the dashboard
*/
@RestController
public class DashboardController {

    private final ServerState            serverState;
    private final Broadcast              broadcast;
    private final FrameWebSocketHandler  frameHandler;
    private final RestTemplate           restTemplate = new RestTemplate();

    private static final String PYTHON_AI_URL = "http://127.0.0.1:8060/api/v1/live";
    private static final String VIDEOS_DIR    = "videos/";

    public DashboardController(ServerState serverState,
                               Broadcast broadcast,
                               FrameWebSocketHandler frameHandler) {
        this.serverState  = serverState;
        this.broadcast    = broadcast;
        this.frameHandler = frameHandler;
    }

    // Redirect /dashboard to the static HTML page served by Spring Boot
    @GetMapping("/dashboard")
    public RedirectView dashboard() {
        return new RedirectView("/dashboard.html");
    }

    /*
        Returns the current state of the entire server as one JSON object.
        The dashboard JavaScript calls this every 2 seconds to update the UI.
    */
    @GetMapping("/api/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "pollingEnabled",    serverState.isPollingEnabled(),
            "streamingEnabled",  serverState.isStreamingEnabled(),
            "trackingEnabled",   serverState.isTrackingEnabled(),
            "connectedClients",  broadcast.getConnectedCount(),
            "framesThisSession", frameHandler.getFrameCount(),
            "websocketActive",   frameHandler.isWebSocketActive(),
            "pythonAiReachable", checkPythonAi(),
            "lastPoolResult",    serverState.getLastPoolResult()
        ));
    }

    // Checks whether the Python AI program is reachable — used for the status panel
    private boolean checkPythonAi() {
        try {
            restTemplate.getForObject(PYTHON_AI_URL, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Toggle endpoints ──────────────────────────────────────────────────────

    @PostMapping("/api/toggle/polling")
    public ResponseEntity<Map<String, Boolean>> togglePolling() {
        serverState.togglePolling();
        return ResponseEntity.ok(Map.of("pollingEnabled", serverState.isPollingEnabled()));
    }

    @PostMapping("/api/toggle/streaming")
    public ResponseEntity<Map<String, Boolean>> toggleStreaming() {
        serverState.toggleStreaming();
        return ResponseEntity.ok(Map.of("streamingEnabled", serverState.isStreamingEnabled()));
    }

    @PostMapping("/api/toggle/tracking")
    public ResponseEntity<Map<String, Boolean>> toggleTracking() {
        serverState.toggleTracking();
        return ResponseEntity.ok(Map.of("trackingEnabled", serverState.isTrackingEnabled()));
    }

    /*
        Returns the list of recorded videos in the videos/ folder.
        Each entry contains the filename, human-readable file size, and formatted timestamp.
        The timestamp is extracted from the filename (video_YYYYMMDD_HHmmss.mp4).
    */
    @GetMapping("/api/videos")
    public ResponseEntity<List<Map<String, String>>> videos() {
        File dir = new File(VIDEOS_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        List<Map<String, String>> result = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                String timestamp = extractTimestamp(f.getName());
                String size      = formatSize(f.length());
                result.add(Map.of(
                    "name",      f.getName(),
                    "size",      size,
                    "timestamp", timestamp
                ));
            }
            result.sort((a, b) -> b.get("name").compareTo(a.get("name"))); // newest first
        }

        return ResponseEntity.ok(result);
    }

    // Parses the timestamp out of a filename like video_20260412_143022.mp4
    private String extractTimestamp(String filename) {
        try {
            String raw = filename.replace("video_", "").replace(".mp4", ""); // 20260412_143022
            DateTimeFormatter parser    = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
            return LocalDateTime.parse(raw, parser).format(formatter);
        } catch (Exception e) {
            return filename;
        }
    }

    // Converts a byte count to a human-readable string (KB or MB)
    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return new DecimalFormat("0.0").format(bytes / 1024.0) + " KB";
        return new DecimalFormat("0.0").format(bytes / (1024.0 * 1024.0)) + " MB";
    }

    /*
        Returns the current detector settings as JSON.
        This endpoint is also called by pool_detector.py on startup so that
        settings changed in the dashboard are picked up without restarting Python.
    */
    @GetMapping("/api/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(Map.of(
            "brightnessThreshold", serverState.getBrightnessThreshold(),
            "blurKernel",          serverState.getBlurKernel(),
            "morphKernel",         serverState.getMorphKernel(),
            "minBlobArea",         serverState.getMinBlobArea(),
            "minCircularity",      serverState.getMinCircularity(),
            "videoFramerate",      serverState.getVideoFramerate()
        ));
    }

    /*
        Updates detector settings from the dashboard form.
        pool_detector.py fetches the updated values on its next settings poll cycle.
    */
    @PostMapping("/api/settings")
    public ResponseEntity<Void> updateSettings(@RequestBody Map<String, Object> body) {
        if (body.containsKey("brightnessThreshold"))
            serverState.setBrightnessThreshold((Integer) body.get("brightnessThreshold"));
        if (body.containsKey("blurKernel"))
            serverState.setBlurKernel((Integer) body.get("blurKernel"));
        if (body.containsKey("morphKernel"))
            serverState.setMorphKernel((Integer) body.get("morphKernel"));
        if (body.containsKey("minBlobArea"))
            serverState.setMinBlobArea((Integer) body.get("minBlobArea"));
        if (body.containsKey("minCircularity"))
            serverState.setMinCircularity(((Number) body.get("minCircularity")).doubleValue());
        if (body.containsKey("videoFramerate"))
            serverState.setVideoFramerate((Integer) body.get("videoFramerate"));
        return ResponseEntity.ok().build();
    }
}
