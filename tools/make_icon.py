"""Sky Dome icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the adaptive icon
(`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python -I tools/make_icon.py [export.png]

Layer 0: the zenith-navy tile. Layer 1: the dome over the horizon with an amber constellation inside.
sdf_N = the layer's alpha in white. Coordinates below are the vector's 108-unit viewport.
"""
import math
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
NAVY = (15, 42, 82, 255)
DOME = (30, 74, 134, 255)
WHITE = (255, 255, 255, 255)
AMBER = (255, 200, 107, 255)


def p(x, y):
    return (x * K, y * K)


def dot(d, x, y, r, fill):
    d.ellipse((p(x - r, y - r), p(x + r, y + r)), fill=fill)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=NAVY)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.pieslice((p(27, 42), p(81, 96)), 180, 360, fill=DOME)
    d.arc((p(27, 42), p(81, 96)), 180, 360, fill=WHITE, width=int(3 * K))
    d.rectangle((p(21, 68), p(87, 71)), fill=WHITE)
    pts = [(39, 60), (47, 51), (58, 48), (67, 57)]
    d.line([p(*q) for q in pts], fill=AMBER, width=int(1.8 * K), joint="curve")
    for (x, y), r in zip(pts, (2.4, 2.6, 2.8, 2.2)):
        dot(d, x, y, r, AMBER)
    for x, y, r in ((45, 64, 1.2), (64, 64, 1.2), (53, 59, 1.1), (35, 53.8, 1.0), (71, 48.8, 1.0)):
        dot(d, x, y, r, WHITE)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        Image.alpha_composite(l0, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
