#!/usr/bin/env python3
"""Rate-distortion sweep for the box encoder: keyframe size against PSNR/SSIM for H.264 and H.265.

The box screen must be still while this runs. A ground-truth screenshot is taken before and after (they
must match), the encoder runs at the display's own size (no scaling) with a fixed quantiser, and the first
frame of each run is compared with the screenshot.

  rd-sweep.py ADB_SERIAL [workdir]
"""
import re
import subprocess
import sys
from pathlib import Path

serial = sys.argv[1]
work = Path(sys.argv[2] if len(sys.argv) > 2 else "rd-work")
work.mkdir(parents=True, exist_ok=True)
QPS = [16, 20, 24, 28, 32, 36, 40, 44]


def adb(*args, **kw):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, **kw)


def shot(path):
    with open(path, "wb") as f:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], stdout=f, check=True)


def compare(a, b):
    r = subprocess.run(["ffmpeg", "-v", "info", "-i", str(a), "-i", str(b), "-lavfi", "[0][1]psnr;[0][1]ssim",
                        "-f", "null", "-"], capture_output=True, text=True).stderr
    psnr = re.findall(r"PSNR.*average:([0-9.inf]+)", r)
    ssim = re.findall(r"SSIM.*All:([0-9.]+)", r)
    return (float(psnr[-1]) if psnr and psnr[-1] != "inf" else 99.0), (float(ssim[-1]) if ssim else 0.0)


shot(work / "truth-before.png")
rows = []
for codec in ("avc", "hevc"):
    for qp in QPS:
        out = adb("shell",
                  f"CLASSPATH=/data/local/tmp/oabl-enc.dex app_process /system/bin com.softiva.oablenc.Main "
                  f"secs=2 repeat=0 mbps=60 w=1898 h=1080 mime={codec} qp={qp} out=/data/local/tmp/rd.bin 2>&1").stdout
        key = re.search(r"KEY size=(\d+)", out)
        name = f"{codec}-{qp}"
        subprocess.run(["adb", "-s", serial, "pull", "/data/local/tmp/rd.bin", str(work / f"{name}.bin")],
                       capture_output=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(work / f"{name}.bin"), "-frames:v", "1",
                        str(work / f"{name}.png")], capture_output=True)
        if not key or not (work / f"{name}.png").exists():
            rows.append((codec, qp, None, None, None))
            continue
        p, s = compare(work / f"{name}.png", work / "truth-before.png")
        rows.append((codec, qp, int(key.group(1)), p, s))
shot(work / "truth-after.png")
drift, _ = compare(work / "truth-after.png", work / "truth-before.png")
print(f"screen drift between before/after screenshots: PSNR {drift:.1f} dB (99 = identical)")
print("codec  qp  keyframe_KB   PSNR_dB   SSIM")
for c, q, k, p, s in rows:
    print(f"{c:5} {q:3}  " + ("failed" if k is None else f"{k/1000:9.1f}   {p:7.2f}   {s:.4f}"))
