#!/usr/bin/env python3
"""Rebuild the original synthetic 1080p30 decoder fixtures (requires FFmpeg with x264/x265)."""
from pathlib import Path
import subprocess

TARGET = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/codec-test"


def main():
    TARGET.mkdir(parents=True, exist_ok=True)
    for name, encoder, params in (
        ("h264", "libx264", ["-x264-params", "keyint=30:min-keyint=30:scenecut=0:bframes=0"]),
        ("h265", "libx265", ["-x265-params", "keyint=30:min-keyint=30:scenecut=0:bframes=0:pools=2:frame-threads=2", "-tag:v", "hvc1"]),
    ):
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "testsrc2=size=1920x1080:rate=30:duration=2", "-an", "-c:v", encoder,
            "-preset", "fast", "-crf", "28", "-pix_fmt", "yuv420p", "-threads", "2",
            *params, "-movflags", "+faststart", str(TARGET / f"{name}.mp4"),
        ], check=True)


if __name__ == "__main__":
    main()
