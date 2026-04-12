package com.example.thesisremy.serviceandcomponents;

import org.springframework.stereotype.Component;

/*
    Central store for all runtime flags and configurable settings.

    Everything here can be changed from the dashboard at http://localhost:9999/dashboard
    without restarting the server.

    volatile ensures that changes made by the dashboard HTTP thread are
    immediately visible to the scheduler thread running DataGetter,
    and to any other thread that reads these values.
*/
@Component
public class ServerState {

    // ── Runtime toggles ───────────────────────────────────────────────────────

    // When false, DataGetter stops polling the Python AI entirely
    // Starts as false — enable from the dashboard once everything is connected and configured
    private volatile boolean pollingEnabled   = false;

    // When false, fetched data is not broadcast — polling still happens so we stay in sync
    // Starts as false — enable from the dashboard once everything is connected and configured
    private volatile boolean streamingEnabled = false;

    // When false, pool detection results from POST /pool are ignored and not forwarded
    // Starts as false — enable from the dashboard once everything is connected and configured
    private volatile boolean trackingEnabled  = false;

    // ── Detector settings ─────────────────────────────────────────────────────
    // These are read by pool_detector.py via GET /api/settings.
    // Changing them from the dashboard takes effect on the next detector poll cycle.

    private volatile int    brightnessThreshold = 200;  // 0-255, pixels brighter than this = blob
    private volatile int    blurKernel          = 7;    // must be odd: 3, 5, 7, 9 ...
    private volatile int    morphKernel         = 5;    // erosion/dilation kernel size
    private volatile int    minBlobArea         = 100;  // minimum blob area in pixels
    private volatile double minCircularity      = 0.5;  // 0.0-1.0, 1.0 = perfect circle
    private volatile int    videoFramerate      = 30;   // fps used when stitching MP4

    // ── Last known pool detection result (shown on dashboard) ─────────────────
    private volatile String lastPoolResult = "—";

    // ── Toggles ───────────────────────────────────────────────────────────────

    public boolean isPollingEnabled()   { return pollingEnabled; }
    public boolean isStreamingEnabled() { return streamingEnabled; }
    public boolean isTrackingEnabled()  { return trackingEnabled; }

    public void togglePolling()   { pollingEnabled   = !pollingEnabled; }
    public void toggleStreaming() { streamingEnabled = !streamingEnabled; }
    public void toggleTracking()  { trackingEnabled  = !trackingEnabled; }

    // ── Detector settings getters / setters ───────────────────────────────────

    public int    getBrightnessThreshold() { return brightnessThreshold; }
    public int    getBlurKernel()          { return blurKernel; }
    public int    getMorphKernel()         { return morphKernel; }
    public int    getMinBlobArea()         { return minBlobArea; }
    public double getMinCircularity()      { return minCircularity; }
    public int    getVideoFramerate()      { return videoFramerate; }

    public void setBrightnessThreshold(int v)  { this.brightnessThreshold = v; }
    public void setBlurKernel(int v)           { this.blurKernel = v; }
    public void setMorphKernel(int v)          { this.morphKernel = v; }
    public void setMinBlobArea(int v)          { this.minBlobArea = v; }
    public void setMinCircularity(double v)    { this.minCircularity = v; }
    public void setVideoFramerate(int v)       { this.videoFramerate = v; }

    public String getLastPoolResult()        { return lastPoolResult; }
    public void   setLastPoolResult(String v){ this.lastPoolResult = v; }
}
