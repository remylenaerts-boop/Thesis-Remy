# AR Welding Guidance — Server

**KU Leuven Master's Thesis · Remy Lenaerts · 2025–2026**

This repository contains the central server component of a proof-of-concept augmented reality welding guidance system. A welder wearing Epson Moverio BT-300 AR glasses receives live feedback about the welding process — current, voltage, shielding gas flow, and a porosity risk indicator — without having to look away from the weld. The server is the communication hub that sits between a Python AI program reading the sensor data and the Android glasses displaying it.

---

## What the system does

A Python AI program (separate repository, developed by thesis partner Yam) reads live welding sensor data via NI-DAQmx hardware and exposes it as a JSON endpoint on the local machine. This server polls that endpoint every second, deduplicates the data, and streams it to the glasses over Wi-Fi using Server-Sent Events. At the same time, the glasses stream their camera feed back to the server over WebSocket. A weld pool detector (`pool_detector.py`) processes every incoming frame using OpenCV, draws a detection circle on each one, and the server stitches the annotated frames into an MP4 recording at the end of each session.

```
Python AI (port 8060)
        │  HTTP GET every second
        ▼
  Spring Boot Server (port 9999)  ←──── pool_detector.py (auto-started)
        │                        │              │
        │ SSE /stream            │ WS /frames   │ POST /pool
        ▼                        ▼              │
  Android Glasses          frames/ folder ──────┘
  (live data overlay)            │
                                 ▼
                        annotated/frame_NNNNN.jpg
                                 │
                                 ▼
                        videos/video_TIMESTAMP.mp4
```

- **SSE `/stream`** — glasses subscribe here and receive live welding data as named JSON events. Two event types: `welddata` (sensor readings + porosity) and `cameraControl` (server command to start/stop the camera).
- **WebSocket `/frames`** — glasses stream raw JPEG camera frames here when camera capture is enabled. Frames are saved to `frames/` and processed by the pool detector.
- **Pool detector** — `pool_detector.py` starts automatically with the server. It watches `frames/` for new images, runs a brightness-based blob detection pipeline, and saves an annotated copy of every frame to `annotated/` with a circle drawn around the detected pool. The result is stored on the server for the dashboard but is **not** forwarded to the glasses — pool tracking is for the video recording only.
- **Video recording** — when the glasses disconnect (after a 5-second grace period to handle Wi-Fi hiccups), the server stitches all annotated frames into a timestamped MP4 using ffmpeg.
- **Dashboard** — a web interface at `http://localhost:9999/dashboard` lets the operator control the session, tune detection parameters, and review recorded videos.
- **mDNS** — the server advertises itself as `sse-server.local` on the local network so the glasses always find it by name, regardless of what IP address the router assigned.

---

## Project structure

```
src/main/java/com/example/thesisremy/
├── ThesisRemyApplication.java          — entry point
├── controller/
│   ├── ControllerClass.java            — SSE /stream, POST /pool, GET /frames/count
│   └── DashboardController.java        — dashboard page and all /api/* endpoints
└── serviceandcomponents/
    ├── ServerState.java                — shared runtime state (volatile flags + settings)
    ├── Broadcast.java                  — SSE emitter list, heartbeat, broadcast methods
    ├── DataGetter.java                 — @Scheduled Python AI poller
    ├── FrameWebSocketHandler.java      — WebSocket frame handler, ffmpeg video stitching
    ├── PoolDetectorLauncher.java       — starts pool_detector.py on server startup
    ├── DnsConfig.java                  — JmDNS mDNS registration
    ├── WebSocketConfig.java            — registers /frames endpoint (512 KB buffer)
    ├── StartupLogger.java              — prints endpoint URLs to console on startup
    └── LatencyStats.java               — latency sample recorder + CSV writer (benchmarking)

pool_detector.py                        — OpenCV weld pool detector (Python)
test_pipeline.py                        — standalone test harness for the detection pipeline
test_webcam.py                          — local webcam sanity check for pool detection
requirements.txt                        — Python dependencies
src/main/resources/static/
├── dashboard.html                      — operator dashboard (single-page HTML/CSS/JS)
└── KU-Leuven-logo.png
```

---

## Requirements

### 1. Java 17

The project targets Java 17. If you don't have it:

**Windows / macOS / Linux**
Download Eclipse Temurin from https://adoptium.net — it's free and easy to set up.

Verify after installing:
```
java -version
```

### 2. Python 3

Required for `pool_detector.py`. The server launches it automatically — you don't need to start it yourself.

**Windows**
```
winget install Python.Python.3
```
Make sure to check **"Add Python to PATH"** during installation, or the server won't be able to find it.

**macOS**
```
brew install python
```

**Linux (Debian/Ubuntu)**
```
sudo apt install python3 python3-pip
```

Then install the required Python packages from the project root:
```
pip install -r requirements.txt
```

### 3. ffmpeg

Used to stitch the saved JPEG frames into MP4 videos.

**Windows**
```
winget install ffmpeg
```

**macOS**
```
brew install ffmpeg
```

**Linux (Debian/Ubuntu)**
```
sudo apt install ffmpeg
```

The server locates ffmpeg automatically — no PATH configuration needed on Windows if you used winget.

### 4. Windows Firewall rule (Windows only)

The server uses mDNS (UDP port 5353) so the glasses can find it by name. Windows blocks this port by default and you need to add one inbound rule manually:

1. Press `Windows + R`, type `wf.msc`, press Enter
2. Click **Inbound Rules** → **New Rule...**
3. Select **Port** → Next
4. Select **UDP**, type `5353` → Next
5. Select **Allow the connection** → Next
6. Leave all three profile boxes checked → Next
7. Name it `mDNS - Thesis AR Server` → Finish

This is a one-time step per machine.

### 5. IDE (recommended)

**IntelliJ IDEA Community Edition** is the easiest option — it detects the Maven project automatically when you open the folder.

**VS Code** also works with the Extension Pack for Java and Spring Boot Extension Pack installed.

---

## Getting started

### Step 1 — Clone the repository
```
git clone https://github.com/your-username/Thesis-Remy.git
cd Thesis-Remy
```

### Step 2 — Install Python dependencies
```
pip install -r requirements.txt
```

### Step 3 — Open in your IDE

- **IntelliJ:** File → Open → select the `Thesis-Remy` folder. IntelliJ picks up `pom.xml` and imports everything automatically. Wait for the dependency download to finish.
- **VS Code:** File → Open Folder → select the `Thesis-Remy` folder.

### Step 4 — Run the server

**From IntelliJ:**
Open `ThesisRemyApplication.java` and click the green run button next to `main`.

**From the terminal:**
```
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

On startup you will see:
```
[Launcher] pool_detector.py started
[Detector] Started — watching frames/ for new frames
```
The pool detector is running. You don't need to start it separately.

### Step 5 — Open the dashboard

```
http://localhost:9999/dashboard
```

From here you can start a session, enable camera capture, tune detection parameters, and review recordings.

### Step 6 — Connect the glasses

Make sure the server machine and the glasses are on the same Wi-Fi network. Start the AR app on the glasses — it connects automatically to `sse-server.local:9999/stream` via mDNS. If the connection fails, check the firewall rule from step 4 of the requirements.

---

## Dashboard overview

| Page | What it does |
|---|---|
| **Overview** | Start/stop session, live sensor readings, Python AI and glasses connection status, and a reference list of all server endpoints |
| **Glasses Preview** | Faithful recreation of the Android HUD (gauges + porosity heatbar) — uses live data when a session is active, or a smooth simulation otherwise |
| **Camera & Detection** | Enable/disable camera capture, frame counter, WebSocket status, recorded video list |
| **Camera Settings** | Tune detection parameters live (changes take effect within 10 seconds) |
| **App Settings** | Gauge display ranges and heatbar duration pushed to the Android app |

---

## Configuration

Server port and application name are in [`src/main/resources/application.properties`](src/main/resources/application.properties). The default port is `9999`.

The Python AI endpoint is hardcoded to `http://127.0.0.1:8060/api/v1/live` in `DataGetter.java`. Change this if your AI program runs on a different port.

**Pool detector parameters** (tunable live from the Camera Settings page):

| Parameter | Default | What it does |
|---|---|---|
| `BRIGHTNESS_THRESHOLD` | `200` | Pixels above this value (0–255) are treated as the weld pool. Lower if nothing is detected. |
| `BLUR_KERNEL` | `7` | Gaussian blur kernel size before thresholding. Must be odd. Higher = more smoothing. |
| `MORPH_KERNEL` | `8` | Erosion/dilation kernel size. Higher = more aggressive noise removal and hole filling. |
| `MIN_BLOB_AREA` | `700` | Minimum blob size in pixels. Blobs smaller than this are discarded as noise. |
| `MIN_CIRCULARITY` | `0.62` | How round the blob must be (0.0–1.0, 1.0 = perfect circle). Rejects sparks and irregular reflections. |
| `videoFramerate` | `30` | Frames per second used when stitching the annotated frames into the final MP4. |

Enable `DEBUG_MODE = True` at the top of `pool_detector.py` to save binary threshold masks to `debug/` — useful for tuning the threshold when detection is not working.

---

## Output folders

| Folder | Contents |
|---|---|
| `frames/` | Raw JPEG frames from the current session (cleared after video is stitched) |
| `annotated/` | Frames with the detection circle drawn on them — used for the video output |
| `videos/` | Finished MP4 recordings, named `video_YYYYMMDD_HHmmss.mp4` |

All folders are created automatically when the server starts. They are excluded from Git.

---

## Troubleshooting

**Pool detector does not start**
Check that Python is on your PATH: `python --version`. If that works, make sure the dependencies are installed: `pip install -r requirements.txt`. The server console shows a specific error if Python is not found.

**Nothing is ever detected**
Enable `DEBUG_MODE = True` in `pool_detector.py` and look at the images saved in `debug/`. If they are all black, the threshold is too high — lower `BRIGHTNESS_THRESHOLD` from the Camera Settings page. During testing without a real welder, point a flashlight at the glasses camera to simulate the weld pool.

**Glasses cannot find the server (`sse-server.local` not resolving)**
Add the UDP 5353 inbound firewall rule described above. As a fallback, connect using the server's IP address directly. Find it with `ipconfig` on Windows or `ifconfig` on macOS/Linux and look for the IPv4 address of your Wi-Fi adapter.

**Video is not created after disconnecting**
Check that ffmpeg is installed and reachable: `ffmpeg -version`. If the command is not found, re-run `winget install ffmpeg` and open a new terminal window. If ffmpeg fails for any other reason, the raw frames are kept in `frames/` so nothing is lost — you can stitch manually:
```
ffmpeg -framerate 30 -i annotated/frame_%05d.jpg -c:v libx264 -pix_fmt yuv420p output.mp4
```

**Port 9999 is already in use**
Change `server.port` in `application.properties` to a free port (e.g. `8080`).

**Python AI is not reachable**
If nothing is running on port 8060, `DataGetter` logs a connection error every second. The rest of the server — SSE streaming, camera recording, pool detection — keeps working normally. The error resolves automatically once the AI program starts.
