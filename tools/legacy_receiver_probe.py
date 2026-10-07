#!/usr/bin/env python3
"""Local legacy-protocol interoperability probe. Logs metadata, never screen/audio payloads."""
import json
import os
import hashlib
import shutil
import plistlib
import signal
import socket
import socketserver
import struct
import subprocess
import threading
import time
import uuid
from pathlib import Path

NAME = "WifiScreen Test IPv4"
MAC = "02:4B:57:46:53:02"
CONTROL = 47110
MIRROR = 47111
AUDIO = 47112
HTTP = 47113
IP = os.environ.get("WIFISCREEN_LISTEN_IP") or subprocess.check_output(["/usr/sbin/ipconfig", "getifaddr", "en0"], text=True).strip()
UID = str(uuid.uuid4())
RUN = threading.Event()
RUN.set()
LOG = Path("output/qa/legacy-probe.jsonl")
LOG.parent.mkdir(parents=True, exist_ok=True)
lock = threading.Lock()
children = []
servers = []

def event(event_type, **fields):
    row = {"time": round(time.time(), 3), "event": event_type, **fields}
    text = json.dumps(row, ensure_ascii=False)
    with lock:
        with LOG.open("a") as out:
            out.write(text + "\n")
        print(text, flush=True)

def read_exact(stream, size):
    result = bytearray()
    while len(result) < size:
        chunk = stream.read(size - len(result))
        if not chunk:
            raise EOFError()
        result.extend(chunk)
    return bytes(result)

def description():
    return ('<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0">'
            '<specVersion><major>1</major><minor>0</minor></specVersion><device>'
            '<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>'
            '<friendlyName>' + NAME + '</friendlyName><manufacturer>WifiScreen Project</manufacturer>'
            '<modelName>WifiScreen</modelName><modelNumber>0.1</modelNumber><UDN>uuid:' + UID + '</UDN>'
            '<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>'
            '<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/avtransport.xml</SCPDURL>'
            '<controlURL>/avtransport</controlURL><eventSubURL>/events</eventSubURL></service></serviceList>'
            '</device></root>').encode()

class VideoValidator:
    """Decode to a null sink for interoperability validation; never save screen content."""
    def __init__(self):
        self.process = None
        self.error_file = None
        self.stream_time = None
        self.failed = False
        self.progress_thread = None

    def feed(self, kind, header, payload):
        if self.failed or not shutil.which("ffmpeg"):
            return
        try:
            if kind == 1:
                if len(payload) < 7 or payload[0] != 1:
                    return
                position = 6
                units = []
                for count in (payload[5] & 31,):
                    for _ in range(count):
                        size = int.from_bytes(payload[position:position + 2], "big")
                        position += 2
                        if size < 1 or position + size > len(payload):
                            raise ValueError("Invalid AVC configuration")
                        units.append(payload[position:position + size])
                        position += size
                count = payload[position]
                position += 1
                for _ in range(count):
                    size = int.from_bytes(payload[position:position + 2], "big")
                    position += 2
                    if size < 1 or position + size > len(payload):
                        raise ValueError("Invalid PPS configuration")
                    units.append(payload[position:position + size])
                    position += size
                if self.process is None:
                    self.error_file = open("output/qa/video-validation-errors.log", "a")
                    self.process = subprocess.Popen(
                        ["ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostats",
                         "-flags", "low_delay", "-probesize", "32",
                         "-analyzeduration", "0", "-f", "h264", "-i", "pipe:0",
                         "-f", "null", "-", "-progress", "pipe:1", "-stats_period", "1"],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error_file)
                    self.progress_thread = threading.Thread(target=self.progress, args=(self.process,), daemon=True)
                    self.progress_thread.start()
                self.process.stdin.write(b"".join(b"\0\0\0\1" + unit for unit in units))
                self.process.stdin.flush()
                event("avc_configuration", parameter_sets=len(units))
            elif kind == 0 and self.process is not None and len(payload) > 5:
                size = int.from_bytes(payload[:4], "big")
                if size != len(payload) - 4:
                    raise ValueError("Invalid AVC access-unit length")
                data = bytearray(payload)
                if (data[4] & 31) in (5, 19):
                    if not self.stream_time:
                        raise ValueError("Missing stream context")
                    from Crypto.Cipher import AES
                    def derive(value):
                        return bytes(byte ^ 0x78 for byte in hashlib.md5(value.encode()).digest())
                    count = ((len(data) - 5) // 32) * 16
                    if count:
                        cipher = AES.new(derive("Happycast/1.0"), AES.MODE_CBC, derive(self.stream_time))
                        data[5:5 + count] = cipher.decrypt(bytes(data[5:5 + count]))
                self.process.stdin.write(b"\0\0\0\1" + bytes(data[4:]))
                self.process.stdin.flush()
        except Exception as error:
            self.failed = True
            event("video_validation_error", reason=type(error).__name__, message=str(error)[:120])
            self.close()

    def progress(self, process):
        for line in process.stdout:
            text = line.decode("utf8", "replace").strip()
            if text.startswith("frame="):
                event("decoded_video", frames=int(text.split("=", 1)[1]))

    def close(self):
        process = self.process
        self.process = None
        if process is not None:
            try:
                process.stdin.close()
                process.wait(timeout=2)
            except Exception:
                process.terminate()
            if self.progress_thread is not None:
                self.progress_thread.join(timeout=1)
            if process.stdout is not None:
                process.stdout.close()
        if self.error_file is not None:
            self.error_file.close()
            self.error_file = None

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(40)
        frame_count = 0
        saw_request = False
        validator = VideoValidator()
        try:
            while RUN.is_set():
                prefix = read_exact(self.rfile, 4)
                if prefix not in (b"GET ", b"POST", b"OPTI", b"ANNO", b"SETU", b"RECO", b"SET_", b"GET_", b"TEAR"):
                    header = prefix + read_exact(self.rfile, 124)
                    size, kind = struct.unpack_from("<IH", header)
                    if size > 8 * 1024 * 1024:
                        event("invalid_frame_length", size=size)
                        return
                    payload = read_exact(self.rfile, size)
                    frame_count += 1
                    if frame_count <= 4 or frame_count % 120 == 0:
                        event("frame", count=frame_count, kind=kind, size=size,
                              width=struct.unpack_from("<f", header, 16)[0],
                              height=struct.unpack_from("<f", header, 20)[0])
                    validator.feed(kind, header, payload)
                    del payload
                    continue
                head = bytearray(prefix)
                while not head.endswith(b"\r\n\r\n"):
                    head.extend(read_exact(self.rfile, 1))
                    if len(head) > 16384:
                        return
                lines = head.decode("utf-8", "replace").split("\r\n")
                parts = lines[0].split(" ")
                if len(parts) != 3:
                    return
                method, path, protocol = parts
                saw_request = True
                headers = {}
                for line in lines[1:]:
                    if ":" in line:
                        key, value = line.split(":", 1)
                        headers[key.lower()] = value.strip()
                length = int(headers.get("content-length", "0"))
                if not 0 <= length <= 65536:
                    return
                body = read_exact(self.rfile, length)
                if "stream-time" in headers:
                    validator.stream_time = headers["stream-time"]
                event("request", port=self.server.server_address[1], method=method,
                      path=path.split("?")[0], protocol=protocol, length=length,
                      header_names=sorted(headers))
                answer = b""
                content_type = "text/parameters"
                status = "200 OK"
                extra = {}
                if path in ("/", "/description.xml"):
                    answer = description()
                    content_type = "text/xml"
                elif path == "/server-info":
                    answer = plistlib.dumps({"deviceid": MAC, "features": 0x5A7FFFF7,
                        "model": "WifiScreen,1", "protovers": "1.0", "srcvers": "220.68",
                        "name": NAME, "vv": "1"})
                    content_type = "text/x-apple-plist+xml"
                elif path in ("/stream", "/stream.xml") and method == "GET":
                    answer = plistlib.dumps({"width": 1920, "height": 1080, "refreshRate": 60.0,
                        "overscanned": False, "streams": [{"type": 110, "dataPort": MIRROR}]})
                    content_type = "text/x-apple-plist+xml"
                elif path == "/reverse":
                    status = "101 Switching Protocols"
                    extra = {"Upgrade": "PTTH/1.0", "Connection": "Upgrade"}
                    self.connection.settimeout(None)
                elif path == "/stream" and method == "POST":
                    if body.startswith(b"bplist") or body.startswith(b"<?xml"):
                        try:
                            event("stream_metadata_keys", keys=sorted(plistlib.loads(body).keys()))
                        except Exception:
                            pass
                    # This is a one-way transition into the framed video channel.
                    # Sending an HTTP response here is consumed by the sender as
                    # a binary feedback packet and can abort the stream.
                    continue
                elif method == "SETUP":
                    if "/audio" in path:
                        extra["Transport"] = "RTP/AVP/UDP;unicast;mode=record;server_port=" + str(AUDIO) + ";control_port=" + str(AUDIO) + ";timing_port=" + str(AUDIO)
                    else:
                        extra["Transport"] = "RTP/AVP/TCP;unicast;mode=record;server_port=" + str(MIRROR)
                    extra["Session"] = "wifiscreen-test"
                elif method == "GET_PARAMETER":
                    answer = b"volume: -3.000000\r\n"
                elif method in ("ANNOUNCE", "RECORD", "OPTIONS", "SET_PARAMETER", "TEARDOWN"):
                    extra["Session"] = "wifiscreen-test"
                elif path in ("/feedback", "/heartbat", "/stop"):
                    pass
                else:
                    status = "501 Not Implemented"
                    event("unsupported", method=method, path=path.split("?")[0])
                result = protocol + " " + status + "\r\nServer: WifiScreen/0.1\r\n"
                if "cseq" in headers:
                    result += "CSeq: " + headers["cseq"] + "\r\n"
                for key, value in extra.items():
                    result += key + ": " + value + "\r\n"
                result += "Content-Type: " + content_type + "\r\nContent-Length: " + str(len(answer)) + "\r\n\r\n"
                self.wfile.write(result.encode() + answer)
                self.wfile.flush()
                if method == "TEARDOWN":
                    return
        except (EOFError, ConnectionError, TimeoutError, OSError) as error:
            if saw_request or frame_count:
                event("connection_end", reason=type(error).__name__, frames=frame_count)
        except Exception as error:
            event("handler_error", reason=type(error).__name__, message=str(error)[:160])
        finally:
            validator.close()
            if saw_request or frame_count:
                event("connection_closed", port=self.server.server_address[1], frames=frame_count)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

def ssdp():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", 1900))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    socket.inet_aton("239.255.255.250") + socket.inet_aton(IP))
    sock.settimeout(1)
    try:
        while RUN.is_set():
            try:
                data, peer = sock.recvfrom(8192)
            except socket.timeout:
                continue
            if not data.startswith(b"M-SEARCH"):
                continue
            response = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=60\r\n"
                        "LOCATION: http://" + IP + ":" + str(HTTP) + "/description.xml\r\n"
                        "SERVER: WifiScreen/0.1 UPnP/1.0\r\n"
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                        "USN: uuid:" + UID + "::urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                        "EXT:\r\n\r\n")
            sock.sendto(response.encode(), peer)
    finally:
        sock.close()

def audio():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((IP, AUDIO))
    sock.settimeout(1)
    count = 0
    try:
        while RUN.is_set():
            try:
                data, peer = sock.recvfrom(65536)
            except socket.timeout:
                continue
            count += 1
            if count <= 3 or count % 200 == 0:
                event("audio_packet", count=count, size=len(data))
    finally:
        sock.close()

def stop(*_):
    RUN.clear()

def main():
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        for port in (CONTROL, MIRROR, HTTP):
            server = Server((IP, port), Handler)
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        for target in (ssdp, audio):
            threading.Thread(target=target, daemon=True).start()
        records = [
            [NAME, "_leboremote._tcp", str(CONTROL), "port=" + str(CONTROL),
             "version=3.2", "w=1920", "h=1080", "raop=" + str(CONTROL),
             "airplay=" + str(MIRROR), "remote=" + str(CONTROL),
             "lelinkport=" + str(CONTROL), "devicemac=" + MAC,
             "mirror=" + str(MIRROR), "channel=WifiScreen-0.1",
             "feature=162303", "lebofeature=162303", "packagename=com.kanayama.wifiscreen",
             "u=7000245757465302", "ver=1.0", "appInfo=0", "vv=1",
             "htv=1", "atv=0", "etv=1", "hmd=WifiScreen", "hstv=150.33"],
            [NAME, "_airplay._tcp", str(MIRROR), "deviceid=" + MAC,
             "features=0x5A7FFFF7", "model=WifiScreen,1", "srcvers=220.68",
             "vv=1", "flags=0x4", "pw=0", "protovers=1.0", "pi=" + UID],
            [MAC.replace(":", "") + "@" + NAME, "_raop._tcp", str(CONTROL),
             "ch=2", "cn=0", "et=0", "sr=44100", "ss=16", "tp=UDP",
             "vv=1", "am=WifiScreen,1", "sv=false", "vs=220.68"]
        ]
        for name, kind, port, *txt in records:
            children.append(subprocess.Popen(
                ["/usr/bin/dns-sd", "-P", name, kind, "local.", port,
                 "wifiscreen-receiver.local.", IP, *txt],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        event("ready", name=NAME, ip=IP, control=CONTROL, mirror=MIRROR)
        deadline = time.monotonic() + 900
        while RUN.is_set() and time.monotonic() < deadline:
            time.sleep(.5)
    finally:
        RUN.clear()
        for child in children:
            child.terminate()
        for server in servers:
            server.shutdown()
            server.server_close()
        event("stopped")

if __name__ == "__main__":
    main()
