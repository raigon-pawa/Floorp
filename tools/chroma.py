#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
"""Report coloured pixels in a screenshot.

The app's own UI is meant to be black, white and grey, so any coloured pixel in app chrome is a
leftover accent or tint. A pixel counts as coloured ("saturated" below) when
max(R,G,B) - min(R,G,B) >= --threshold. Screenshots of grey UI measure exactly 0, so the default
of 4 also catches faint tints such as the engine's blue-grey #2A2A2E; pass --threshold 48 to see
only vivid accents.

Usage: tools/chroma.py shot.png [more.png ...] [--rect X,Y,W,H ...] [--exclude X,Y,W,H ...]
                       [--threshold N] [--max PERCENT]
       tools/chroma.py --selftest

  --rect     only look inside these rectangles (default: the whole image)
  --exclude  ignore these rectangles (web content, site and extension icons)
  --max      exit with status 1 when the saturated share of an image exceeds PERCENT

Prints, per image, the saturated share of the examined area and the clusters of saturated pixels
(bounding box, pixel count, average colour), largest first.
"""

import argparse
import sys

from PIL import Image, ImageChops, ImageDraw, ImageStat

CELL = 16  # clusters are found on a grid of CELL x CELL pixel cells


def parse_rect(text):
    x, y, w, h = (int(v) for v in text.split(","))
    return (x, y, x + w, y + h)


def saturated_mask(image, threshold):
    """Mode "L" image: 255 where the pixel is saturated, else 0."""
    r, g, b = image.convert("RGB").split()
    high = ImageChops.lighter(ImageChops.lighter(r, g), b)
    low = ImageChops.darker(ImageChops.darker(r, g), b)
    return ImageChops.subtract(high, low).point(lambda v: 255 if v >= threshold else 0)


def area_mask(size, rects, excludes):
    """Mode "L" image: 255 where pixels are examined."""
    mask = Image.new("L", size, 0 if rects else 255)
    draw = ImageDraw.Draw(mask)
    for x0, y0, x1, y1 in rects:
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=255)
    for x0, y0, x1, y1 in excludes:
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=0)
    return mask


def clusters(mask):
    """Group touching grid cells that contain saturated pixels; return their pixel bounding boxes."""
    width, height = mask.size
    cols, rows = -(-width // CELL), -(-height // CELL)
    padded = Image.new("L", (cols * CELL, rows * CELL), 0)
    padded.paste(mask, (0, 0))
    hot = padded.resize((cols, rows), Image.BOX).load()  # mean of each cell: non-zero = has pixels
    seen = set()
    boxes = []
    for row in range(rows):
        for col in range(cols):
            if not hot[col, row] or (col, row) in seen:
                continue
            seen.add((col, row))
            stack = [(col, row)]
            c0 = c1 = col
            r0 = r1 = row
            while stack:
                c, r = stack.pop()
                c0, c1, r0, r1 = min(c0, c), max(c1, c), min(r0, r), max(r1, r)
                for dc in (-1, 0, 1):
                    for dr in (-1, 0, 1):
                        n = (c + dc, r + dr)
                        if 0 <= n[0] < cols and 0 <= n[1] < rows and n not in seen and hot[n]:
                            seen.add(n)
                            stack.append(n)
            box = (c0 * CELL, r0 * CELL, min((c1 + 1) * CELL, width), min((r1 + 1) * CELL, height))
            boxes.append(box)
    return boxes


THRESHOLD = 4


def analyse(image, rects=(), excludes=(), threshold=THRESHOLD):
    """Return (share of examined pixels that are saturated, [(box, pixels, '#rrggbb'), ...])."""
    image = image.convert("RGB")
    area = area_mask(image.size, rects, excludes)
    mask = ImageChops.multiply(saturated_mask(image, threshold), area)
    examined = ImageStat.Stat(area).sum[0] / 255
    saturated = ImageStat.Stat(mask).sum[0] / 255
    found = []
    for box in clusters(mask):
        part = mask.crop(box)
        x0, y0, x1, y1 = part.getbbox()
        tight = (box[0] + x0, box[1] + y0, box[0] + x1, box[1] + y1)
        pixels = int(ImageStat.Stat(part).sum[0] / 255)
        mean = ImageStat.Stat(image.crop(box), part).mean
        found.append((tight, pixels, "#%02x%02x%02x" % tuple(int(v) for v in mean)))
    found.sort(key=lambda item: -item[1])
    return (saturated / examined if examined else 0.0), found


def selftest():
    image = Image.new("RGB", (200, 200), (0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 199, 9), fill=(128, 128, 128))  # grey: never saturated
    draw.rectangle((40, 60, 59, 79), fill=(0, 188, 212))  # 20x20 cyan
    draw.rectangle((150, 150, 159, 159), fill=(255, 0, 0))  # 10x10 red
    share, found = analyse(image)
    assert abs(share - 500 / 40000) < 1e-9, share
    assert found == [((40, 60, 60, 80), 400, "#00bcd4"), ((150, 150, 160, 160), 100, "#ff0000")], found
    share, found = analyse(image, excludes=[(40, 60, 60, 80)])
    assert [item[1] for item in found] == [100], found
    share, found = analyse(image, rects=[(0, 0, 100, 100)])
    assert abs(share - 400 / 10000) < 1e-9 and len(found) == 1, (share, found)
    assert analyse(Image.new("RGB", (50, 50), (255, 255, 255)))[0] == 0
    # Faint tints count too: the download notification blue-grey and the engine cover.
    for tint in ((0x60, 0x7D, 0x8B), (0x2A, 0x2A, 0x2E)):
        assert analyse(Image.new("RGB", (50, 50), tint))[0] == 1, tint
    assert analyse(Image.new("RGB", (50, 50), (0x60, 0x7D, 0x8B)), threshold=48)[0] == 0
    print("selftest ok")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("images", nargs="*")
    parser.add_argument("--rect", action="append", default=[], type=parse_rect, metavar="X,Y,W,H")
    parser.add_argument("--exclude", action="append", default=[], type=parse_rect, metavar="X,Y,W,H")
    parser.add_argument("--threshold", type=int, default=THRESHOLD)
    parser.add_argument("--max", type=float, metavar="PERCENT")
    parser.add_argument("--top", type=int, default=8, help="clusters to list per image")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    if not args.images:
        parser.error("no image given")
    status = 0
    for path in args.images:
        share, found = analyse(Image.open(path), args.rect, args.exclude, args.threshold)
        print("%s: %.4f%% saturated, %d cluster(s)" % (path, share * 100, len(found)))
        for (x0, y0, x1, y1), pixels, colour in found[: args.top]:
            print("  %6d px  %s  at %d,%d %dx%d" % (pixels, colour, x0, y0, x1 - x0, y1 - y0))
        if args.max is not None and share * 100 > args.max:
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
