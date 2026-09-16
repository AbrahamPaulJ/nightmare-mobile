"""
Rebuild the launcher assets from `icon-source.png`.

    python tools/build_icon.py            # icon.png + app/src/main/res/mipmap-*

⚠⚠ **The source is a FINISHED square icon**, full bleed: a purple gradient, a
black horse running off the left and bottom edges, a white four-point star for
the eye. That is the whole input. Everything below is measurement against those
pixels rather than numbers somebody chose, and every measurement is printed so a
later change can be checked instead of eyeballed.

⚠⚠⚠ This replaced a much longer script on 2026-09-16, and the deletion is the
point. The previous artwork arrived as a horse on an off-centre badge on a flat
page, with the neck cut flat and the eye drawn on the neck rather than the
skull — so the script had to find the badge, re-centre it, refit the gradient,
extrude two cut edges along their own fitted slopes, and solve for where a
horse's eye goes. None of that applies to a drawing that is already correct, and
keeping it would have meant maintaining corrections to a picture that no longer
has the faults.

⚠ What survives, because it is about ANDROID rather than about the drawing: an
adaptive icon is 108dp with only the middle 72dp guaranteed visible, so the
foreground layer is solved against that circle instead of being the square
composition scaled down. The muzzle sits at 93% of the source's width; masked
naively it would be cut off, which is exactly the bug that shipped once before.
"""

from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage
import os
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else "icon-source.png"
RES = "app/src/main/res"
SS = 4                       # supersampling for the corner mask

# --- read the source -------------------------------------------------------
img = Image.open(SRC).convert("RGBA")
S = img.size[0]
assert img.size[1] == S, f"the source must be square, got {img.size}"
rgb = np.array(img)[..., :3].astype(float)
lum = rgb.sum(axis=2)
print(f"source {S}x{S}")

# The two marks, by luminance: the horse is near-black, the eye near-white, and
# everything between is the gradient.
solid = ndimage.binary_fill_holes(lum < 90)      # ⚠ fill: the eye is a hole in it
EYE_M = rgb.min(axis=2) > 200

# --- the gradient, as the plane it is --------------------------------------
# ⚠ Refitted rather than sampled, because the horse covers a third of it and the
# foreground layer needs the gradient where the horse used to be. The residuals
# are printed: under one 8-bit step means this reproduces the source's gradient
# rather than approximating it.
gy, gx = np.nonzero(~solid & ~EYE_M)
A = np.c_[gx, gy, np.ones(len(gx))]
COEFS = []
for i, ch in enumerate("RGB"):
    c, *_ = np.linalg.lstsq(A, rgb[gy, gx, i], rcond=None)
    COEFS.append(c)
    print(f"  gradient {ch}: resid_std={np.std(rgb[gy, gx, i] - A @ c):.2f}")

# ⚠ The corners the fitted plane reaches, which is what `gradient()` reproduces.
_corner = lambda x, y: np.array([c[0] * x + c[1] * y + c[2] for c in COEFS])
GRAD_LIGHT, GRAD_DARK = _corner(0, 0), _corner(S - 1, S - 1)


def contrast(c):
    """WCAG contrast of a colour against the pure black of the silhouette."""
    ch = np.clip(np.asarray(c, float), 0, 255) / 255.0
    ln = np.where(ch <= 0.04045, ch / 12.92, ((ch + 0.055) / 1.055) ** 2.4)
    return (float(ln @ [0.2126, 0.7152, 0.0722]) + 0.05) / 0.05


print(f"  light corner {GRAD_LIGHT.round().astype(int)} {contrast(GRAD_LIGHT):.2f}:1, "
      f"dark {GRAD_DARK.round().astype(int)} {contrast(GRAD_DARK):.2f}:1 against the horse")
# ⚠⚠ The previous artwork failed this: its dark corner was 1.04:1, which is to
# say the muzzle was drawn in black on very nearly black. Asserted rather than
# trusted, because it is invisible in a thumbnail and obvious on a phone.
assert contrast(GRAD_DARK) >= 2.5, "the dark corner would swallow the silhouette"


# ⚠⚠ A SOFT mask, recovered from the source's own antialiasing rather than from
# a threshold. Thresholding throws away every half-lit edge pixel, and on the
# neck's long diagonal that shows as stair-steps: the mark stops looking drawn
# and starts looking traced. The edge pixels are black blended over a known
# background, so the blend factor is recoverable exactly.
_plane = sum(c[0] * np.arange(S)[None, :] + c[1] * np.arange(S)[:, None] + c[2]
             for c in COEFS)
HORSE_A = np.clip(1.0 - lum / np.maximum(_plane, 1.0), 0.0, 1.0)
HORSE_A[solid] = 1.0
# ⚠ …and zero anywhere that is not actually an edge. The plane is a fit, so out
# in the open gradient `lum/plane` wobbles by a few parts in a hundred and the
# alpha lands at 0.0-0.05 instead of 0 — invisible alone, but it tints the whole
# pasted rectangle and draws a seam where that rectangle ends.
_edge = ndimage.binary_dilation(solid, iterations=2) & ~solid
HORSE_A[~(solid | _edge)] = 0.0
EYE_A = np.clip((rgb.min(axis=2) - 140.0) / 80.0, 0.0, 1.0)
EYE_A[~ndimage.binary_dilation(EYE_M, iterations=3)] = 0.0
print(f"  soft edge: {np.count_nonzero((HORSE_A > 0.02) & (HORSE_A < 0.98))} "
      f"partially covered pixels recovered")

# ⚠⚠ The horse already runs off the LEFT and BOTTOM edges, which is what stops
# it reading as a bust on a plate. But the adaptive layer scales it DOWN, so
# those edges would land inside the frame and the cut would come into view.
# ⇒ Replicate the boundary outward. Where the source's edge is horse the padding
# is horse, where it is background the padding is background — the neck simply
# continues the way it was already going, which is the same thing the old script
# did with two fitted slopes and 60 lines.
PAD = S
HORSE_PAD = np.pad(HORSE_A, ((0, PAD), (PAD, 0)), mode="edge")
EYE_PAD = np.pad(EYE_A, ((0, PAD), (PAD, 0)), mode="constant")
print(f"  extruded {PAD}px left and down by edge replication")

# --- where the head is -----------------------------------------------------
# ⚠⚠ The adaptive layer is placed on the HEAD, not on the silhouette: the neck
# is most of the silhouette's area and runs off two edges, so centring the whole
# mark would shrink the head to nothing.
#
# ⚠ Anchored on two landmarks this drawing cannot be wrong about — the EYE (the
# only bright thing) and the MUZZLE (the right-most dark pixel) — and everything
# within [HEAD_REACH] of the eye, measured in eye-to-muzzle lengths, is the head.
# The number is printed with the resulting span so it can be checked against the
# rendered file rather than believed.
HEAD_REACH = 1.45
ys, xs = np.nonzero(solid)
MUZZLE = np.array([xs[xs.argmax()], ys[xs.argmax()]])
ey, ex = np.nonzero(EYE_M)
EYE = np.array([ex.mean(), ey.mean()])
REACH = np.hypot(*(MUZZLE - EYE)) * HEAD_REACH
_d = np.hypot(xs - EYE[0], ys - EYE[1])
HEAD = np.c_[xs[_d <= REACH], ys[_d <= REACH]]          # (x, y)
_HC = np.array([(HEAD[:, 0].min() + HEAD[:, 0].max()) / 2,
                (HEAD[:, 1].min() + HEAD[:, 1].max()) / 2])
_HR = np.hypot(*(HEAD - _HC).T).max()
print(f"eye {EYE.round().astype(int)}, muzzle {MUZZLE}, reach {REACH:.0f}px")
print(f"head spans x {HEAD[:, 0].min()}..{HEAD[:, 0].max()}, "
      f"y {HEAD[:, 1].min()}..{HEAD[:, 1].max()}; radius {_HR:.0f} about its centre")

# ⚠ Positive is RIGHT, as a fraction of the layer. The head is centred by its
# bounding CIRCLE, but the neck runs off to the lower-left, so the visual mass
# sits left of that centre and the horse reads as off to one side. ⚠⚠ It eats
# into the mask clearance, and the assert below refuses a nudge that would clip
# the muzzle rather than shipping one.
HEAD_NUDGE_X = 0.03
FILL = 0.88                  # of the guaranteed-visible radius


def gradient(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(float) * (S - 1) / (size - 1)
    return np.dstack([c[0] * xx + c[1] * yy + c[2] for c in COEFS])


def mark(size, s, ox, oy):
    """The horse and its eye, scaled by `s`, with source (0,0) at (ox, oy)."""
    w = max(1, round((S + PAD) * s))
    out = []
    for a in (HORSE_PAD, EYE_PAD):
        im = Image.fromarray((a * 255).round().astype(np.uint8)).resize((w, w), Image.LANCZOS)
        canvas = Image.new("L", (size, size), 0)
        canvas.paste(im, (round(ox - PAD * s), round(oy)))
        out.append(np.array(canvas).astype(float) / 255.0)
    return out


def compose(size, background):
    """
    ⚠⚠ Placement SOLVED against the launcher's crop, not chosen.

    A launcher may mask the 108dp layer to a circle of 72dp, so anything further
    than 72/108/2 of the layer from its centre CAN BE CUT OFF. Hand-picked
    margins kept missing it — the muzzle measured 146px against a 144px radius,
    which is why the nose was truncated on a real phone while every square
    preview looked fine.
    """
    safe = size * (72 / 108) / 2.0
    s = FILL * safe / _HR
    ox = size / 2.0 - _HC[0] * s + HEAD_NUDGE_X * size
    oy = size / 2.0 - _HC[1] * s

    horse, eye = mark(size, s, ox, oy)
    base = gradient(size) if background else np.zeros((size, size, 3))
    alpha = np.full((size, size), 255.0) if background else np.zeros((size, size))

    out = base * (1 - horse[..., None])                    # the horse is pure black
    alpha = np.maximum(alpha, horse * 255.0)
    out = out * (1 - eye[..., None]) + 255.0 * eye[..., None]
    alpha = np.maximum(alpha, eye * 255.0)

    half = size / 2.0
    hp = HEAD * s + [ox, oy]
    worst = np.hypot(hp[:, 0] - half, hp[:, 1] - half).max()
    print(f"  {size}px  scale {s:.3f}  furthest head pixel {worst:.0f} from centre; "
          f"a circular mask keeps {safe:.0f}  {'OK' if worst <= safe else 'TRUNCATED'}")
    assert worst <= safe, (
        f"the head reaches {worst:.0f}px but the mask keeps {safe:.0f} — "
        f"lower HEAD_NUDGE_X or FILL")
    return Image.fromarray(np.dstack([np.clip(out, 0, 255), alpha]).astype(np.uint8))


# --- 1. the square icon ----------------------------------------------------
# ⚠ The source itself, resized, with the corners as transparency. Nothing is
# recomposed: the drawing is already the square icon, and re-deriving it would
# only be a chance to get it wrong.
print("icon.png:")
CORNER = 0.18
icon = img.resize((1024, 1024), Image.LANCZOS)
rr = Image.new("L", (1024 * SS, 1024 * SS), 0)
ImageDraw.Draw(rr).rounded_rectangle([0, 0, 1024 * SS - 1, 1024 * SS - 1],
                                     radius=round(CORNER * 1024) * SS, fill=255)
icon.putalpha(rr.resize((1024, 1024), Image.LANCZOS))
# ⚠ Written to a temp file and renamed: saving straight over icon.png fails with
# "Invalid argument" whenever anything on Windows still has the old file open —
# a preview pane is enough — and the whole run is otherwise wasted.
icon.save("icon.png.tmp", format="PNG")
os.replace("icon.png.tmp", "icon.png")
print(f"  1024px, corner radius {CORNER * 100:.0f}%")

# --- 2. the launcher assets ------------------------------------------------
print("launcher (adaptive 432 = 108dp @ xxxhdpi):")
os.makedirs(f"{RES}/mipmap-xxxhdpi", exist_ok=True)
compose(432, background=False).save(f"{RES}/mipmap-xxxhdpi/ic_launcher_foreground.png")
Image.fromarray(np.dstack([gradient(432), np.full((432, 432), 255.0)]).astype(np.uint8)) \
    .save(f"{RES}/mipmap-xxxhdpi/ic_launcher_background.png")
print("  background + foreground layers written")

# Legacy square icons, for launchers and Android versions with no adaptive
# support. ⚠ Downscaled from the 1024 render rather than recomposed: at 48px a
# fresh composition would antialias the ear tips into nothing.
print("legacy:")
for d, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144),
              ("xxxhdpi", 192)]:
    os.makedirs(f"{RES}/mipmap-{d}", exist_ok=True)
    small = icon.resize((px, px), Image.LANCZOS)
    small.save(f"{RES}/mipmap-{d}/ic_launcher.png")
    small.save(f"{RES}/mipmap-{d}/ic_launcher_round.png")
    print(f"  {d} {px}px")

# ⭐ The in-app logo (the Library header). ⚠ A PNG in drawable-nodpi, because the
# launcher icon is adaptive XML on every supported API and Compose cannot paint that.
os.makedirs(f"{RES}/drawable-nodpi", exist_ok=True)
icon.resize((192, 192), Image.LANCZOS).save(f"{RES}/drawable-nodpi/brand_logo.png")
print("  brand_logo 192px")

os.makedirs(f"{RES}/mipmap-anydpi-v26", exist_ok=True)
for name in ("ic_launcher", "ic_launcher_round"):
    with open(f"{RES}/mipmap-anydpi-v26/{name}.xml", "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            # ⚠ No "- -" (closed up) anywhere in here: XML forbids it inside a
            # comment, and aapt REJECTS the file rather than warning. The em-dash
            # style used everywhere else in this repo breaks the build here.
            '<!-- Generated by tools/build_icon.py; do not hand-edit. -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
            '</adaptive-icon>\n')
print("wrote mipmap-anydpi-v26/*.xml")
