"""
test_pipeline.py — End-to-end test for the weld pool detector

Generates synthetic frames with a bright circular blob moving across a dark
background (simulates a phone flashlight / weld pool), runs detect_pool() on
each one, then stitches the annotated frames into a test video with ffmpeg.

Run with:  python test_pipeline.py
Output:    annotated/frame_000XX.jpg  — frames with green circle + red dot
           videos/test_output.mp4     — stitched video
"""

import os
import math
import glob
import subprocess

import cv2
import numpy as np

# Import the detector — this also imports its tunable globals
import pool_detector as detector

# ── Test settings ─────────────────────────────────────────────────────────────

WIDTH      = 640
HEIGHT     = 480
N_FRAMES   = 60       # 2 seconds at 30 fps
BLOB_R     = 35       # radius of the simulated flashlight blob in pixels
FRAMERATE  = 30

# ──────────────────────────────────────────────────────────────────────────────


def generate_test_frames():
    """
    Creates N_FRAMES synthetic JPEG frames in frames/.
    The bright blob moves in a gentle arc so we get variation across frames.
    Background is dark gray (~30) to simulate a dim workshop.
    """
    os.makedirs("frames/",    exist_ok=True)
    os.makedirs("annotated/", exist_ok=True)
    os.makedirs("videos/",    exist_ok=True)

    # Clear any leftover frames from a previous run
    for f in glob.glob("frames/frame_?????.jpg"):
        os.remove(f)
    for f in glob.glob("annotated/frame_?????.jpg"):
        os.remove(f)

    print(f"[Test] Generating {N_FRAMES} frames ({WIDTH}x{HEIGHT}) ...")

    for i in range(N_FRAMES):
        t = i / (N_FRAMES - 1)  # 0.0 → 1.0

        # Blob center: moves left→right with a slight vertical sine wave
        cx = int(WIDTH  * 0.1 + WIDTH  * 0.8 * t)
        cy = int(HEIGHT * 0.5 + HEIGHT * 0.15 * math.sin(t * 2 * math.pi))

        # Dark background
        img = np.full((HEIGHT, WIDTH, 3), 30, dtype=np.uint8)

        # Bright blob: filled circle at value 245 — well above any threshold
        cv2.circle(img, (cx, cy), BLOB_R, (245, 245, 245), -1)

        # Soft glow around it so it looks like a real light source
        cv2.circle(img, (cx, cy), BLOB_R + 10, (120, 120, 120), 6)

        path = f"frames/frame_{i:05d}.jpg"
        cv2.imwrite(path, img, [cv2.IMWRITE_JPEG_QUALITY, 95])

    print(f"[Test] Done — frames saved to frames/")


def run_detection():
    """
    Runs detect_pool() on every generated frame and prints a summary.
    Returns (detected_count, total_count).
    """
    frames = sorted(glob.glob("frames/frame_?????.jpg"))
    detected = 0

    print(f"\n[Test] Running detection on {len(frames)} frames ...")
    print(f"[Test] Settings: threshold={detector.BRIGHTNESS_THRESHOLD}  "
          f"blur={detector.BLUR_KERNEL}  morph={detector.MORPH_KERNEL}  "
          f"min_area={detector.MIN_BLOB_AREA}  min_circ={detector.MIN_CIRCULARITY}")

    for path in frames:
        result = detector.detect_pool(path)
        if result["poolDetected"]:
            detected += 1
            print(f"  DETECTED  {os.path.basename(path)}  "
                  f"x={result['poolX']:.3f}  y={result['poolY']:.3f}  r={result['poolRadius']:.3f}")
        else:
            print(f"  not found {os.path.basename(path)}")

    print(f"\n[Test] Detection rate: {detected}/{len(frames)} frames")
    return detected, len(frames)


def stitch_video():
    """
    Calls ffmpeg to stitch annotated/ frames into videos/test_output.mp4.
    Returns True on success.
    """
    out = "videos/test_output.mp4"

    # Remove old test video if it exists
    if os.path.exists(out):
        os.remove(out)

    # Resolve ffmpeg the same way the Java server does
    ffmpeg = "ffmpeg"
    winget = os.path.expanduser("~/AppData/Local/Microsoft/WinGet/Links/ffmpeg.exe")
    if os.path.exists(winget):
        ffmpeg = winget

    cmd = [
        ffmpeg, "-y",
        "-framerate", str(FRAMERATE),
        "-i", "annotated/frame_%05d.jpg",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        out
    ]

    print(f"\n[Test] Stitching video: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print("[Test] ffmpeg FAILED:")
        print(result.stderr[-2000:])   # last 2000 chars of ffmpeg output
        return False

    size_kb = os.path.getsize(out) // 1024
    print(f"[Test] Video saved: {out}  ({size_kb} KB)")
    return True


def main():
    generate_test_frames()
    detected, total = run_detection()

    if detected == 0:
        print("\n[Test] Nothing detected at all — lower BRIGHTNESS_THRESHOLD in pool_detector.py")
        return
    if detected < total * 0.8:
        print(f"\n[Test] Warning: only {detected}/{total} frames detected — "
              "consider lowering BRIGHTNESS_THRESHOLD or MIN_CIRCULARITY")

    annotated = sorted(glob.glob("annotated/frame_?????.jpg"))
    if len(annotated) != total:
        print(f"\n[Test] WARNING: annotated/ has {len(annotated)} frames but {total} were generated — "
              "missing frames will break the ffmpeg sequence")
        return

    success = stitch_video()
    if success:
        print("\n[Test] All done. Open videos/test_output.mp4 to verify the green circles.")


if __name__ == "__main__":
    main()
