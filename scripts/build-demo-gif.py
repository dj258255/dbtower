#!/usr/bin/env python3
"""Playwright가 찍은 7장면에 설명 띠를 붙여 README 데모 GIF를 만든다."""

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


frames_dir, captions_path, output_path = map(Path, sys.argv[1:4])
items = json.loads(captions_path.read_text(encoding="utf-8"))
width = 1100
bar_height = 54
font = None
for candidate in [
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
]:
    try:
        font = ImageFont.truetype(candidate, 24)
        break
    except OSError:
        continue
if font is None:
    font = ImageFont.load_default()

frames = []
durations = []
for item in items:
    image = Image.open(frames_dir / item["file"]).convert("RGB")
    height = round(image.height * width / image.width)
    image = image.resize((width, height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (width, height + bar_height), (30, 35, 42))
    canvas.paste(image, (0, bar_height))
    ImageDraw.Draw(canvas).text((18, 13), item["caption"], fill=(255, 255, 255), font=font)
    frames.append(canvas.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE))
    durations.append(item.get("ms", 2600))

frames[0].save(output_path, save_all=True, append_images=frames[1:], duration=durations,
               loop=0, optimize=True, disposal=2)
print(f"{output_path} {len(frames)} frames")
