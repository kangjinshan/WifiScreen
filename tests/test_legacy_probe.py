import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import hashlib
import re
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch

with patch.dict(os.environ, {"WIFISCREEN_LISTEN_IP": "127.0.0.1"}):
    spec = importlib.util.spec_from_file_location(
        "probe", Path(__file__).resolve().parents[1] / "tools" / "legacy_receiver_probe.py")
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)

class ProbeTest(unittest.TestCase):
    def test_frame_metadata_does_not_conflict_with_event_name(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(probe, "LOG", Path(tmp) / "log"):
            with contextlib.redirect_stdout(io.StringIO()):
                probe.event("frame", kind=1, size=35)
            row = json.loads(probe.LOG.read_text())
            self.assertEqual(row["event"], "frame")
            self.assertEqual(row["kind"], 1)

    def test_post_stream_accepts_first_config_frame_without_closing(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(probe, "LOG", Path(tmp) / "log"):
            server = probe.Server(("127.0.0.1", 0), probe.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    with socket.create_connection(server.server_address, timeout=2) as client:
                        request = b"POST /stream HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
                        header = struct.pack("<IH", 7, 1) + bytes(122)
                        client.sendall(request + header + b"config1")
                        client.shutdown(socket.SHUT_WR)
                        self.assertEqual(client.recv(1), b"")
                rows = [json.loads(line) for line in probe.LOG.read_text().splitlines()]
                self.assertTrue(any(r["event"] == "frame" and r["kind"] == 1 for r in rows))
                self.assertFalse(any(r["event"] == "handler_error" for r in rows))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def test_fragmented_prefix_is_read_exactly(self):
        class Fragmented(io.BytesIO):
            def read(self, size=-1):
                return super().read(min(size, 1))
        self.assertEqual(probe.read_exact(Fragmented(b"POST"), 4), b"POST")

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg required for decoder integration")
    def test_first_idr_is_retained_and_decoded(self):
        from Crypto.Cipher import AES
        encoded = subprocess.check_output([
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=30",
            "-frames:v", "30", "-c:v", "libx264", "-preset", "ultrafast",
            "-x264-params", "keyint=250:min-keyint=250:scenecut=0", "-f", "h264", "pipe:1"
        ])
        units = [u for u in re.split(b"\x00\x00\x00?\x01", encoded) if u]
        sps = next(u for u in units if u[0] & 31 == 7)
        pps = next(u for u in units if u[0] & 31 == 8)
        config = bytes([1, sps[1], sps[2], sps[3], 255, 225]) + len(sps).to_bytes(2, "big") + sps
        config += b"\x01" + len(pps).to_bytes(2, "big") + pps
        def key(value):
            return bytes(x ^ 0x78 for x in hashlib.md5(value.encode()).digest())
        with tempfile.TemporaryDirectory() as tmp, patch.object(probe, "LOG", Path(tmp) / "log"):
            validator = probe.VideoValidator()
            validator.stream_time = "fixture-time"
            with contextlib.redirect_stdout(io.StringIO()):
                validator.feed(1, bytes(128), config)
                for unit in units:
                    if unit[0] & 31 not in (1, 5):
                        continue
                    payload = bytearray(len(unit).to_bytes(4, "big") + unit)
                    if unit[0] & 31 == 5:
                        count = ((len(payload) - 5) // 32) * 16
                        cipher = AES.new(key("Happycast/1.0"), AES.MODE_CBC, key("fixture-time"))
                        payload[5:5 + count] = cipher.encrypt(bytes(payload[5:5 + count]))
                    validator.feed(0, bytes(128), payload)
                validator.close()
            rows = [json.loads(line) for line in probe.LOG.read_text().splitlines()]
            self.assertFalse(any(r["event"] == "video_validation_error" for r in rows))
            self.assertEqual(max(r["frames"] for r in rows if r["event"] == "decoded_video"), 30)

if __name__ == "__main__":
    unittest.main()
