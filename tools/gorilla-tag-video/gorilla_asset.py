# -*- coding: utf-8 -*-
"""
Gorilla Tag "monke" head - procedural vector asset renderer.

Geometry measured directly off the reference skin screenshot on a 20 px grid
(source frame 1080x1918).  All lengths below are normalised by the face-plate
width FW = 318 source px:

    plate      318 x 192 px          -> 1.000 x 0.604
    eyes       135 x  72 px  @ +-86  -> 0.212 x 0.113 rx/ry, ex = +-0.267
    muzzle     160 x  97 px          -> hw 0.252, y in [-0.022, +0.283]
    hood       402 px wide, top -147 -> hw 0.632, top -0.462
    wing       base +0.626,-0.101 -> tip +1.066,-0.704

Rendered at SS x supersampling into 8-bit masks, composited in float, then
LANCZOS-downsampled: clean edges and alpha at any output size.
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SS = 4

# ---------------- palette (sampled from the reference) --------------------
HOOD_LIT   = (150,  56, 250)
HOOD_BASE  = (109,  26, 205)
HOOD_SHADE = ( 72,  14, 140)
HOOD_DEEP  = ( 44,   8,  90)
WING_LIT   = (198, 168, 246)
WING_BASE  = (150, 108, 216)
WING_EDGE  = ( 92,  56, 140)
FACE_LIT   = (214, 214, 216)
FACE_BASE  = (172, 172, 175)
FACE_SHADE = (118, 118, 123)
MUZ_LIT    = (222, 222, 224)
MUZ_BASE   = (184, 184, 187)
INK        = ( 10,  10,  12)
IRIS       = ( 34,  34,  40)
SPEC       = (253, 253, 255)

# ---------------- measured geometry (units of FW) -------------------------
G = dict(
    plate_h      = 0.604,
    brow_hw      = 0.500, brow_top = -0.318, brow_bot = -0.004, brow_r = 0.118,
    muz_hw       = 0.272, muz_top  = -0.060, muz_bot  = 0.336, muz_r  = 0.150,
    ink_w        = 0.036,
    eye_x        = 0.256, eye_y = -0.162, eye_rx = 0.216, eye_ry = 0.114,
    iris_rx      = 0.118, iris_ry = 0.082,
    spec_rx      = 0.062, spec_ry = 0.046, spec_dx = -0.030, spec_dy = -0.020,
    nos_x        = 0.062, nos_y = 0.075, nos_rx = 0.030, nos_ry = 0.020,
    mouth_hw     = 0.096, mouth_hh = 0.031, mouth_y = 0.252,
    hood_hw      = 0.632, hood_cy = -0.012, hood_hh = 0.470,
    wing_bx      = 0.585, wing_by = -0.110, wing_len = 0.790, wing_ang = 22.0,
)


# ---------------- drawing helpers -----------------------------------------
def _L(w, h):
    return Image.new('L', (w, h), 0)


def _rr(d, cx, cy, hw, hh, r, v=255):
    hw = max(hw, 0.6); hh = max(hh, 0.6)
    d.rounded_rectangle([cx - hw, cy - hh, cx + hw, cy + hh],
                        radius=max(1, min(int(r), int(min(hw, hh)))), fill=v)


def _el(d, cx, cy, rx, ry, v=255):
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=v)


def _rot_el(d, cx, cy, rx, ry, deg, v=255, n=120):
    a = np.radians(deg); ca, sa = np.cos(a), np.sin(a)
    pts = []
    for i in range(n):
        t = 2 * np.pi * i / n
        x, y = rx * np.cos(t), ry * np.sin(t)
        pts.append((cx + x * ca - y * sa, cy + x * sa + y * ca))
    d.polygon(pts, fill=v)


def _feather(d, ox, oy, ang_deg, length, width, v=255, curl=0.14):
    """rounded angel-wing feather: fat base, round tip, slight curl."""
    a = np.radians(ang_deg)
    ux, uy = np.cos(a), np.sin(a)
    nx, ny = -uy, ux
    R, Lft = [], []
    N = 36
    for i in range(N + 1):
        t = i / N
        w = width * (0.62 + 0.38 * np.sin(np.pi * min(1.0, 0.10 + t * 0.90)) ** 0.55)
        w *= (1.0 - t ** 4.2) ** 0.55
        bend = curl * length * (t ** 1.9)
        px = ox + ux * length * t + nx * bend
        py = oy + uy * length * t + ny * bend
        R.append((px + nx * w, py + ny * w))
        Lft.append((px - nx * w, py - ny * w))
    d.polygon(R + Lft[::-1], fill=v)
    d.ellipse([ox - width * 0.72, oy - width * 0.72,
               ox + width * 0.72, oy + width * 0.72], fill=v)


def _mottle(shape, cell, seed, amp):
    h, w = shape
    rs = np.random.RandomState(seed)
    s = rs.rand(max(2, h // cell), max(2, w // cell)).astype(np.float32)
    n = np.asarray(Image.fromarray((s * 255).astype(np.uint8))
                   .resize((w, h), Image.BICUBIC), np.float32) / 255.0
    n = (n - n.mean()) / (n.std() + 1e-6)
    return np.clip(n * amp, -2.5 * amp, 2.5 * amp)


# ---------------- renderer -------------------------------------------------
def render_head(face_w=400, turn=0.0, wings=True, body=True, mottle=True,
                mode='full', flat=(120, 30, 210), accent=(250, 250, 255),
                stroke=3.0, hood_sx=1.0, hood_sy=1.0, hood_dy=0.0,
                canvas=3.10):
    """face_w: face-plate width in output px.  turn: yaw in [-1, 1]."""
    FW = float(face_w) * SS
    W = int(FW * canvas)
    H = int(FW * canvas)
    cx, cy = W * 0.5, H * 0.470                 # plate centre
    g = {k: v * FW for k, v in G.items() if k not in ('wing_ang',)}
    g['wing_ang'] = G['wing_ang']

    tx = turn * FW * 0.085                      # plate slides inside the hood
    sq = 1.0 - 0.17 * abs(turn)                 # and foreshortens

    keys = ('wing', 'hood', 'hoodrim', 'ink', 'grey', 'muz',
            'nose', 'eye', 'iris', 'spec', 'dark')
    lay = {k: _L(W, H) for k in keys}
    d = {k: ImageDraw.Draw(v) for k, v in lay.items()}

    # ---- wings ----
    hrx_w = g['hood_hw'] * hood_sx
    hry_w = g['hood_hh'] * 1.06 * hood_sy
    hcy_w = cy + g['hood_cy'] + hood_dy * FW
    if wings:
        for side in (-1, 1):
            bx = cx + side * hrx_w * 0.84 + tx * 0.35
            by = hcy_w + hry_w * 0.10
            # (deviation from the wing axis, length, width)
            fan = [(-12, 0.76, 0.118), (2, 0.94, 0.130), (16, 0.98, 0.130),
                   (31, 0.88, 0.118), (46, 0.68, 0.098)]
            for k, (dev, ln, wd) in enumerate(fan):
                ang = -90 + side * (g['wing_ang'] + dev)
                ox = bx + side * k * hrx_w * 0.040
                oy = by + k * hrx_w * 0.052
                L = hrx_w * 0.96 * ln
                # outline then fill, feather by feather, so each blade stays
                # visually separate instead of fusing into one lavender mass
                _feather(d['wing'], ox, oy, ang, L, hrx_w * wd * 1.30, 128,
                         curl=0.11 * side)
                _feather(d['wing'], ox, oy, ang, L, hrx_w * wd, 255,
                         curl=0.11 * side)

    # ---- hood + body ----
    hcx = cx + turn * FW * 0.012
    hrx = g['hood_hw'] * hood_sx
    hry = g['hood_hh'] * 1.06 * hood_sy
    hcy = cy + g['hood_cy'] + hood_dy * FW
    _el(d['hood'], hcx, hcy, hrx, hry)
    # collar: a short neck that reads as the hoodie opening, never a torso blob
    _rr(d['hood'], hcx, hcy + hry * 0.72, hrx * 0.74, hry * 0.42, FW * 0.20)
    if body:
        _rr(d['hood'], hcx, hcy + hry * 1.30, hrx * 1.02, hry * 0.55, FW * 0.24)
    _el(d['hoodrim'], hcx, hcy, hrx * 1.028, hry * 1.062)

    # ---- face plate (brow block U muzzle capsule) ----
    def plate(target, inset):
        bt, bb = g['brow_top'] + inset, g['brow_bot']
        _rr(target, cx + tx, cy + (bt + bb) * 0.5,
            (g['brow_hw'] - inset) * sq, (bb - bt) * 0.5,
            g['brow_r'] - inset * 0.5)
        mt, mb = g['muz_top'], g['muz_bot'] - inset
        _rr(target, cx + tx, cy + (mt + mb) * 0.5,
            (g['muz_hw'] - inset) * sq, (mb - mt) * 0.5,
            g['muz_r'] - inset * 0.5)

    plate(d['ink'], 0.0)
    plate(d['grey'], g['ink_w'])

    # muzzle highlight
    _rr(d['muz'], cx + tx, cy + (g['muz_top'] + g['muz_bot']) * 0.5 + FW * 0.012,
        (g['muz_hw'] - g['ink_w'] * 1.9) * sq,
        (g['muz_bot'] - g['muz_top']) * 0.5 - g['ink_w'] * 1.7,
        g['muz_r'] - g['ink_w'])

    # ---- eyes ----
    for side in (-1, 1):
        px = cx + tx + side * g['eye_x'] * sq + turn * FW * 0.030
        py = cy + g['eye_y']
        _rot_el(d['eye'], px, py, g['eye_rx'] * sq, g['eye_ry'], side * 5.0)
        icx = px + side * g['eye_rx'] * 0.055 * sq + turn * FW * 0.035
        icy = py + g['eye_ry'] * 0.055
        _el(d['iris'], icx, icy, g['iris_rx'] * sq, g['iris_ry'])
        _rot_el(d['spec'], icx + g['spec_dx'] * sq, icy + g['spec_dy'],
                g['spec_rx'] * sq, g['spec_ry'], -20)

    # ---- nose shadow band + nostrils + mouth ----
    _rr(d['nose'], cx + tx, cy + g['nos_y'] - FW * 0.030,
        g['muz_hw'] * 0.66 * sq, FW * 0.052, FW * 0.030)
    for side in (-1, 1):
        _el(d['dark'], cx + tx + side * g['nos_x'] * sq, cy + g['nos_y'],
            g['nos_rx'] * sq, g['nos_ry'])
    _rr(d['dark'], cx + tx, cy + g['mouth_y'], g['mouth_hw'] * sq,
        g['mouth_hh'], FW * 0.010)

    # ================= colourise =================
    # Masks are drawn supersampled, then box-filtered down to the output size
    # BEFORE any colour math: identical anti-aliasing, ~SS^2 less float work.
    ow, oh = W // SS, H // SS

    def A(l):
        return (np.asarray(l.resize((ow, oh), Image.BOX), np.float32) / 255.0)

    ink_blur = lay['ink'].filter(ImageFilter.GaussianBlur(FW * 0.045))
    a_ink_blur = A(ink_blur)

    fw = FW / SS
    cxo, cyo = cx / SS, cy / SS
    yy, xx = np.mgrid[0:oh, 0:ow].astype(np.float32)
    gy = np.clip((yy - (cyo - fw * 0.95)) / (fw * 2.30), 0, 1)
    gx = np.clip((xx - (cxo - fw * 1.25)) / (fw * 2.50), 0, 1)
    key = np.clip(1.0 - (((xx - (cxo - fw * 0.26)) / (fw * 1.15)) ** 2 +
                         ((yy - (cyo - fw * 0.55)) / (fw * 1.20)) ** 2), 0, 1)

    def grad(c0, c1, b=0.72):
        a = np.array(c0, np.float32); bb = np.array(c1, np.float32)
        t = np.clip(gy * b + gx * (1 - b), 0, 1)[..., None]
        return (a * (1 - t) + bb * t) / 255.0

    rgb = np.zeros((oh, ow, 3), np.float32)
    alpha = np.zeros((oh, ow), np.float32)

    def over(col, m):
        m3 = m[..., None]
        np.copyto(rgb, rgb * (1 - m3) + col * m3)
        np.copyto(alpha, alpha * (1 - m) + m)

    nh = _mottle((oh, ow), max(2, int(fw * 0.16)), 7, 0.013) if mottle else None
    nf = _mottle((oh, ow), max(2, int(fw * 0.12)), 13, 0.017) if mottle else None

    a_ink, a_grey = A(lay['ink']), A(lay['grey'])

    wraw = np.asarray(lay['wing'].resize((ow, oh), Image.BOX), np.float32)
    w_all = np.clip(wraw / 128.0, 0, 1)
    w_fill = np.clip((wraw - 128.0) / 127.0, 0, 1)
    over(np.array(WING_EDGE, np.float32) / 255.0, w_all)
    over(grad(WING_LIT, WING_BASE, 0.62), w_fill)

    over(np.array(HOOD_DEEP, np.float32) / 255.0, A(lay['hoodrim']))
    hc = grad(HOOD_BASE, HOOD_DEEP, 0.68)
    hc = hc + (np.array(HOOD_LIT, np.float32) / 255.0 - hc) * (key ** 1.25)[..., None] * 0.95
    if mottle:
        hc = np.clip(hc + nh[..., None], 0, 1)
    a_hood = A(lay['hood'])
    over(hc, a_hood)

    ring = np.clip(a_ink_blur - a_ink, 0, 1)
    over(np.array(HOOD_DEEP, np.float32) / 255.0, ring * 0.55 * a_hood)

    over(np.array(INK, np.float32) / 255.0, a_ink)
    fc = grad(FACE_LIT, FACE_SHADE, 0.74)
    if mottle:
        fc = np.clip(fc + nf[..., None], 0, 1)
    over(fc, a_grey)
    a_muz = A(lay['muz'])
    mc = grad(MUZ_LIT, MUZ_BASE, 0.74)
    if mottle:
        mc = np.clip(mc + nf[..., None] * 0.6, 0, 1)
    over(mc, a_muz)

    over(np.array(FACE_SHADE, np.float32) / 255.0 * 0.86,
         A(lay['nose']) * a_muz * 0.85)
    a_eye = A(lay['eye'])
    eclip = a_eye * a_grey
    over(np.array(INK, np.float32) / 255.0, eclip)
    over(np.array(IRIS, np.float32) / 255.0, A(lay['iris']) * eclip)
    over(np.array(SPEC, np.float32) / 255.0, A(lay['spec']) * eclip)
    over(np.array(INK, np.float32) / 255.0, A(lay['dark']))

    if mode != 'full':
        sil = np.clip(alpha, 0, 1)
        eyes = np.clip(A(lay['spec']) * 1.15 + A(lay['iris']) * 0.92, 0, 1) * \
            (A(lay['eye']) * A(lay['grey']))
        if mode == 'silhouette':
            rgb = np.zeros_like(rgb) + np.array(flat, np.float32) / 255.0
            rgb = rgb * (1 - eyes[..., None]) + \
                (np.array(accent, np.float32) / 255.0) * eyes[..., None]
            alpha = sil
        elif mode == 'outline':
            k = max(3, int(round(stroke)) | 1)

            def contour(m):
                im8 = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8))
                return np.clip(
                    np.asarray(im8.filter(ImageFilter.MaxFilter(k)), np.float32) / 255.0 -
                    np.asarray(im8.filter(ImageFilter.MinFilter(k)), np.float32) / 255.0,
                    0, 1)

            band = contour(sil)
            # face plate + muzzle contours, so the outline reads as a monke face
            band = np.clip(band + contour(a_ink) * 0.95 +
                           contour(a_muz) * 0.75, 0, 1)
            rgb = np.zeros_like(rgb) + np.array(accent, np.float32) / 255.0
            alpha = np.clip(band + eyes * 0.85, 0, 1)

    out = np.dstack([np.clip(rgb, 0, 1) * 255.0, np.clip(alpha, 0, 1) * 255.0])
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


def anchor(face_w=400, canvas=3.10):
    """((cx, cy), (W, H)) - face-plate centre inside the rendered image."""
    W = int(face_w * SS * canvas) // SS
    H = int(face_w * SS * canvas) // SS
    return (W * 0.5, H * 0.470), (W, H)


if __name__ == '__main__':
    for t, n in ((0.0, 'front'), (-0.8, 'left'), (0.8, 'right')):
        render_head(340, turn=t).save(f'asset/head_{n}.png')
    print('ok')
