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

    // Shared constant — single place to update if the Python AI port changes
    public static final String PYTHON_AI_URL = "http://127.0.0.1:8060/api/v1/live";

    // ── Runtime toggles ───────────────────────────────────────────────────────

    // When false, DataGetter stops polling the Python AI entirely
    // Starts as false — enable from the dashboard once everything is connected and configured
    private volatile boolean pollingEnabled   = false;

    // When false, fetched data is not broadcast — polling still happens so we stay in sync
    // Starts as false — enable from the dashboard once everything is connected and configured
    private volatile boolean streamingEnabled = false;

    // ── Detector settings ─────────────────────────────────────────────────────
    // These are read by pool_detector.py via GET /api/settings.
    // Changing them from the dashboard takes effect on the next detector poll cycle.

    private volatile int    brightnessThreshold = 200;  // 0-255, pixels brighter than this = blob
    private volatile int    blurKernel          = 7;    // must be odd: 3, 5, 7, 9 ...
    private volatile int    morphKernel         = 8;    // erosion/dilation kernel size
    private volatile int    minBlobArea         = 700;  // minimum blob area in pixels
    private volatile double minCircularity      = 0.62; // 0.0-1.0, 1.0 = perfect circle
    private volatile int    videoFramerate      = 30;   // fps used when stitching MP4

    // ── Camera capture (proof-of-concept feature — off by default) ───────────
    // When false: frames from the glasses are discarded, pool detector is idle,
    // and the glasses are told to stop their camera via a "cameraControl" SSE event.
    private volatile boolean cameraEnabled = false;

    // ── Android app settings (fetched by the app on launch via GET /api/app-settings) ──

    // Gauge display ranges — set these to the realistic operating ranges for the welder
    private volatile double voltageMin    =  10.0;   // V
    private volatile double voltageMax    =  40.0;   // V
    private volatile double amperageMin   =   0.0;   // A
    private volatile double amperageMax   = 400.0;   // A
    private volatile double gasFlowMin    =   0.0;   // l/min
    private volatile double gasFlowMax    =  20.0;   // l/min

    // Heatbar display
    private volatile int heatbarDuration = 30;  // seconds of porosity history shown on the glasses

    // ── Last known data packets (shown on overview dashboard) ────────────────
    private volatile String lastDataPacket = "";  // last JSON received from Python AI

    // ── Last known pool detection result (shown on dashboard) ─────────────────
    private volatile String lastPoolResult = "—";

    // ── Toggles ───────────────────────────────────────────────────────────────

    public boolean isPollingEnabled()   { return pollingEnabled; }
    public boolean isStreamingEnabled() { return streamingEnabled; }

    public void togglePolling()   { pollingEnabled   = !pollingEnabled; }
    public void toggleStreaming() { streamingEnabled = !streamingEnabled; }

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

    // ── App settings getters / setters ────────────────────────────────────

    public double  getVoltageMin()           { return voltageMin; }
    public void    setVoltageMin(double v)   { this.voltageMin = v; }

    public double  getVoltageMax()           { return voltageMax; }
    public void    setVoltageMax(double v)   { this.voltageMax = v; }

    public double  getAmperageMin()          { return amperageMin; }
    public void    setAmperageMin(double v)  { this.amperageMin = v; }

    public double  getAmperageMax()          { return amperageMax; }
    public void    setAmperageMax(double v)  { this.amperageMax = v; }

    public double  getGasFlowMin()           { return gasFlowMin; }
    public void    setGasFlowMin(double v)   { this.gasFlowMin = v; }

    public double  getGasFlowMax()           { return gasFlowMax; }
    public void    setGasFlowMax(double v)   { this.gasFlowMax = v; }

    public int  getHeatbarDuration()       { return heatbarDuration; }
    public void setHeatbarDuration(int v)  { this.heatbarDuration = v; }

    // ── Camera toggle ─────────────────────────────────────────────────────
    public boolean isCameraEnabled()           { return cameraEnabled; }
    public void    toggleCamera()              { cameraEnabled = !cameraEnabled; }
    public void    setCameraEnabled(boolean v) { this.cameraEnabled = v; }

    // ── Last AI data packet ───────────────────────────────────────────────
    public String getLastDataPacket()        { return lastDataPacket; }
    public void   setLastDataPacket(String v){ this.lastDataPacket = v; }
}
