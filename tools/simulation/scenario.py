#!/usr/bin/env python3
"""Select an existing OABL Link simulated-car phase; never operates on a physical device."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PHASES = {"charging": 300, "unplugged": 660, "driving": 1020, "parked": 1380, "idle": 720}
ROOT = Path(__file__).resolve().parents[2]


class Simulator:
    def __init__(self, serial="emulator-5554"):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Only emulator serials are accepted")
        self.adb = [os.environ.get("OABL_ADB", str(Path.home() / "Library/Android/sdk/platform-tools/adb")), "-s", serial]
        if self.run("shell", "getprop", "ro.hardware").strip() not in ("ranchu", "goldfish"):
            raise ValueError("Refusing non-emulator hardware")

    def run(self, *args, **kwargs):
        return subprocess.check_output(self.adb + list(args), text=True, **kwargs)

    def phase(self, name):
        command = shlex.join(["run-as", "com.koushikdutta.vysor", "sh", "-c",
            "cat > files/simulation-phase-seconds.next && mv files/simulation-phase-seconds.next files/simulation-phase-seconds"])
        subprocess.run(self.adb + ["shell", command], input=str(PHASES[name])+(" hold" if name=="idle" else ""), text=True, check=True)
        time.sleep(2)

    def read(self, target="/v1/now"):
        prefs = ET.fromstring(self.run("exec-out", "run-as", "com.koushikdutta.vysor", "cat", "shared_prefs/oabllink.xml"))
        code = next(n.text for n in prefs if n.get("name") == "pair_code")
        key = hmac.new(code.encode(), b"oabl-link/v1/pair", hashlib.sha256).digest()
        stamp = int(time.time() * 1000)
        nonce = str(uuid.uuid4())
        canonical = f"GET\n{target}\n{stamp}\n{nonce}\n{hashlib.sha256(b'').hexdigest()}"
        signature = hmac.new(key, canonical.encode(), hashlib.sha256).hexdigest()
        request = (f"GET {target} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                   f"X-OABL-Time: {stamp}\r\nX-OABL-Nonce: {nonce}\r\nX-OABL-Sign: {signature}\r\n\r\n")
        raw = subprocess.check_output(self.adb + ["shell", "toybox", "nc", "-6", "-w", "4", "-W", "4", "::1", "8731"], input=request.encode(), timeout=8)
        header, body = raw.split(b"\r\n\r\n", 1)
        if header.splitlines()[0].split()[1] != b"200":
            raise RuntimeError(raw.decode())
        return json.loads(body)

    def tree(self):
        for attempt in range(3):
            try:
                self.run("shell", "uiautomator", "dump", "/sdcard/oabl-simulation-ui.xml", timeout=20)
                return ET.fromstring(self.run("shell", "cat", "/sdcard/oabl-simulation-ui.xml"))
            except (subprocess.SubprocessError, ET.ParseError):
                if attempt == 2:
                    raise
                time.sleep(.5)

    def tap(self, label=None, prefix=None, resource=None):
        nodes = [n for n in self.tree().iter("node") if
                 (label is not None and label in (n.get("text"), n.get("content-desc"))) or
                 (prefix is not None and n.get("text", "").startswith(prefix)) or
                 (resource is not None and n.get("resource-id") == resource)]
        if not nodes:
            raise RuntimeError(f"Missing UI item: {label or prefix or resource}")
        node = next((n for n in nodes if n.get("clickable") == "true"), nodes[0])
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(.35)

    def texts(self):
        return "\n".join(n.get("text") for n in self.tree().iter("node") if n.get("text"))

    def capture(self, output):
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.with_suffix(".png").write_bytes(subprocess.check_output(self.adb + ["exec-out", "screencap", "-p"]))
        output.with_suffix(".txt").write_text(self.texts())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=PHASES, nargs="?")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    simulator = Simulator(args.serial)
    if args.phase:
        simulator.phase(args.phase)
    data = {"hello": simulator.read("/v1/hello"), "now": simulator.read()}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.with_suffix(".json").write_text(json.dumps(data, indent=2) + "\n")
        simulator.capture(args.output)
    print(json.dumps(data, indent=2))
