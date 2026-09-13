#!/usr/bin/env python3
"""Generates the instrumented-test video asset.

Each frame encodes its own frame index as a 6-bit black/white barcode, so an instrumented test can
decode a frame at a given timestamp and assert *which* frame actually came back. That is how the
frame-accuracy of `MediaMetadataRetriever.OPTION_CLOSEST` is verified, and it is insensitive to
H.264 quantisation because the bars are pure black and pure white.

Requires: python3 with Pillow, and ffmpeg. Run from the repository root:

    python3 tools/generate_test_video.py
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

WIDTH, HEIGHT = 320, 240
FPS = 30
FRAMES = 60
BITS = 6
BAR_WIDTH = 48
BAR_HEIGHT = 192
BAR_TOP = 24
BAR_LEFT = 16
BAR_INSET = 4
# A keyframe every 30 frames forces a real decode-forward for most seeks instead of a cache hit.
KEYFRAME_INTERVAL = 30

OUTPUT = pathlib.Path("app/src/androidTest/assets/frame_barcode_320x240_30fps_2s.mp4")


def render_frame(index: int) -> Image.Image:
    image = Image.new("L", (WIDTH, HEIGHT), 0)
    for bit in range(BITS):
        if index & (1 << bit):
            x0 = BAR_LEFT + bit * BAR_WIDTH + BAR_INSET
            image.paste(
                255,
                (x0, BAR_TOP + BAR_INSET, x0 + (BAR_WIDTH - 2 * BAR_INSET), BAR_TOP + BAR_HEIGHT - BAR_INSET),
            )
    return image


def main() -> int:
    if shutil.which("ffmpeg") is None:
        print("ffmpeg is required", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = pathlib.Path(tmp)
        for index in range(FRAMES):
            render_frame(index).save(tmp_path / f"frame_{index:04d}.png")

        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-framerate", str(FPS),
                "-i", str(tmp_path / "frame_%04d.png"),
                "-c:v", "libx264",
                "-profile:v", "baseline",
                "-pix_fmt", "yuv420p",
                "-g", str(KEYFRAME_INTERVAL),
                "-keyint_min", str(KEYFRAME_INTERVAL),
                "-sc_threshold", "0",
                "-crf", "1",
                "-movflags", "+faststart",
                str(OUTPUT),
            ],
            check=True,
        )
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
