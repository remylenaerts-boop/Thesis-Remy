# Thesis-Remy — AR Welding Guidance Server

A Spring Boot server that acts as the communication hub for an augmented reality welding guidance system.
It receives live welding process data from a local AI program and streams it to Android smart glasses over Wi-Fi.
It also receives the glasses' camera feed via WebSocket and stitches each recording session into a video file.

---

## How it works

```
Python AI (port 8060)
        │  polls every second
        ▼
  Spring Boot Server (port 9999)
        │                        │
        │ SSE /stream            │ WebSocket /frames
        ▼                        ▼
  Android Glasses          Android Glasses
  (receives JSON)          (sends camera frames)
        │
        ▼
  videos/video_TIMESTAMP.mp4   ← stitched automatically on disconnect
```

- **SSE `/stream`** — Android glasses connect here and receive live JSON guidance data.
- **WebSocket `/frames`** — Android glasses stream raw JPEG camera frames here; frames are saved to `frames/` and automatically stitched into an MP4 in `videos/` when the glasses disconnect.
- **GET `/frames/count`** — returns how many frames have been saved in the current session.
- **mDNS** — the server advertises itself as `sse-server.local` so the glasses can find it by name instead of by IP address.

---

## Requirements

### 1. Java Development Kit (JDK) 17

The project targets Java 17. Download the JDK from:
- **Windows/macOS/Linux:** https://adoptium.net (Eclipse Temurin — recommended, free)

After installing, verify in a terminal:
```
java -version
```
You should see `openjdk 17` (or higher).

### 2. ffmpeg

Used to stitch JPEG frames into MP4 videos. Install it for your OS:

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

After installing, you do **not** need to configure anything — the server finds ffmpeg automatically.

### 3. An IDE (recommended)

**IntelliJ IDEA Community Edition** (free) is the recommended IDE for this project.
Download: https://www.jetbrains.com/idea/download

Required plugins (install from *Settings → Plugins*):
- **Java** — built into IntelliJ, no action needed
- **Spring Boot** — search "Spring" in the plugin marketplace, install *Spring Boot*

**VS Code** also works. Install these extensions:
- Extension Pack for Java (by Microsoft)
- Spring Boot Extension Pack (by VMware)

### 4. Maven

Maven is the build tool used to compile and run the project. You do **not** need to install it separately — the project includes a Maven wrapper (`mvnw`) that downloads the correct version automatically.

### 5. Windows Firewall rule (Windows only)

The server advertises itself on the local Wi-Fi network so that the Android glasses can find it by name (`sse-server.local`) without needing to know the server's IP address. It does this using a technology called **mDNS** (multicast DNS) — the same mechanism your phone uses to find a Chromecast or wireless printer on your home network.

mDNS works by sending a small broadcast message over the network on **UDP port 5353**. The glasses send a message that essentially says *"is there a device called sse-server.local on this network?"*. The server is listening for exactly these questions and replies *"yes, that's me, here is my IP address"*. After that exchange the glasses know where to connect.

On a fresh Windows installation the built-in firewall blocks all incoming network traffic by default — including these mDNS broadcast messages. The glasses ask the question, but Windows silently drops it before the server ever hears it. The result is that `sse-server.local` never resolves to an IP address and the connection never happens.

**To fix this, add one inbound firewall rule:**

1. Press `Windows + R`, type `wf.msc`, press Enter — this opens *Windows Defender Firewall with Advanced Security*
2. Click **Inbound Rules** in the left panel
3. Click **New Rule...** in the right panel
4. Select **Port** → click Next
5. Select **UDP**, enter `5353` in the specific local ports field → click Next
6. Select **Allow the connection** → click Next
7. Leave all three boxes checked (Domain, Private, Public) → click Next
8. Give it a name such as `mDNS - Thesis AR Server` → click Finish

This only needs to be done once per machine. After adding the rule the glasses will be able to resolve `sse-server.local` automatically as long as both devices are on the same Wi-Fi network.

---

## Getting started

### Step 1 — Clone the repository
```
git clone https://github.com/your-username/Thesis-Remy.git
cd Thesis-Remy
```

### Step 2 — Open in your IDE

- **IntelliJ:** File → Open → select the `Thesis-Remy` folder. IntelliJ detects the `pom.xml` automatically and imports the project.
- **VS Code:** File → Open Folder → select the `Thesis-Remy` folder.

Wait for the IDE to finish downloading dependencies (progress bar at the bottom).

### Step 3 — Run the server

**From IntelliJ:**
Open `ThesisRemyApplication.java` and click the green run button next to the `main` method.

**From the terminal:**
```
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

### Step 4 — Verify it is running

Open a browser and go to:
```
http://localhost:9999/frames/count
```
You should see: `{ "framesSaved": 0 }`

The server is also reachable from other devices on the same Wi-Fi network via:
```
http://sse-server.local:9999/stream       ← SSE endpoint (Android glasses)
ws://sse-server.local:9999/frames         ← WebSocket endpoint (camera frames)
```

---

## Configuration

All configuration is in [`src/main/resources/application.properties`](src/main/resources/application.properties):

| Property | Default | Description |
|---|---|---|
| `server.port` | `9999` | Port the server listens on |
| `spring.application.name` | `Thesis-Remy` | Application name |

The external AI data source is hardcoded to `http://127.0.0.1:8060/api/v1/live` in `DataGetter.java`. Change this if your AI program runs on a different port.

---

## Output files

| Folder | Contents |
|---|---|
| `frames/` | Temporary JPEG frames from the current session — deleted automatically after stitching |
| `videos/` | Finished MP4 recordings, one per session, named `video_YYYYMMDD_HHmmss.mp4` |

Both folders are created automatically in the project root when the server starts.

---

## Troubleshooting

**The glasses cannot find the server by name (`sse-server.local`)**
Make sure you have added the UDP 5353 inbound firewall rule described in requirement 5 above. As a fallback, connect using the server's IP address directly — find it by running `ipconfig` in a terminal on Windows or `ifconfig` on macOS/Linux, and look for the IPv4 address on your Wi-Fi adapter.

**Video stitching fails / no MP4 is created**
Make sure ffmpeg is installed (`winget install ffmpeg` on Windows). If ffmpeg is missing, the frames are kept in `frames/` so no data is lost — you can stitch manually:
```
ffmpeg -framerate 30 -i frames/frame_%05d.jpg -c:v libx264 -pix_fmt yuv420p output.mp4
```

**Port 9999 is already in use**
Change `server.port` in `application.properties` to any free port (e.g. `8080`).

**The AI data source is not available**
If nothing is running on port 8060, `DataGetter` will log an error every second. The rest of the server (SSE streaming, WebSocket frame receiving) continues to work normally.
