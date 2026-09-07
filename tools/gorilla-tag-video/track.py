# -*- coding: utf-8 -*-
"""Template tracker: seeds from one hand-measured anchor per shot and follows the
head through the shot with multi-scale NCC, then smooths the trajectory."""
import cv2, numpy as np, json, sys
from hints import HINTS, RANGE_OVERRIDE, SKIP

SEG = [tuple(x) for x in json.load(open('segs.json'))['segs']]
FW, FH = 576, 576


def load(f):
    return cv2.imread(f'frames/f{f+1:04d}.jpg')


def prep(bgr):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    g = cv2.GaussianBlur(g, (0, 0), 1.0)
    # local contrast normalisation: robust to the heavy per-shot grading
    m = cv2.GaussianBlur(g, (0, 0), 21.0)
    s = np.sqrt(np.maximum(cv2.GaussianBlur((g - m) ** 2, (0, 0), 21.0), 1.0))
    return np.clip((g - m) / s, -4, 4).astype(np.float32)


def patch(img, cx, cy, w, h):
    x0, y0 = int(round(cx - w / 2)), int(round(cy - h / 2))
    out = np.zeros((h, w), np.float32)
    sx0, sy0 = max(0, x0), max(0, y0)
    sx1, sy1 = min(img.shape[1], x0 + w), min(img.shape[0], y0 + h)
    if sx1 <= sx0 or sy1 <= sy0:
        return out
    out[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = img[sy0:sy1, sx0:sx1]
    return out


def track_dir(frames, cx, cy, w, tmpl, search=34, scales=(0.982, 0.993, 1.0, 1.007, 1.018)):
    """walk `frames` in order, NCC-matching `tmpl` (refreshed slowly)."""
    th, tw = tmpl.shape
    out = {}
    cur = (float(cx), float(cy), float(w))
    T = tmpl.copy()
    for f in frames:
        I = prep(load(f))
        best = None
        for s in scales:
            nw = max(12, int(round(cur[2] * s)))
            k = nw / float(cur[2])
            Ts = cv2.resize(T, (max(8, int(round(tw * k))), max(8, int(round(th * k)))),
                            interpolation=cv2.INTER_AREA if k < 1 else cv2.INTER_CUBIC)
            ph, pw = Ts.shape
            reg_w, reg_h = pw + 2 * search, ph + 2 * search
            R = patch(I, cur[0], cur[1], reg_w, reg_h)
            if R.shape[0] < ph or R.shape[1] < pw:
                continue
            res = cv2.matchTemplate(R, Ts, cv2.TM_CCOEFF_NORMED)
            _, mx, _, loc = cv2.minMaxLoc(res)
            ncx = cur[0] - reg_w / 2 + loc[0] + pw / 2
            ncy = cur[1] - reg_h / 2 + loc[1] + ph / 2
            pen = 0.012 * abs(np.log(s)) / 0.02
            if best is None or mx - pen > best[0]:
                best = (mx - pen, ncx, ncy, nw, mx)
        if best is None:
            out[f] = dict(cx=cur[0], cy=cur[1], w=cur[2], ncc=0.0)
            continue
        _, ncx, ncy, nw, raw = best
        cur = (ncx, ncy, float(nw))
        out[f] = dict(cx=cur[0], cy=cur[1], w=cur[2], ncc=float(raw))
        # slow template refresh keeps up with pose change without drifting fast
        if raw > 0.45:
            NT = patch(I, cur[0], cur[1], int(round(cur[2] * 1.30)),
                       int(round(cur[2] * 1.30 * th / tw)))
            NT = cv2.resize(NT, (tw, th))
            T = 0.82 * T + 0.18 * NT
    return out


def smooth(seq, frames, win=9, poly=2):
    if len(frames) < 5:
        return seq
    from numpy.polynomial import polynomial as P
    arr = {k: np.array([seq[f][k] for f in frames], float) for k in ('cx', 'cy', 'w')}
    out = {f: dict(seq[f]) for f in frames}
    n = len(frames)
    half = min(win // 2, max(1, n // 3))
    for k, v in arr.items():
        sm = np.empty_like(v)
        for i in range(n):
            a, b = max(0, i - half), min(n, i + half + 1)
            t = np.arange(a, b, dtype=float)
            d = min(poly, max(1, (b - a) - 2))
            c = np.polyfit(t - i, v[a:b], d)
            sm[i] = np.polyval(c, 0.0)
        for i, f in enumerate(frames):
            out[f][k] = float(sm[i])
    return out


def run():
    sol = {}
    for si, (a, b) in enumerate(SEG):
        if si in SKIP or si not in HINTS:
            continue
        af, cx, cy, w, style = HINTS[si]
        lo, hi = RANGE_OVERRIDE.get(si, (a, b))
        h = int(round(w * 1.22))                       # head is taller than wide
        T = patch(prep(load(af)), cx, cy, int(round(w * 1.30)), int(round(h * 1.30)))
        fwd = [f for f in range(af + 1, hi + 1)]
        bwd = [f for f in range(af - 1, lo - 1, -1)]
        seq = {af: dict(cx=float(cx), cy=float(cy), w=float(w), ncc=1.0)}
        seq.update(track_dir(fwd, cx, cy, w, T))
        seq.update(track_dir(bwd, cx, cy, w, T))
        frames = sorted(seq)
        seq = smooth(seq, frames)
        # scale must stay physically plausible inside one shot: refit w with a
        # robust *linear* ramp anchored on the measured value, then clamp.
        tt = np.array(frames, float)
        ww = np.array([seq[f]['w'] for f in frames], float)
        rs = np.random.RandomState(11)
        bi, bc = -1, None
        thr = max(4.0, 0.35 * np.std(ww) + 3.0)
        for _ in range(200):
            k = rs.choice(len(tt), size=min(len(tt), 3), replace=False)
            try:
                c = np.polyfit(tt[k], ww[k], 1)
            except Exception:
                continue
            inl = np.abs(np.polyval(c, tt) - ww) < thr
            if inl.sum() > bi:
                bi, bc = inl.sum(), inl
        c = np.polyfit(tt[bc], ww[bc], 1) if bc is not None and bc.sum() > 2 \
            else np.polyfit(tt, ww, 1)
        fit = np.polyval(c, tt)
        # renormalise so the anchor frame keeps its measured width exactly
        ai = frames.index(af) if af in frames else 0
        fit = fit * (float(w) / max(fit[ai], 1e-6))
        fit = np.clip(fit, w * 0.82, w * 1.24)
        for i, f in enumerate(frames):
            seq[f]['w'] = float(fit[i])
        nc = np.mean([seq[f]['ncc'] for f in frames])
        print(f"SEG{si:2d} f{lo}-{hi} n={len(frames):3d} style={style:5s} meanNCC={nc:.2f}")
        for f in frames:
            sol[str(f)] = dict(seq[f], style=style, seg=si)
    json.dump(sol, open('solve.json', 'w'))
    print('frames solved:', len(sol))


if __name__ == '__main__':
    run()
