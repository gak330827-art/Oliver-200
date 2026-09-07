# -*- coding: utf-8 -*-
"""Composite the Gorilla Tag head over the tracked character.

Per solved frame:
  * the hood is sized from the tracked head box so it fully covers the original
    head - nothing of the old character can survive underneath (no ghost edges);
  * the asset is rendered in the visual language of the shot (full colour /
    flat silhouette / line-art outline);
  * it is graded into the shot by matching the LAB statistics of the pixels it
    covers, so it belongs to the same image as everything around it;
  * it is composited with its own anti-aliased alpha.

The gorilla keeps its native proportions; the whole head is stretched
vertically by STRETCH_Y so a wide gorilla hood covers a tall cartoon head
without distorting the face relative to the hood.
"""
import cv2, json, numpy as np, os, sys
from PIL import Image, ImageDraw
import gorilla_asset as ga

SOL = json.load(open('solve.json'))

CANVAS = 3.5          # asset canvas, in face-plate widths
REF = 480             # base render size; every frame is a resize of it
STRETCH_Y = 1.28      # vertical stretch of the whole head
HEAD_AR = 1.22        # tracked head height / width
FW_K = 0.98           # face-plate width, in head widths
HOOD_DY = -0.02

# per-style grading strength: (luma, chroma)
GRADE = {'toon': (0.38, 0.16), 'dark': (0.86, 0.46)}
# The patch always covers the same eye - a character does not swap eyes between
# shots.  A couple of source shots are mirrored footage, so their patch sits on
# the other side there; we ignore that and keep the gorilla consistent, since
# the gorilla head replaces the original one completely anyway.
PATCH_DEFAULT = -1
PATCH_SIDE = {}

# per-segment nudges: (dx, dy in head widths, scale multiplier), measured on
# 1:1 composites where a sliver of the original head was still showing
TWEAK = {
    2:  (0.03, -0.11, 1.12),
    5:  (0.17,  0.00, 1.14),
    14: (-0.06, -0.02, 1.10),
    23: (0.00,  0.00, 1.12),
    32: (0.00,  0.00, 1.12),
}

BASE = {}


def base_assets():
    if BASE:
        return BASE
    for side in (-1, 1):
        kw = dict(hood_sx=1.0, hood_sy=1.0, hood_dy=HOOD_DY, canvas=CANVAS,
                  body=False, patch_side=side)
        BASE[('full', side)] = np.asarray(
            ga.render_head(REF, mode='full', **kw)).astype(np.float32)
        BASE[('sil', side)] = np.asarray(
            ga.render_head(REF, mode='silhouette', flat=(255, 255, 255),
                           accent=(0, 0, 0), **kw)).astype(np.float32)
        BASE[('line', side)] = np.asarray(
            ga.render_head(REF, mode='outline', accent=(255, 255, 255),
                           stroke=3.0, **kw)).astype(np.float32)
    (ax, ay), (AW, AH) = ga.anchor(REF, CANVAS)
    BASE['anchor'] = (ax / AW, ay / AH)
    return BASE


def scaled(kind, face_w, side):
    B = base_assets()[(kind, side)]
    w = max(24, int(round(face_w * CANVAS)))
    h = max(24, int(round(face_w * CANVAS * STRETCH_Y)))
    interp = cv2.INTER_AREA if w < B.shape[1] else cv2.INTER_CUBIC
    return cv2.resize(B, (w, h), interpolation=interp)


def tint_sil(a, flat, accent):
    """base silhouette (white body, black eyes) -> shot-coloured silhouette."""
    al = a[..., 3]
    eye = 1.0 - np.clip(a[..., :3].mean(2, keepdims=True) / 255.0, 0, 1)
    body = np.array(flat, np.float32).reshape(1, 1, 3)
    acc = np.array(accent, np.float32).reshape(1, 1, 3)
    return np.dstack([body * (1 - eye) + acc * eye, al])


def tint_line(a, accent):
    rgb = np.zeros_like(a[..., :3]) + np.array(accent, np.float32).reshape(1, 1, 3)
    return np.dstack([rgb, a[..., 3]])


def paste(dst, src, ox, oy):
    h, w = src.shape[:2]
    H, W = dst.shape[:2]
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(W, ox + w), min(H, oy + h)
    if x1 <= x0 or y1 <= y0:
        return dst
    s = src[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    a = s[..., 3:4] / 255.0
    dst[y0:y1, x0:x1] = dst[y0:y1, x0:x1] * (1 - a) + s[..., :3] * a
    return dst


def region_stats(bgr, ox, oy, rgba):
    h, w = rgba.shape[:2]
    H, W = bgr.shape[:2]
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(W, ox + w), min(H, oy + h)
    if x1 <= x0 or y1 <= y0:
        return None
    m = rgba[y0 - oy:y1 - oy, x0 - ox:x1 - ox, 3] > 40
    if m.sum() < 40:
        return None
    lab = cv2.cvtColor(bgr[y0:y1, x0:x1], cv2.COLOR_BGR2LAB).astype(np.float32)
    v = lab[m]
    return v.mean(0), v.std(0) + 1e-3


def grade(rgba, stats, kL, kC):
    if stats is None:
        return rgba
    tm, ts = stats
    a = rgba[..., 3]
    m = a > 8
    if m.sum() < 30:
        return rgba
    rgb = np.clip(rgba[..., :3], 0, 255).astype(np.uint8)
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    sm, ss = lab[m].mean(0), lab[m].std(0) + 1e-3
    out = lab.copy()
    tgt = (lab[..., 0] - sm[0]) / ss[0] * ts[0] + tm[0]
    out[..., 0] = lab[..., 0] * (1 - kL) + tgt * kL
    for c in (1, 2):
        t = (lab[..., c] - sm[c]) * min(1.0, ts[c] / ss[c]) + tm[c]
        out[..., c] = lab[..., c] * (1 - kC) + t * kC
    out = np.clip(out, 0, 255)
    rgb2 = cv2.cvtColor(out.astype(np.uint8), cv2.COLOR_LAB2RGB).astype(np.float32)
    return np.dstack([rgb2, a])


def fill_color(bgr, cx, cy, r):
    """median colour of the area the head covers - used to blank the original
    line-art head before drawing the gorilla outline over it."""
    H, W = bgr.shape[:2]
    x0, x1 = max(0, int(cx - r)), min(W, int(cx + r))
    y0, y1 = max(0, int(cy - r)), min(H, int(cy + r))
    p = bgr[y0:y1, x0:x1].reshape(-1, 3)
    if len(p) < 20:
        return (0, 0, 0)
    return tuple(int(v) for v in np.median(p, 0)[::-1])


def local_colors(bgr, cx, cy, r):
    H, W = bgr.shape[:2]
    x0, x1 = max(0, int(cx - r)), min(W, int(cx + r))
    y0, y1 = max(0, int(cy - r)), min(H, int(cy + r))
    p = bgr[y0:y1, x0:x1].reshape(-1, 3)
    if len(p) < 20:
        return (10, 10, 12), (240, 240, 245)
    lum = p.astype(np.float32) @ np.array([0.114, 0.587, 0.299], np.float32)
    lo = p[lum <= np.percentile(lum, 10)].mean(0)
    hi = p[lum >= np.percentile(lum, 98)].mean(0)
    return tuple(int(v) for v in lo[::-1]), tuple(int(v) for v in hi[::-1])


def build(f, bgr):
    v = SOL.get(str(f))
    if not v:
        return bgr
    cx, cy, Hw, style, seg = v['cx'], v['cy'], v['w'], v['style'], v['seg']
    dx, dy, ms = TWEAK.get(seg, (0.0, 0.0, 1.0))
    Hw *= ms
    cx += dx * Hw
    cy += dy * Hw
    FW = FW_K * Hw

    side = PATCH_SIDE.get(seg, PATCH_DEFAULT)
    if style == 'sil':
        flat, accent = local_colors(bgr, cx, cy, Hw * 0.75)
        # the character's eye reads as a white glow in these shots; keep it
        # bright rather than letting the scene's brightest hue take over
        accent = tuple(int(0.70 * 255 + 0.30 * v) for v in accent)
        rgba = tint_sil(scaled('sil', FW, side), flat, accent)
    elif style == 'line':
        _, accent = local_colors(bgr, cx, cy, Hw * 0.85)
        rgba = tint_line(scaled('line', FW, side), accent)
        # blank the original outline first, otherwise both heads are drawn
        blank = tint_sil(scaled('sil', FW, side),
                         fill_color(bgr, cx, cy, Hw * 0.55),
                         fill_color(bgr, cx, cy, Hw * 0.55))
    else:
        rgba = scaled('full', FW, side).copy()

    AH, AW = rgba.shape[:2]
    fx, fy = base_assets()['anchor']
    acx, acy = fx * AW, fy * AH
    ox = int(round(cx - acx))
    oy = int(round(cy + 0.10 * Hw - acy))

    if style in GRADE:
        kL, kC = GRADE[style]
        rgba = grade(rgba, region_stats(bgr, ox, oy, rgba), kL, kC)

    dst = bgr[:, :, ::-1].astype(np.float32)
    if style == 'line':
        dst = paste(dst, blank, ox, oy)
    dst = paste(dst, rgba, ox, oy)
    return np.clip(dst, 0, 255).astype(np.uint8)[:, :, ::-1]


def preview(path='probe/comp_preview.png'):
    segs = sorted({v['seg'] for v in SOL.values()})
    S, cols = 288, 5
    rows = (len(segs) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * S, rows * (S + 16)), (8, 8, 8))
    d = ImageDraw.Draw(sheet)
    for k, si in enumerate(segs):
        fs = sorted(int(f) for f, x in SOL.items() if x['seg'] == si)
        f = fs[len(fs) // 2]
        im = build(f, cv2.imread(f'frames/f{f+1:04d}.jpg'))
        x, y = (k % cols) * S, (k // cols) * (S + 16)
        sheet.paste(Image.fromarray(im[:, :, ::-1]).resize((S, S)), (x, y + 16))
        d.rectangle([x, y, x + S, y + 15], fill=(0, 0, 0))
        d.text((x + 4, y + 3), f"SEG{si} f{f} {SOL[str(f)]['style']}", fill=(255, 255, 255))
    sheet.save(path)
    print('preview ->', path)


if __name__ == '__main__':
    if sys.argv[1:2] == ['preview']:
        preview()
