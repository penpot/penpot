"""Pixel diff of two folders of PNG snapshots with the same file names.

Usage: python3 -I diff.py <before-dir> <after-dir>
Prints, per file, the number of differing pixels and their bounding box,
or the two sizes when they differ.
"""
import os
import sys

from PIL import Image, ImageChops

before, after = sys.argv[1], sys.argv[2]
for name in sorted(os.listdir(before)):
    other = os.path.join(after, name)
    if not os.path.exists(other):
        print(name, "missing in", after)
        continue
    a = Image.open(os.path.join(before, name)).convert("RGB")
    b = Image.open(other).convert("RGB")
    if a.size != b.size:
        print(name, "size", a.size, b.size)
        continue
    diff = ImageChops.difference(a, b)
    count = sum(1 for p in diff.getdata() if p != (0, 0, 0))
    print(name, count, diff.getbbox())
