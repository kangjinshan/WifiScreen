#!/usr/bin/env python3
"""Send original synthetic AVC/HEVC over the real receiver TCP path; never reads a user's screen.

Requires ffmpeg with libx264/libx265 and pycryptodome. Point ports at an idle test receiver.
For an emulator bound to its LAN address, use `adb emu redir`, not localhost-only `adb forward`.
"""
import argparse
import hashlib
import json
import plistlib
import re
import socket
import struct
import subprocess
import time
import urllib.request
from pathlib import Path
from Crypto.Cipher import AES


def packet(kind, data, width, height, frame=0):
    header = bytearray(128)
    seconds = frame / 30 + 1
    struct.pack_into("<IH", header, 0, len(data), kind)
    struct.pack_into("<IIff", header, 8, int(seconds % 1 * 2**32), int(seconds), width, height)
    return header + data


def response(sock):
    stream = sock.makefile("rb")
    assert b"200 OK" in stream.readline()
    length = 0
    while True:
        line = stream.readline()
        if line == b"\r\n":
            break
        if not line:
            raise EOFError("Incomplete response")
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":", 1)[1])
    return stream.read(length)


def key(value):
    return bytes(b ^ 0x78 for b in hashlib.md5(value.encode()).digest())


def config(units, case):
    if case == "h264":
        sps = next(u for u in units if u[0] & 31 == 7)
        pps = next(u for u in units if u[0] & 31 == 8)
        return bytes([1, *sps[1:4], 255, 225]) + struct.pack(">H", len(sps)) + sps + b"\x01" + struct.pack(">H", len(pps)) + pps
    sets = [next(u for u in units if (u[0] & 126) >> 1 == kind) for kind in (32, 33, 34)]
    if case == "hevc-legacy":
        blob = b"\x00\x00\x00\x01".join(sets)
        return bytes([1, *blob[1:4], 255, 225]) + struct.pack(">H", len(blob)) + blob + b"\x01\x00\x00"
    header = bytes.fromhex("0101600000009000000000005df000fcfdf8f800000f03")
    return header + b"".join(bytes([128 | ((u[0] & 126) >> 1)]) + struct.pack(">HH", 1, len(u)) + u for u in sets)


def run_case(args, case):
    width, height = (360, 640) if case == "hevc-legacy" else (640, 360)
    params = "keyint=30:min-keyint=30:scenecut=0:bframes=0"
    options = ["-x264-params", params + ":slices=1"] if case == "h264" else [
        "-x265-params", params + ":pools=1:frame-threads=1:log-level=error"]
    encoded = subprocess.check_output([
        "ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate=30",
        "-frames:v", "60", "-c:v", "libx264" if case == "h264" else "libx265",
        "-preset", "ultrafast", "-threads", "1", *options, "-f", "h264" if case == "h264" else "hevc", "pipe:1",
    ])
    units = [u for u in re.split(b"\x00\x00\x00?\x01", encoded) if u]
    parameters = config(units, case)
    with socket.create_connection((args.host, args.control_port), timeout=15) as ctrl, socket.create_connection(
        (args.host, args.video_port), timeout=15
    ) as video:
        ctrl.sendall(b"GET /stream RTSP/1.0\r\nCSeq: 1\r\n\r\n")
        advertised = plistlib.loads(response(ctrl))["avformat_support"]
        video.sendall(b"GET /stream.xml HTTP/1.1\r\nStream-Time: fixture-time\r\n\r\n")
        response(video)
        video.sendall(b"POST /stream HTTP/1.1\r\nContent-Length: 0\r\n\r\n" + packet(1, parameters, width, height))
        sequence = 0
        for unit in units:
            kind = unit[0] & 31 if case == "h264" else (unit[0] & 126) >> 1
            if (case == "h264" and kind not in (1, 5)) or (case != "h264" and kind > 31):
                continue
            data = bytearray(struct.pack(">I", len(unit)) + unit)
            if (case == "h264" and kind == 5) or (case == "hevc-legacy" and kind == 19):
                length = (len(data) - 5) // 32 * 16
                data[5:5 + length] = AES.new(key("Happycast/1.0"), AES.MODE_CBC, key("fixture-time")).encrypt(bytes(data[5:5 + length]))
            video.sendall(packet(0, data, width, height, sequence))
            sequence += 1
            if sequence == 30:
                video.sendall(packet(1, parameters, width, height))
            time.sleep(1 / 30)
        deadline = time.monotonic() + 20
        state = {}
        while time.monotonic() < deadline:
            with urllib.request.urlopen(f"http://{args.host}:{args.control_port}/status", timeout=5) as response_file:
                state = json.load(response_file)
            if state["videoDecoded"] >= sequence:
                break
            time.sleep(.25)
        expected = "H264" if case == "h264" else "H265"
        result = {"case": case, "advertisedHevc": advertised, "sent": sequence, **state}
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / f"{case}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        assert state["videoEncoding"] == expected, result
        assert state["playing"] and state["videoDecoded"] >= sequence, result
        assert [state["videoWidth"], state["videoHeight"]] == [width, height], result
        assert not state["lastError"], result
        assert state["videoDiagnostics"]["decoderStarts"] == 1, result
        print(f"PASS {case}: {sequence} frames, {state['videoCodec']}, repeated configuration retained", flush=True)
        if args.hold:
            time.sleep(args.hold)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--control-port", type=int, default=47110)
    parser.add_argument("--video-port", type=int, default=47111)
    parser.add_argument("--case", choices=["h264", "hevc-hvcc", "hevc-legacy"])
    parser.add_argument("--output", type=Path, default=Path("output/qa/video-codecs"))
    parser.add_argument("--hold", type=float, default=0, help="Keep the last synthetic frame for visual inspection")
    args = parser.parse_args()
    for case in ([args.case] if args.case else ["h264", "hevc-hvcc", "hevc-legacy"]):
        run_case(args, case)
        time.sleep(.5)


if __name__ == "__main__":
    main()
