"""
Rebuild the app icon, and the launcher assets, from the hand-drawn source.

⚠⚠ This exists because the source is a RASTER, not a vector: the horse was
supplied as flat black pixels on a purple badge, already cropped. Every
correction here is therefore a measurement against those pixels rather than a
number somebody chose, and the numbers are printed so a later change can be
checked rather than eyeballed.

    python tools/build_icon.py            # icon.png + app/src/main/res/mipmap-*

What it fixes, all measured on the input:

  * the badge was 640x640 sitting off-centre on a 703x676 canvas (margins 43/20
    left/right, 7/29 top/bottom) -- the icon is square and full-bleed now, with
    the rounded corners as transparency;
  * the muzzle came within 5 px of the badge edge, and the neck's flat cuts
    floated inside the badge where they read as the horse being chopped off.
    The placement now derives a scale that pushes both cuts PAST the frame --
    off the bottom and off the left -- while the muzzle keeps a real margin;
  * the eye sat at (397, 254) in badge coordinates -- behind the poll, which is
    to say on the neck rather than on the skull. It is redrawn under the ear
    tip, in the upper third of the head, where a horse's eye is.

⚠ Nothing here can un-crop the source. The neck's flat edges are baked into the
pixels; the only honest thing to do with them is push them off the frame.

⚠⚠ The ADAPTIVE icon is not the same composition scaled down. Android masks the
outer ~17% of an adaptive icon to whatever shape the launcher wants, so the
40px muzzle margin that looks generous in the square icon is INSIDE the crop --
a circular mask would cut the nose off. The foreground layer therefore keeps
the whole head inside the 72/108dp safe zone and lets only the neck bleed.
"""

from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage
import os
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else "icon-source.png"
RES = "app/src/main/res"
SS = 4                       # supersampling for the drawn shapes

# --- read the source -------------------------------------------------------
a = np.array(Image.open(SRC).convert("RGBA")).astype(int)
rgb_all = a[..., :3]
page = np.array([9, 2, 28])                     # the flat page behind the badge
is_badge = np.abs(rgb_all - page).sum(axis=2) > 20
ys, xs = np.nonzero(is_badge)
BX0, BY0, BX1, BY1 = xs.min(), ys.min(), xs.max(), ys.max()
B = BX1 - BX0 + 1
assert B == BY1 - BY0 + 1, f"badge is not square: {B}x{BY1-BY0+1}"
badge = rgb_all[BY0:BY0 + B, BX0:BX0 + B]
print(f"badge {B}x{B} at ({BX0},{BY0})")

# ⚠ The old eye is a HOLE in the black; fill it, or it reappears as a grey
# smudge wherever the silhouette is redrawn.
lum_src = badge.sum(axis=2)
horse = ndimage.binary_fill_holes(lum_src < 25)
ys, xs = np.nonzero(horse)
HX0, HX1, HY0, HY1 = xs.min(), xs.max(), ys.min(), ys.max()
HW, HH = HX1 - HX0 + 1, HY1 - HY0 + 1
print(f"silhouette {HW}x{HH}  margins L{HX0} R{B-1-HX1} T{HY0} B{B-1-HY1}")

# The corner radius the source drew, as a fraction of the edge.
CORNER = np.nonzero(is_badge[BY0, BX0:BX0 + B])[0].min() / B
print(f"corner radius {CORNER*B:.0f}/{B} = {CORNER*100:.1f}%")

# --- the badge gradient, as the plane it is --------------------------------
# ⚠ Refitted rather than inpainted: the horse moves, so the pixels it used to
# cover have to come from somewhere. The residuals below are under one 8-bit
# step, so this reproduces the original gradient rather than approximating it.
gy, gx = np.nonzero(is_badge[BY0:BY0 + B, BX0:BX0 + B] & ~horse)
A = np.c_[gx, gy, np.ones(len(gx))]
COEFS = []
for i, ch in enumerate("RGB"):
    c, *_ = np.linalg.lstsq(A, badge[gy, gx, i], rcond=None)
    COEFS.append(c)
    print(f"  gradient {ch}: resid_std={np.std(badge[gy, gx, i] - A @ c):.2f}")

# ⚠⚠ The source gradient is TOO DARK to hold a black silhouette. Measured
# against pure black, its bright corner is 1.55:1 and its dark corner **1.04:1**
# -- which is to say the muzzle, which lands in that corner, was drawn in black
# on very nearly black. That is the whole of the "contrast is not good" problem,
# and no amount of repositioning fixes it.
#
# ⇒ The gradient's DIRECTION is kept (it is the source's own, fitted above) and
# only its colours are replaced, with endpoints chosen so even the dark corner
# clears 2.5:1. The ratios are printed on every run rather than trusted.
GRAD_LIGHT = np.array([155.0, 120.0, 242.0])     # the light corner, top-left
GRAD_DARK = np.array([103.0, 68.0, 180.0])       # the dark corner, bottom-right


def contrast(c):
    """WCAG contrast of a colour against the pure black of the silhouette."""
    ch = np.asarray(c, float) / 255.0
    ln = np.where(ch <= 0.04045, ch / 12.92, ((ch + 0.055) / 1.055) ** 2.4)
    return (float(ln @ [0.2126, 0.7152, 0.0722]) + 0.05) / 0.05


print(f"  contrast against the silhouette: light {contrast(GRAD_LIGHT):.2f}:1, "
      f"dark {contrast(GRAD_DARK):.2f}:1  (the source was 1.55:1 and 1.04:1)")
assert contrast(GRAD_DARK) >= 2.5, "the dark corner would swallow the silhouette"


# ⚠⚠ A SOFT silhouette, recovered from the source's own antialiasing.
#
# `horse` above is a hard threshold, and thresholding throws away the half-lit
# pixels along every edge. Scaled up 1.5x for a 1024 icon that shows as visible
# stair-steps on the neck's long diagonal -- the mark stops looking drawn and
# starts looking traced.
#
# The source's edge pixels are a blend of black over the badge gradient, so the
# blend factor is recoverable exactly: alpha = 1 - lum/lum_background, using the
# plane fitted above as the background. ⚠ The old eye is bright, which would
# read as alpha 0, so the filled hole is forced back to solid.
_plane = sum(COEFS[i][0] * np.arange(B)[None, :] + COEFS[i][1] * np.arange(B)[:, None]
             + COEFS[i][2] for i in range(3))
HORSE_A = np.clip(1.0 - lum_src / np.maximum(_plane, 1.0), 0.0, 1.0)
HORSE_A[horse] = 1.0                              # solid interior, eye hole included
# ⚠⚠ …and ZERO everywhere that is not actually an edge. The plane is a fit, not
# the pixels, so out in the open badge `lum/plane` wobbles by a few parts in a
# hundred and the alpha comes out at 0.0-0.05 instead of 0. Invisible on its own
# -- but the mask is pasted as a rectangle, so it darkened that whole rectangle
# by a hair and drew a visible seam down the badge where the rectangle ended.
# The real edge is within a pixel or two of the threshold; nothing else counts.
_edge = ndimage.binary_dilation(horse, iterations=2) & ~horse
HORSE_A[~(horse | _edge)] = 0.0
print(f"  soft edge: {np.count_nonzero((HORSE_A > 0.02) & (HORSE_A < 0.98))} "
      f"partially covered pixels recovered from the source")


# ⚠⚠ **The two cut edges are EXTENDED, so placement stops being a hostage.**
#
# The bust ends in a flat bottom edge (row 583, x 15..302) and a flat left one
# (column 0, y 379..532). Those cuts have to leave the frame or they read as a
# chopped-off horse -- which pinned the silhouette to two edges, and pinning it
# is what made every other request contradictory: zooming out for more
# background moved the cuts INTO view, so the horse could not shrink.
#
# The cuts are straight and their sides are near-vertical/near-horizontal, so
# extruding them is faithful rather than invented: the neck simply continues the
# way it was already going. With that, the horse can be placed and scaled
# freely and the extrusion always reaches the frame.
#
# ⚠ The corner between the two extrusions has to be closed too, or a notch of
# background sits in it. Columns are extended down only as far right as the
# bottom cut actually reaches -- past that the outline is the real sloping edge
# under the jaw, and extending there would fill the space beneath the head.
_bottom_cut = np.nonzero(horse[HY1, :])[0]
_left_cut = np.nonzero(horse[:, HX0])[0]
PADL, PADB = HW, HH                       # generous: any scale, any placement
EXT_A = np.zeros((HH + PADB, HW + PADL))
EXT_A[0:HH, PADL:PADL + HW] = HORSE_A[HY0:HY1 + 1, HX0:HX1 + 1]

# ⚠⚠ The left extension FOLLOWS THE NECK'S OWN LINE, it is not a straight bar.
# Extruding the cut horizontally -- the obvious thing -- put a black shelf
# across the bottom-left of the badge that read as a slab, not a neck. The
# crest is already descending as it runs back: fitted over the 80 px next to
# the cut it drops 0.575 px for every px leftward, so continuing at that slope
# is the drawing's own direction rather than an invention.
_slope = -np.polyfit(np.arange(80),
                     [np.nonzero(horse[:, HX0 + x])[0].min() for x in range(80)], 1)[0]
_crest = _left_cut.min() - HY0
for i, x in enumerate(range(PADL - 1, -1, -1)):
    EXT_A[int(round(_crest + _slope * (i + 1))):, x] = 1.0

# …and the THROAT continues downward on its own lean, for the same reason the
# crest does. Extruding the bottom cut straight down left a dead-vertical wall
# running a third of the icon's height, which next to these curves reads as a
# mistake. Fitted over the 80 rows above the cut, the throat draws in by 0.212 px
# per px, so it keeps doing that.
_throat = -np.polyfit(np.arange(HY1 - 79, HY1 + 1),
                      [np.nonzero(horse[y, :HX0 + 400])[0].max()
                       for y in range(HY1 - 79, HY1 + 1)], 1)[0]
# ⚠⚠ The CORNER between the two cuts has to be closed first. The left cut ends
# at row 532 but the bottom cut only starts at x=15, so source columns 0..14 stop
# short and leave a notch of background inside the neck -- 416 px of it, found by
# looking for interior holes rather than by eye.
for x in range(PADL, PADL + _bottom_cut.min()):
    col = np.nonzero(EXT_A[:HH, x] > 0.5)[0]
    if len(col):
        EXT_A[col.max():HH, x] = 1.0

_throat_x = _bottom_cut.max()
for i, y in enumerate(range(HH, HH + PADB)):
    xr = int(round(PADL + _throat_x - _throat * (i + 1)))
    if xr <= 0:
        break
    EXT_A[y, 0:xr + 1] = 1.0
# ⚠ Whatever is still enclosed is background trapped inside the neck, and the
# joins above leave slivers of it where the soft edge falls below the threshold.
# ⚠⚠ Safe only because the eye is drawn LATER, as its own layer -- run against a
# mask that already had the eye in it, this would fill the eye in solid.
_solid = ndimage.binary_fill_holes(EXT_A > 0.5)
_closed = np.count_nonzero(_solid & (EXT_A <= 0.5))
EXT_A = np.maximum(EXT_A, _solid.astype(float))
print(f"  extended: crest continues left at {_slope:.3f} px/px, "
      f"throat draws in at {_throat:.3f} px/px, {_closed} px of trapped "
      f"background closed")


def gradient(size):
    """The source's gradient direction, in colours that can hold a silhouette."""
    yy, xx = np.mgrid[0:size, 0:size].astype(float)
    # ⚠ Driven by the summed plane, not one channel: that is what carries the
    # direction, and remapping per channel would rotate the hue across the badge.
    lum = sum(COEFS[i][0] * xx + COEFS[i][1] * yy for i in range(3))
    t = (lum - lum.min()) / (lum.max() - lum.min())      # 1 = the light corner
    return GRAD_DARK + t[..., None] * (GRAD_LIGHT - GRAD_DARK)

# --- the eye, where a horse's eye goes -------------------------------------
# ⚠⚠ Solved against the silhouette, not placed by hand, and wrong twice before
# this: the source had it behind the poll (on the NECK); searching for maximum
# clearance alone walks back onto the neck crest, which is thicker and just as
# wrong; and anchoring 30% of the way down the muzzle puts it where the skull
# is too narrow to hold the glow, which then spills onto the badge.
#
# ⇒ Anchor on the EAR TIP -- the one landmark on this outline that cannot be
# mistaken for anything else -- and take the deepest point of the upper third
# of the head beneath it. That is where the eye belongs on a horse and the only
# part of the skull with room for its glint.
EAR_X = int(np.median(xs[ys == ys.min()]))
_dist = ndimage.distance_transform_edt(horse)
_best = None
for x in range(EAR_X - 15, EAR_X + 26):
    col = np.nonzero(horse[:, x])[0]
    if not len(col):
        continue
    # ⚠ The head run, not the whole column: the ears are a separate run above
    # it, and including them would put the eye between the ear tips.
    runs, s0, prev = [], col[0], col[0]
    for v in col[1:]:
        if v != prev + 1:
            runs.append((s0, prev))
            s0 = v
        prev = v
    runs.append((s0, prev))
    top, bot = max(runs, key=lambda r: r[1] - r[0])
    for frac in np.arange(0.22, 0.38, 0.01):
        y = int(round(top + frac * (bot - top)))
        if _best is None or _dist[y, x] > _best[2]:
            _best = (x, y, _dist[y, x])
EYE_X, EYE_Y, _ = _best
print(f"ear tip x={EAR_X}, eye at badge ({EYE_X},{EYE_Y})")


# ⚠ The HEAD, as distinct from the neck: everything forward of the throat. It is
# the part that has to survive an adaptive icon's mask, and the part a launcher
# icon is actually of.
HEAD = np.array(np.nonzero(horse[:, 330:])).T + [0, 330]
_HC = np.array([(HEAD[:, 1].min() + HEAD[:, 1].max()) / 2,
                (HEAD[:, 0].min() + HEAD[:, 0].max()) / 2])
_HR = np.hypot(*(HEAD[:, ::-1] - _HC).T).max()      # head radius about its centre
print(f"head spans x {HEAD[:, 1].min()}..{HEAD[:, 1].max()}, "
      f"y {HEAD[:, 0].min()}..{HEAD[:, 0].max()}; radius {_HR:.0f} about its centre")


# ⚠⚠⚠ **This is the number that moves the LAUNCHER icon.** `SQUARE`'s
# `right_margin` only affects the standalone icon.png; the adaptive layer the
# launcher actually draws is solved by `fit_head_to_mask`, which ignores margins
# entirely and centres the head's own bounding circle. Changing the square
# margins to move the launcher icon does nothing at all, and did — 2026-09-10.
#
# ⚠ Positive is RIGHT, as a fraction of the layer. The head is centred by its
# bounding CIRCLE, but the neck runs off to the lower-left, so the visual mass
# sits left of that centre and the horse reads as off to one side. This nudges
# the drawn result back. ⚠⚠ It eats into the mask clearance — the script prints
# "furthest head pixel N from centre" and refuses past the safe radius, so a
# nudge that would clip the muzzle fails the build rather than shipping.
HEAD_NUDGE_X = 0.105

# ⚠ Lowered from 0.90 to buy back the clearance the nudge spends.
def fit_head_to_mask(size, fill=0.86):
    """
    ⚠⚠ Placement for an ADAPTIVE icon, SOLVED rather than chosen.

    A launcher may mask the 108dp layer to a circle of 72dp, so anything further
    than 72/108/2 of the layer from its centre CAN BE CUT OFF. Hand-picked
    margins kept missing it -- the muzzle measured 146px against a 144px radius,
    which is exactly why the nose was truncated on a real phone while every
    square preview looked fine.

    ⇒ Centre the HEAD on the frame centre and scale so its own bounding radius
    is `fill` of the mask radius. The neck then runs off to the bottom-left and
    is masked away, which is what it is for.
    """
    safe = size * (72 / 108) / 2.0
    s = fill * safe / _HR
    ox = size / 2.0 - _HC[0] * s + HX0 * s + HEAD_NUDGE_X * size
    oy = size / 2.0 - _HC[1] * s + HY0 * s
    return s, ox, oy, safe


def compose(size, background, rounded, width=None, right_margin=None,
            top_margin=None, fit_mask=False):
    """
    One composition, at `size` px. Fractions of the edge, or `fit_mask` to solve
    the placement against a launcher's circular crop.

    ⭐ Now that the cuts are extruded, the things that actually matter are the
    inputs: how big the horse is, how much air the muzzle gets, and how much
    sits above the ears. The extrusion covers whatever is left over.
    """
    if fit_mask:
        s, ox, oy, safe = fit_head_to_mask(size)
    else:
        s = width * size / HW
        ox = size * (1 - right_margin) - HW * s      # source HX0 lands here
        oy = top_margin * size
        safe = size * (72 / 108) / 2.0
    w, h = HW * s, HH * s
    assert ox - PADL * s < 0, "the left extrusion does not reach the frame"
    assert oy + (HH + PADB) * s > size, "the bottom extrusion does not reach it"

    if background:
        out = gradient(size)
        base_alpha = np.full((size, size), 255.0)
    else:
        out = np.zeros((size, size, 3))
        base_alpha = np.zeros((size, size))

    mask = Image.fromarray((EXT_A * 255).round().astype(np.uint8))
    mask = mask.resize((max(1, round((HW + PADL) * s)),
                        max(1, round((HH + PADB) * s))), Image.LANCZOS)
    canvas = Image.new("L", (size, size), 0)
    # ⚠ The paste origin is the EXTENDED canvas's corner, which sits up and left
    # of the horse itself by the padding.
    canvas.paste(mask, (round(ox - PADL * s), round(oy)))
    m = np.array(canvas).astype(float) / 255.0
    out = out * (1 - m[..., None])                       # the horse is pure black
    alpha = np.maximum(base_alpha, m * 255.0)

    # the eye, through the same transform
    ex, ey = ox + (EYE_X - HX0) * s, oy + (EYE_Y - HY0) * s
    clear = ndimage.distance_transform_edt(np.array(canvas) > 128)[round(ey), round(ex)]
    # ⚠⚠ A clean star, and NO glow.
    #
    # The source drew the eye as a small star inside a wide radial bloom. At icon
    # size that bloom is not light, it is a grey smear that eats the star's own
    # edges -- it reads as a strobe rather than as an eye, and it was the only
    # soft thing in an otherwise hard-edged mark.
    #
    # ⇒ The star is drawn alone, and BIGGER: sized from the clearance so it fills
    # the skull it sits on instead of floating in it. Nothing is composited over
    # it, so its edges stay as sharp as the supersampling can make them.
    R = min(size * 0.075, clear * 0.72)
    assert R > size * 0.012, f"no room for the eye at {size}px"

    star = Image.new("L", (size * SS, size * SS), 0)
    # ⚠ The waist sets how star-like it reads: 0.5 would be a plain diamond,
    # 0.10 a thin cross. 0.17 keeps four clean points with body behind them.
    k = R * 0.17
    pts = [(R, 0), (k, k), (0, R), (-k, k), (-R, 0), (-k, -k), (0, -R), (k, -k)]
    ImageDraw.Draw(star).polygon(
        [((ex + px) * SS, (ey + py) * SS) for px, py in pts], fill=255)
    light = np.array(star.resize((size, size), Image.LANCZOS)).astype(float) / 255.0

    out = out * (1 - light[..., None]) + 255.0 * light[..., None]
    alpha = np.maximum(alpha, light * 255.0)

    if rounded:
        rr = Image.new("L", (size * SS, size * SS), 0)
        ImageDraw.Draw(rr).rounded_rectangle(
            [0, 0, size * SS - 1, size * SS - 1],
            radius=round(CORNER * size) * SS, fill=255)
        alpha = np.minimum(alpha, np.array(rr.resize((size, size), Image.LANCZOS)))

    # ⚠⚠ The safe-radius line is the one that matters for the launcher: an
    # adaptive icon may be masked to a circle of 72/108 of the layer, so
    # anything further than that from the centre CAN BE CUT OFF. The muzzle was
    # 146px against a 144px radius, which is exactly why the nose was truncated
    # on a real phone while every square preview looked fine.
    # ⚠⚠ Measured over EVERY head pixel, not two landmarks I picked. The first
    # version of this line computed the muzzle and the ear from the wrong y and
    # printed the same number for both -- a check that agreed with itself.
    half = size / 2.0
    hp = (HEAD[:, ::-1] - [HX0, HY0]) * s + [ox, oy]
    worst = np.hypot(hp[:, 0] - half, hp[:, 1] - half).max()
    print(f"  {size:4d}px  scale {s:.3f}  width {w:.0f}  muzzle margin {size-(ox+w):.0f}"
          f"  top {oy:.0f}  eye r{R:.0f}")
    if fit_mask:
        # ⚠ Only meaningful for the masked layer. The square icon is never
        # cropped by a launcher, so reporting it there would be a scary word
        # about a thing that cannot happen.
        assert worst <= safe, (
            f"the head reaches {worst:.0f}px from centre but the mask keeps only "
            f"{safe:.0f} -- lower HEAD_NUDGE_X or `fill`")
        print(f"          furthest head pixel {worst:.0f} from centre; a circular "
              f"mask keeps {safe:.0f}  {'OK' if worst <= safe else 'TRUNCATED'}")
        assert worst <= safe, "the launcher would cut the head"
    return Image.fromarray(
        np.dstack([np.clip(out, 0, 255), alpha]).astype(np.uint8))


# --- 1. the standalone icon ------------------------------------------------
# Margins that look right in a square badge: the muzzle is the subject, so it
# gets real air; the neck runs off two edges.
# ⚠ `right_margin` 0.12 -> 0.075: the horse read as sitting LEFT of centre
# in the launcher, reported from the phone 2026-09-10. The neck runs off the
# left edge by design, so the optical centre is further right than the
# bounding box suggests — trimming the right margin is what moves the SUBJECT
# (the muzzle) toward the middle.
SQUARE = dict(width=0.70, right_margin=0.075, top_margin=0.17)
# ⚠ The adaptive layer takes no margins at all: they are solved against the
# launcher's crop, which is the only thing that decides whether the nose
# survives on a real phone.
print("icon.png:")
icon = compose(1024, background=True, rounded=True, **SQUARE)
# ⚠ Written to a temp file and renamed. Saving straight over icon.png fails with
# "OSError: [Errno 22] Invalid argument" whenever anything on Windows still has
# the previous file open -- a preview pane is enough - and the run is otherwise
# wasted. os.replace is atomic and does not care.
icon.save("icon.png.tmp", format="PNG")
os.replace("icon.png.tmp", "icon.png")

# --- 2. the launcher assets ------------------------------------------------
# ⚠⚠ Adaptive icons are 108dp with only the middle 72dp guaranteed visible --
# the launcher masks the rest to a circle, a squircle or whatever it likes. So
# the foreground layer needs the whole HEAD inside 66.7% of the frame, which is
# a much bigger margin than the square icon wants. Sharing one number between
# the two would either crop the muzzle here or float the horse there.
ADAPTIVE = dict(fit_mask=True)

print("launcher (adaptive 432 = 108dp @ xxxhdpi):")
os.makedirs(f"{RES}/mipmap-xxxhdpi", exist_ok=True)
compose(432, background=False, rounded=False, **ADAPTIVE) \
    .save(f"{RES}/mipmap-xxxhdpi/ic_launcher_foreground.png")

# The background layer is the gradient alone, full bleed: the system crops it.
Image.fromarray(np.dstack([gradient(432), np.full((432, 432), 255.0)])
                .astype(np.uint8)) \
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
