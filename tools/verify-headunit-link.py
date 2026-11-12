#!/usr/bin/env python3
"""Check signed Link reads, history and live streaming on an already-running head unit.

Reads the pairing code from Link's notification without printing it. Does not install an app,
change settings, restart a service or write vehicle properties. Removes its ADB port forwarding.
"""
import argparse
import hashlib
import hmac
import http.client
import json
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(["adb", "-s", args.serial, *command], text=True, timeout=30)

    notice = adb("shell", "dumpsys", "notification", "--noredact")
    code = re.search(r"Pair code (\d{6})", notice)
    if not code:
        parser.error("Start OABL Link first; its pairing notification was not found")
    key = hmac.digest(code.group(1).encode(), b"oabl-link/v1/pair", "sha256")
    # Allocate an available host port rather than replacing another ADB forwarding rule.
    port = int(adb("forward", "tcp:0", "tcp:8731").strip())
    checks = {}

    def headers(target):
        now = int(adb("shell", "date", "+%s")) * 1000
        nonce = secrets.token_hex(16)
        canonical = f"GET\n{target}\n{now}\n{nonce}\n{hashlib.sha256(b'').hexdigest()}"
        return {"X-OABL-Time": str(now), "X-OABL-Nonce": nonce,
                "X-OABL-Sign": hmac.new(key, canonical.encode(), hashlib.sha256).hexdigest()}

    def get(target, signed=True):
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        connection.request("GET", target, headers=headers(target) if signed else {})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    try:
        status, hello = get("/v1/hello")
        assert status == 200, hello
        assert hello["probe"] == "dongfeng-dfcar", hello
        checks["real_dongfeng_probe"] = True
        checks["app_version"] = hello["app_version"]
        checks["advertised_signals"] = len(hello["signals"])
        assert get("/v1/now", signed=False)[0] == 401
        checks["unsigned_requests_rejected"] = True
        status, now = get("/v1/now")
        assert status == 200 and "soc" in now["values"], now
        checks["live_signal_count"] = len(now["values"])
        checks["live_values"] = {k: v["v"] for k, v in now["values"].items()
                                 if k in ["soc", "odometer", "range_est", "speed", "power", "plug", "charging"]}
        _, samples = get("/v1/samples?after=0&limit=1")
        seq = samples["latest_seq"]
        time.sleep(2)
        _, after = get(f"/v1/samples?after={seq}&limit=100")
        assert after["samples"] and after["latest_seq"] > seq, after
        checks["history_advancing"] = True
        with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
            target = "/v1/stream"
            request_headers = {**headers(target), "Host": "127.0.0.1", "Upgrade": "websocket",
                               "Connection": "Upgrade", "Sec-WebSocket-Version": "13",
                               "Sec-WebSocket-Key": "b2FibC1saW5rLXRlc3QxMg=="}
            request = f"GET {target} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in request_headers.items()) + "\r\n"
            sock.sendall(request.encode())
            stream = sock.makefile("rb")
            assert b"101" in stream.readline()
            while stream.readline() != b"\r\n":
                pass
            opcode, length = stream.read(2)
            assert opcode & 15 == 1
            length &= 127
            if length == 126:
                length = struct.unpack("!H", stream.read(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", stream.read(8))[0]
            frame = json.loads(stream.read(length))
            assert frame["seq"] > seq and frame["values"], frame
            checks["authenticated_websocket_stream"] = True
        if args.output:
            args.output.mkdir(parents=True, exist_ok=True)
            for name, value in [("hello", hello), ("now", now), ("samples", after), ("verification", checks)]:
                (args.output / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n")
        print(json.dumps(checks, indent=2))
    finally:
        adb("forward", "--remove", f"tcp:{port}")


if __name__ == "__main__":
    main()
