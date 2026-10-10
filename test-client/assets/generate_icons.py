#!/usr/bin/env python3
"""Regenerate SI-02 taskbar/window icons from the editable SVG artwork.

Developer-only script. Requirements: pip install cairosvg Pillow
The Java/Windows Maven build consumes committed PNG and ICO resources and
never requires Python, CairoSVG or Pillow.
"""
from __future__ import annotations

import io
import struct
from pathlib import Path

import cairosvg
from PIL import Image

SOURCE = Path(__file__).with_name("event-timing.svg")
TARGET = SOURCE.parent.parent / "src" / "main" / "resources" / "icons"
RESOLUTIONS = (16, 24, 32, 48, 64, 128, 256)


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    # Render at 4x the largest icon, then downsample for crisp small artwork.
    rgba = Image.open(io.BytesIO(cairosvg.svg2png(
        bytestring=SOURCE.read_bytes(), output_width=1024, output_height=1024
    ))).convert("RGBA")

    png_frames: list[bytes] = []
    for size in RESOLUTIONS:
        scaled = rgba.resize((size, size), Image.Resampling.LANCZOS)
        # Color quantization keeps small/high-DPI ICO frames compact and
        # preserves RGBA alpha at the rounded-square corners.
        indexed = scaled.quantize(
            colors=64 if size <= 48 else 96,
            method=Image.Quantize.FASTOCTREE,
            dither=Image.Dither.NONE,
        )
        buffer = io.BytesIO()
        indexed.save(buffer, format="PNG", optimize=True)
        png_frames.append(buffer.getvalue())

    (TARGET / "event-timing.png").write_bytes(png_frames[-1])
    directory = bytearray(struct.pack("<HHH", 0, 1, len(RESOLUTIONS)))
    offset = 6 + 16 * len(RESOLUTIONS)
    for size, frame in zip(RESOLUTIONS, png_frames):
        directory += struct.pack(
            "<BBBBHHII", size if size < 256 else 0,
            size if size < 256 else 0, 0, 0, 1, 32, len(frame), offset,
        )
        offset += len(frame)
    (TARGET / "event-timing.ico").write_bytes(directory + b"".join(png_frames))


if __name__ == "__main__":
    main()
