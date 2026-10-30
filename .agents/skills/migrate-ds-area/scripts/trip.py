"""Before | after | red diff image, one row per snapshot.

Usage: python3 -I trip.py <before-dir> <after-dir> <out.png> <scale>
Both folders hold PNGs with the same names (crop them first if needed).
"""
import os
import sys

from PIL import Image, ImageChops

before, after, out, scale = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
rows = []
for name in sorted(os.listdir(before)):
    a = Image.open(os.path.join(before, name)).convert("RGB")
    b = Image.open(os.path.join(after, name)).convert("RGB")
    w, h = max(a.width, b.width), max(a.height, b.height)
    pad_a = Image.new("RGB", (w, h), (200, 200, 200)); pad_a.paste(a, (0, 0))
    pad_b = Image.new("RGB", (w, h), (200, 200, 200)); pad_b.paste(b, (0, 0))
    mask = ImageChops.difference(pad_a, pad_b).convert("L").point(lambda v: 255 if v else 0)
    red = Image.new("RGB", (w, h), (255, 255, 255))
    red.paste((255, 0, 0), mask=mask)
    row = Image.new("RGB", (w * 3 + 20, h), (200, 200, 200))
    for i, im in enumerate([pad_a, pad_b, red]):
        row.paste(im, (i * (w + 10), 0))
    rows.append(row.resize((row.width * scale, row.height * scale), Image.NEAREST))
width = max(r.width for r in rows)
height = sum(r.height + 10 for r in rows)
canvas = Image.new("RGB", (width, height), (255, 255, 255))
y = 0
for r in rows:
    canvas.paste(r, (0, y))
    y += r.height + 10
canvas.save(out)
