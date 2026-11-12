#!/usr/bin/env python3
"""Plays a motion test video on the box and reports how many frames the encoder and the head unit get.

  motion-trial.py BOX_SERIAL HEADUNIT_SERIAL "encoder args" [seconds]

Sets persist.oabl.enc.args, restarts sd-reverse, waits for the head unit to reconnect, starts
/sdcard/Movies/oabl-motion.mp4 in Gallery and prints the last three encoder windows (OABLENC logcat, 5 s
each) and the head unit's decoder throughput lines. The video is a 60 fps test pattern with a frame counter
(ffmpeg testsrc2), so a 5 s window should hold about 300 frames if nothing is lost.
"""
import os
import re
import subprocess
import sys
import time

box, hu, args = sys.argv[1], sys.argv[2], sys.argv[3]
VIDEO = os.environ.get("VIDEO", "oabl-motion.mp4")
secs = int(sys.argv[4]) if len(sys.argv) > 4 else 25


def adb(serial, *a):
    return subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True).stdout


adb(box, "shell", "logcat", "-c")
adb(hu, "logcat", "-c")
adb(box, "shell", f"setprop persist.oabl.enc.args '{args}'; setprop ctl.restart sd-reverse")
t0 = time.time()
ok = False
while time.time() - t0 < 60:
    time.sleep(3)
    log = adb(hu, "shell", "logcat -d --pid=$(pidof com.koushikdutta.vysor)")
    if "session state projecting" in log and "keyframe reached" in log:
        ok = True
        break
if not ok:
    print("head unit did not reconnect")
    sys.exit(1)
time.sleep(2)
adb(box, "shell", "am force-stop com.android.gallery3d")
adb(box, "shell", f"am start -a android.intent.action.VIEW -d file:///sdcard/Movies/{VIDEO} "
                  "-t video/mp4 -n com.android.gallery3d/.app.MovieActivity")
time.sleep(secs)
enc = adb(box, "shell", "logcat -d -s OABLENC")
sums = [l for l in enc.splitlines() if " sum " in l][-3:]
codec = [l for l in enc.splitlines() if "codec " in l][-1:]
print("args:", args)
print(" ", re.sub(r"^.*OABLENC : ", "", codec[0]) if codec else "no codec line")
for l in sums:
    print("  enc", re.sub(r"^.*OABLENC : ", "", l))
tp = [l for l in adb(hu, "shell", "logcat -d --pid=$(pidof com.koushikdutta.vysor)").splitlines() if "Throughput over" in l][-3:]
for l in tp:
    m = re.search(r"rendered=(\d+) \((\d+)fps\), fed=(\d+).*dropped=(\d+), skipped=(\d+), concealed=(\d+)", l)
    if m:
        print(f"  tablet rendered={m.group(1)} ({m.group(2)} fps) fed={m.group(3)} dropped={m.group(4)} skipped={m.group(5)} concealed={m.group(6)}")
adb(box, "shell", "am force-stop com.android.gallery3d")
