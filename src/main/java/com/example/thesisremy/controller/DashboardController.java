package com.example.thesisremy.controller;

import com.example.thesisremy.serviceandcomponents.Broadcast;
import com.example.thesisremy.serviceandcomponents.FrameWebSocketHandler;
import com.example.thesisremy.serviceandcomponents.ServerState;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.view.RedirectView;

import java.io.File;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
    Serves the dashboard web page and all its supporting API endpoints.

    The dashboard is a single HTML page at /dashboard that auto-refreshes its
    status every 2 seconds using JavaScript fetch() calls to the /api/* endpoints below.

    Info about the @... annotations used here can also be found in the ControllerClass

    API overview:
      GET  /dashboard            : redirects to the static HTML page
      GET  /api/status           : current server status as JSON
      POST /api/toggle/polling   : enable / disable DataGetter polling
      POST /api/toggle/streaming : enable / disable SSE broadcast to glasses
      GET  /api/videos           : list of recorded MP4 files in videos/
      GET  /api/settings         : current detector settings (also read by pool_detector.py)
      POST /api/settings         : update detector settings from the dashboard
*/
@RestController
public class DashboardController {

    private final ServerState            serverState;
    private final Broadcast              broadcast;
    private final FrameWebSocketHandler  frameHandler;
    private final RestTemplate           restTemplate = new RestTemplate();

    private static final String PYTHON_AI_URL = ServerState.PYTHON_AI_URL;
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
            "cameraEnabled",     serverState.isCameraEnabled(),
            "connectedClients",  broadcast.getConnectedCount(),
            "framesThisSession", frameHandler.getFrameCount(),
            "websocketActive",   frameHandler.isWebSocketActive(),
            "pythonAiReachable", checkPythonAi(),
            "lastDataPacket",    serverState.getLastDataPacket(),
            "lastPoolResult",    serverState.getLastPoolResult()
        ));
    }

    // Checks whether the Python AI program is reachable: used for the status panel
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

    // Toggles camera capture and immediately tells the glasses to start/stop via SSE
    @PostMapping("/api/toggle/camera")
    public ResponseEntity<Map<String, Boolean>> toggleCamera() {
        serverState.toggleCamera();
        boolean enabled = serverState.isCameraEnabled();
        broadcast.broadcastNamed("cameraControl", "{\"enabled\":" + enabled + "}");
        return ResponseEntity.ok(Map.of("cameraEnabled", enabled));
    }

    /*
        Returns the list of recorded videos in the videos/ folder.
        Each entry contains the filename, human-readable file size, and formatted timestamp.
        The timestamp is extracted from the filename (video_YYYYMMDD_HHmmss.mp4).

        Logic of the method: the file of the video's gets opened and everything that 
        ends with a .mp4 so a video, is added to a new list, a simple list existing of two strings.
    */
    @GetMapping("/api/videos")
    public ResponseEntity<List<Map<String, String>>> videos() {
        File dir = new File(VIDEOS_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4")); //d not used here but needs to be here
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
        Serves a recorded MP4 file inline so the browser can play it directly.
        Filename is validated to prevent path traversal.
    */
    @GetMapping("/api/video/{filename}")
    public ResponseEntity<Resource> streamVideo(@PathVariable String filename) {
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\"))
            return ResponseEntity.badRequest().build();

        File file = new File(VIDEOS_DIR + filename);
        if (!file.exists() || !file.isFile())
            return ResponseEntity.notFound().build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(file));
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
            "videoFramerate",      serverState.getVideoFramerate()
        ));
    }

    /*
        Updates detector settings from the dashboard form.
        pool_detector.py fetches the updated values on its next settings poll cycle.

        Logic of this part, the port receives a json, before the code can reag this we request a body and map the json to <String, Object>,
        then simple if's to check wether the parameter has changed and that then changes it in the serverstate.
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
        if (body.containsKey("videoFramerate"))
            serverState.setVideoFramerate((Integer) body.get("videoFramerate"));
        return ResponseEntity.ok().build();
    }

    /*
        Returns the current Android app configuration as JSON.
        The Android app calls this on launch so settings can be adjusted from the
        dashboard without rebuilding the APK. Gauge ranges and
        display options are all included.

        Same thinking of above
    */
    @GetMapping("/api/app-settings")
    public ResponseEntity<Map<String, Object>> getAppSettings() {
        return ResponseEntity.ok(Map.of(
            "voltageMin",        serverState.getVoltageMin(),
            "voltageMax",        serverState.getVoltageMax(),
            "amperageMin",       serverState.getAmperageMin(),
            "amperageMax",       serverState.getAmperageMax(),
            "gasFlowMin",        serverState.getGasFlowMin(),
            "gasFlowMax",        serverState.getGasFlowMax(),
            "heatbarDuration",   serverState.getHeatbarDuration(),
            "cameraEnabled",     serverState.isCameraEnabled()
        ));
    }

    // Saves Android app settings posted from the dashboard form
    @PostMapping("/api/app-settings")
    public ResponseEntity<Void> updateAppSettings(@RequestBody Map<String, Object> body) {
        if (body.containsKey("voltageMin"))        serverState.setVoltageMin(       ((Number) body.get("voltageMin")).doubleValue());
        if (body.containsKey("voltageMax"))        serverState.setVoltageMax(       ((Number) body.get("voltageMax")).doubleValue());
        if (body.containsKey("amperageMin"))       serverState.setAmperageMin(      ((Number) body.get("amperageMin")).doubleValue());
        if (body.containsKey("amperageMax"))       serverState.setAmperageMax(      ((Number) body.get("amperageMax")).doubleValue());
        if (body.containsKey("gasFlowMin"))        serverState.setGasFlowMin(       ((Number) body.get("gasFlowMin")).doubleValue());
        if (body.containsKey("gasFlowMax"))        serverState.setGasFlowMax(       ((Number) body.get("gasFlowMax")).doubleValue());
        if (body.containsKey("heatbarDuration"))   serverState.setHeatbarDuration(  ((Number) body.get("heatbarDuration")).intValue());
        return ResponseEntity.ok().build();
    }
}
