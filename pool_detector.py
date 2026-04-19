"""
pool_detector.py — Weld Pool Detector

Watches the frames/ folder for new JPEG frames saved by the Java server.
When a new frame appears, it tries to detect the brightest circular blob
(the weld pool, or a flashlight during testing).

The detected position is sent to the Java server via HTTP POST, which
then broadcasts it to the glasses as a named SSE event (pooldetection).

Detection pipeline:
  1. Load frame as grayscale
  2. Gaussian blur        — smooths noise before thresholding
  3. Brightness threshold — isolates the bright blob
  4. Morphological open   — removes small noise specks outside the blob
  5. Morphological close  — fills holes and gaps inside the blob
  6. Find contours        — locate the boundaries of bright regions
  7. Circularity filter   — reject shapes that are not roughly circular
  8. Normalize + POST     — send result to Java as 0.0-1.0 coordinates

All tunable settings are at the top of this file.
For testing, use a flashlight as the bright source — same blob shape as a weld pool.
"""

import os
import time
import glob
import math

import cv2
import numpy as np
import requests

# ── Settings ──────────────────────────────────────────────────────────────────

FRAMES_DIR    = "frames/"                       # folder Java writes frames to
ANNOTATED_DIR = "annotated/"                    # annotated frames saved here for video stitching
JAVA_ENDPOINT = "http://127.0.0.1:9999/pool"   # Java endpoint that receives results

# Threshold — pixels brighter than this value (0-255) are treated as part of the blob.
# 150 works well for a flashlight through a camera — raise toward 200+ for a real welding arc.
# If nothing is being detected, lower this value first.
BRIGHTNESS_THRESHOLD = 200

# Gaussian blur kernel size — must be an odd number (e.g. 5, 7, 9).
# Higher = more smoothing. Helps clean up JPEG compression noise before thresholding.
BLUR_KERNEL = 7

# Morphological kernel size — controls how aggressively erosion/dilation operate.
# Higher = stronger effect.
MORPH_KERNEL = 8

# Minimum blob area in pixels — anything smaller than this is ignored as noise.
MIN_BLOB_AREA = 700

# Circularity threshold — 1.0 is a perfect circle, 0.0 is a straight line.
# The weld pool and flashlight are both close to circular.
# Reject anything below this value to filter out non-circular reflections.
MIN_CIRCULARITY = 0.62

# How often the folder is scanned for new frames (seconds).
# 0.1 = 10 times per second, which is fast enough for smooth tracking.
POLL_INTERVAL = 0.1

# Debug mode — saves the thresholded binary mask to debug/ for every processed frame.
# Open these images to see exactly what the detector sees after thresholding.
# Useful when nothing is being detected and you need to tune the threshold.
# Set to True to enable, False for normal use.
DEBUG_MODE = False

# How often the detector re-fetches settings from the dashboard (seconds).
# This lets you change threshold/blur/etc from the dashboard without restarting Python.
SETTINGS_REFRESH_INTERVAL = 10.0

# ──────────────────────────────────────────────────────────────────────────────


def detect_pool(image_path):
    """
    Loads a JPEG frame and tries to find the weld pool.

    Returns a dict with:
      poolDetected  — True if a valid pool was found, False otherwise
      poolX         — horizontal center, normalized 0.0 (left) to 1.0 (right)
      poolY         — vertical center,   normalized 0.0 (top)  to 1.0 (bottom)
      poolRadius    — radius normalized to image width
    """

    no_detection = {"poolDetected": False, "poolX": 0, "poolY": 0, "poolRadius": 0}
    annotated_path = ANNOTATED_DIR + os.path.basename(image_path)

    # Load both grayscale (for detection) and color (for annotation output)
    frame       = cv2.imread(image_path, cv2.IMREAD_GRAYSCALE)
    color_frame = cv2.imread(image_path, cv2.IMREAD_COLOR)

    if frame is None or color_frame is None:
        # File may still be being written by Java — skip this frame
        return no_detection

    def save_and_return_no_detection():
        # Always write a frame to annotated/ so the ffmpeg sequence is never broken,
        # even when no pool is detected.  Raw frame = no circles drawn.
        cv2.imwrite(annotated_path, color_frame)
        return no_detection

    height, width = frame.shape

    # ── Step 1: Gaussian blur ────────────────────────────────────────────────
    # Smooths the image before thresholding so JPEG compression artifacts
    # and small noise specks do not produce false contours.
    blurred = cv2.GaussianBlur(frame, (BLUR_KERNEL, BLUR_KERNEL), 0)

    # ── Step 2: Brightness threshold ─────────────────────────────────────────
    # Pixels brighter than BRIGHTNESS_THRESHOLD become white, everything else black.
    # This isolates the bright weld pool or flashlight as a white blob.
    _, binary = cv2.threshold(blurred, BRIGHTNESS_THRESHOLD, 255, cv2.THRESH_BINARY)

    # Debug mode: save the binary mask so you can see what the detector is working with.
    # If the mask is all black, the threshold is too high — lower BRIGHTNESS_THRESHOLD.
    # If the mask is mostly white, the threshold is too low — raise it.
    if DEBUG_MODE:
        os.makedirs("debug/", exist_ok=True)
        debug_path = "debug/" + os.path.basename(image_path)
        cv2.imwrite(debug_path, binary)
        print(f"[Detector] Debug mask saved: {debug_path} (white pixels = detected as bright)")

    # ── Step 3: Morphological opening (erode → dilate) ───────────────────────
    # Erosion shrinks all white regions. This removes small isolated noise specks
    # that are not part of the actual blob because they disappear under erosion.
    # Dilation then restores the size of the remaining (real) blob.
    kernel  = np.ones((MORPH_KERNEL, MORPH_KERNEL), np.uint8)
    opened  = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)

    # ── Step 4: Morphological closing (dilate → erode) ───────────────────────
    # Dilation expands the blob to fill small holes and gaps caused by JPEG
    # compression or uneven brightness inside the weld pool.
    # Erosion then restores the outer boundary to its original size.
    closed = cv2.morphologyEx(opened, cv2.MORPH_CLOSE, kernel)

    # ── Step 5: Find contours ────────────────────────────────────────────────
    # Finds the boundaries of all white regions in the cleaned binary image.
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    if not contours:
        return save_and_return_no_detection()

    # ── Step 6: Filter by area and circularity ───────────────────────────────
    # For each contour, compute its area and circularity.
    # Circularity = 4π × area / perimeter² — equals 1.0 for a perfect circle.
    # We keep only contours that are large enough and round enough.
    valid = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if area < MIN_BLOB_AREA:
            continue  # too small — noise or reflection

        perimeter = cv2.arcLength(contour, True)
        if perimeter == 0:
            continue

        circularity = (4 * math.pi * area) / (perimeter * perimeter)
        if circularity < MIN_CIRCULARITY:
            continue  # not round enough — probably not a weld pool

        valid.append((area, contour))

    if not valid:
        return save_and_return_no_detection()

    # ── Step 7: Take the largest valid contour ───────────────────────────────
    # If multiple round blobs pass the filter, the largest one is assumed to
    # be the weld pool. Smaller ones are likely reflections or sparks.
    largest_contour = max(valid, key=lambda x: x[0])[1]

    # ── Step 8: Compute center and radius ────────────────────────────────────
    (cx, cy), radius = cv2.minEnclosingCircle(largest_contour)

    # ── Step 9: Draw the detection circle on the color frame ─────────────────
    # A green circle marks the detected pool boundary.
    # A small red dot marks the exact center point.
    cx_int = int(cx)
    cy_int = int(cy)
    r_int  = int(radius)
    cv2.circle(color_frame, (cx_int, cy_int), r_int, (0, 255, 0),   2)  # green circle
    cv2.circle(color_frame, (cx_int, cy_int), 4,     (0, 0,   255), -1) # red center dot
    cv2.imwrite(annotated_path, color_frame)

    # Normalize to 0.0-1.0 so the result is independent of image resolution
    return {
        "poolDetected": True,
        "poolX":        round(cx / width,  4),
        "poolY":        round(cy / height, 4),
        "poolRadius":   round(radius / width, 4)
    }


def post_result(result):
    """
    Sends the detection result to the Java server via HTTP POST.
    If the server is not running, the error is logged and skipped.
    """
    try:
        requests.post(JAVA_ENDPOINT, json=result, timeout=0.5)
    except requests.exceptions.RequestException as e:
        print(f"[Detector] Could not reach Java server: {e}")


def get_frame_number(filename):
    """
    Extracts the frame number from a filename like frames/frame_00042.jpg.
    Returns the integer 42. Used to track which frames have been processed.
    """
    basename = os.path.basename(filename)      # frame_00042.jpg
    name     = os.path.splitext(basename)[0]   # frame_00042
    return int(name.replace("frame_", ""))     # 42


def fetch_settings():
    """
    Fetches the current detector settings from the Java dashboard API.
    Updates the module-level variables so detect_pool() uses the latest values.
    If the server is not reachable, the current values are kept unchanged.
    """
    global BRIGHTNESS_THRESHOLD, BLUR_KERNEL, MORPH_KERNEL, MIN_BLOB_AREA, MIN_CIRCULARITY

    try:
        response  = requests.get(f"{JAVA_ENDPOINT.replace('/pool', '')}/api/settings", timeout=1.0)
        settings  = response.json()

        BRIGHTNESS_THRESHOLD = settings.get("brightnessThreshold", BRIGHTNESS_THRESHOLD)
        BLUR_KERNEL          = settings.get("blurKernel",          BLUR_KERNEL)
        MORPH_KERNEL         = settings.get("morphKernel",         MORPH_KERNEL)
        MIN_BLOB_AREA        = settings.get("minBlobArea",         MIN_BLOB_AREA)
        MIN_CIRCULARITY      = settings.get("minCircularity",      MIN_CIRCULARITY)

    except Exception as e:
        print(f"[Detector] Could not fetch settings from dashboard: {e} (using current values)")


def main():
    print("[Detector] Started — watching frames/ for new frames")

    # Make sure the annotated frames folder exists
    os.makedirs(ANNOTATED_DIR, exist_ok=True)

    # Fetch settings from the dashboard on startup — override the defaults above
    fetch_settings()

    last_processed      = -1    # frame number of the last processed frame
    last_settings_fetch = time.time()

    while True:
        # Re-fetch settings from dashboard periodically so changes take effect live
        if time.time() - last_settings_fetch >= SETTINGS_REFRESH_INTERVAL:
            fetch_settings()
            last_settings_fetch = time.time()

        # Find all frame files currently in the frames/ folder, sorted by name
        files = sorted(glob.glob(FRAMES_DIR + "frame_?????.jpg"))

        if files:
            # If the lowest frame number is less than last_processed, the Java server
            # cleared the frames folder and started a new session — reset our counter.
            lowest = get_frame_number(files[0])
            if lowest < last_processed:
                print("[Detector] Frame counter reset detected — starting fresh.")
                last_processed = -1

            # Process every new frame since last_processed, not just the latest.
            # Skipping intermediate frames would leave gaps in annotated/ and
            # cause ffmpeg to truncate the video at the first missing frame number.
            new_files = [f for f in files if get_frame_number(f) > last_processed]

            for frame_file in new_files:
                frame_number = get_frame_number(frame_file)
                result = detect_pool(frame_file)

                # Only POST the result for the most recent frame to avoid flooding the server.
                if frame_file == new_files[-1]:
                    post_result(result)

                status = "DETECTED" if result["poolDetected"] else "not found"
                print(f"[Detector] frame_{frame_number:05d}.jpg → {status}  "
                      f"x={result['poolX']}  y={result['poolY']}  r={result['poolRadius']}")

                last_processed = frame_number

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
