# -*- coding: utf-8 -*-
"""Recolour Morty's yellow tee into the skin's purple hoodie.

Done as a hue rotation on a colour key, keeping the original luminance and the
original anti-aliased edges - so the shading, folds and line art all survive and
nothing is redrawn: there is no seam to go wrong.  A spatial gate plus a
connectivity test keeps the key off same-hue backgrounds.
"""

# Segments whose backgrounds are safely off-hue from the tee, verified frame by
# frame.  Everywhere else (yellow portals, blown-out explosions) the key cannot
# be trusted, so the tee is left alone rather than risking a speckled mess.
SEGMENTS = {2, 3, 4, 5, 6, 7, 14}

import cv2, numpy as np

HOOD_H = 132          # OpenCV hue for the skin's violet (~262 deg)
HOOD_S = 1.16         # saturation gain
HOOD_V = 0.80         # value gain (the hoodie is darker than the tee)


def shirt_mask(bgr, cx, cy, Hw):
    H, W = bgr.shape[:2]
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    h, s, v = (hsv[:, :, i].astype(np.int32) for i in range(3))
    key = ((h >= 19) & (h <= 36) & (s >= 105) & (v >= 135)).astype(np.uint8)

    gate = np.zeros((H, W), np.uint8)
    x0 = int(max(0, cx - 1.75 * Hw)); x1 = int(min(W, cx + 1.75 * Hw))
    y0 = int(max(0, cy + 0.30 * Hw)); y1 = int(min(H, cy + 4.2 * Hw))
    gate[y0:y1, x0:x1] = 1
    m = cv2.morphologyEx(key * gate, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))

    # keep only blobs that sit under the head (drops same-hue backgrounds)
    n, lab, st, ct = cv2.connectedComponentsWithStats(m, 8)
    out = np.zeros((H, W), np.uint8)
    for i in range(1, n):
        x, y, w, hh, area = st[i]
        if area < max(120, 0.03 * Hw * Hw):
            continue
        if abs(ct[i][0] - cx) > 1.5 * Hw:
            continue
        if ct[i][1] < cy + 0.35 * Hw or ct[i][1] > cy + 3.6 * Hw:
            continue
        if w > 3.0 * Hw or hh > 3.8 * Hw:      # a background wash, not a shirt
            continue
        out[lab == i] = 1
    return out


def apply(bgr, cx, cy, Hw, panel=True):
    m = shirt_mask(bgr, cx, cy, Hw)
    if m.sum() < 150:
        return bgr
    soft = cv2.GaussianBlur(m.astype(np.float32), (0, 0), 0.8)[..., None]
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV).astype(np.float32)
    out = hsv.copy()
    out[:, :, 0] = HOOD_H
    out[:, :, 1] = np.clip(hsv[:, :, 1] * HOOD_S, 0, 255)
    out[:, :, 2] = np.clip(hsv[:, :, 2] * HOOD_V, 0, 255)
    rec = cv2.cvtColor(out.astype(np.uint8), cv2.COLOR_HSV2BGR).astype(np.float32)
    res = bgr.astype(np.float32) * (1 - soft) + rec * soft

    if panel:
        # grey chest panel with 777, clipped to the shirt so it can never
        # spill onto the background
        H, W = bgr.shape[:2]
        pan = np.zeros((H, W), np.uint8)
        px, py = int(cx), int(cy + 1.55 * Hw)
        rx, ry = int(0.62 * Hw), int(0.72 * Hw)
        cv2.ellipse(pan, (px, py), (rx, ry), 0, 0, 360, 1, -1)
        pm = (pan * m).astype(np.float32)
        pm = cv2.GaussianBlur(pm, (0, 0), 0.8)[..., None]
        grey = np.zeros_like(res) + np.array([172, 172, 172], np.float32)
        lum = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)[..., None]
        grey = np.clip(grey * (0.72 + 0.55 * lum / 255.0), 0, 255)
        res = res * (1 - pm) + grey * pm
        if rx > 26:
            txt = np.zeros((H, W), np.uint8)
            sc = Hw / 190.0
            cv2.putText(txt, '777', (px - int(0.42 * Hw), py - int(0.10 * Hw)),
                        cv2.FONT_HERSHEY_DUPLEX, sc * 1.05, 1,
                        max(1, int(round(sc * 3))), cv2.LINE_AA)
            tm = cv2.GaussianBlur((txt * pan * m).astype(np.float32), (0, 0), 0.6)[..., None]
            res = res * (1 - tm) + np.array([28, 28, 30], np.float32) * tm
    return np.clip(res, 0, 255).astype(np.uint8)
