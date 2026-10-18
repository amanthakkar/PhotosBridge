#!/usr/bin/env python3
"""Generate the launcher icon: 8-petal pinwheel on dark charcoal.

Drawn at 4x (2048) and downscaled for smooth edges. Original geometry:
teardrop petals (parametric polygon) rotated around a white center.
"""
import math
import os

from PIL import Image, ImageDraw

PROJECT = os.path.dirname(os.path.abspath(__file__))
S = 2048  # supersample size; master is 512
CENTER = S / 2.0

COLORS = [
    (255, 59, 48),    # red
    (255, 149, 0),    # orange
    (255, 204, 0),    # yellow
    (52, 199, 89),    # green
    (0, 199, 190),    # teal
    (0, 122, 255),    # blue
    (175, 82, 222),   # purple
    (255, 45, 85),    # pink
]


def petal_layer(angle_deg, color):
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r0 = S * 0.080          # petal base radius (just outside center dot)
    r1 = S * 0.365          # petal tip radius
    maxw = S * 0.118        # max half-width
    steps = 48
    pts = []
    for i in range(steps + 1):           # left edge, base -> tip
        t = i / steps
        r = r0 + (r1 - r0) * t
        w = maxw * (math.sin(math.pi * t) ** 0.75) * (1.0 - 0.12 * t)
        pts.append((CENTER - w, CENTER - r))
    for i in range(steps, -1, -1):       # right edge, tip -> base
        t = i / steps
        r = r0 + (r1 - r0) * t
        w = maxw * (math.sin(math.pi * t) ** 0.75) * (1.0 - 0.12 * t)
        pts.append((CENTER + w, CENTER - r))
    d.polygon(pts, fill=color + (238,))
    return layer.rotate(-angle_deg, resample=Image.BICUBIC,
                        center=(CENTER, CENTER))


def main():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, S, S], radius=S * 0.225,
                        fill=(28, 28, 30, 255))
    for i, color in enumerate(COLORS):
        img.alpha_composite(petal_layer(i * 45.0, color))
    d = ImageDraw.Draw(img)
    cr = S * 0.072
    d.ellipse([CENTER - cr, CENTER - cr, CENTER + cr, CENTER + cr],
              fill=(255, 255, 255, 255))

    master = img.resize((512, 512), Image.LANCZOS)
    master_path = os.path.join(PROJECT, "icon-512.png")
    master.save(master_path)
    print("master:", master_path)

    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px in sizes.items():
        out_dir = os.path.join(PROJECT, "res", folder)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "ic_launcher.png")
        master.resize((px, px), Image.LANCZOS).save(out)
        print("wrote", out, px)


if __name__ == "__main__":
    main()
