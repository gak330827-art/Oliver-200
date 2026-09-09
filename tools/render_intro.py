#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
#  Oliver-200 · предпросмотр заставки в виде видео.
#
#  Зачем: заставка живёт на телефоне, а показать её надо в чате, в шапке
#  репозитория, в описании релиза. Инструмент собирает ровно тот же ролик
#  покадрово и кладёт рядом звук диктора.
#
#  Главное свойство: НИ ОДНОЙ своей константы. Тайминги читаются из
#  core/intro/IntroTimeline.kt, пропорции знака — из ui/IntroLogoView.kt,
#  тексты — из core/intro/PlainIntroBrand.kt. Поправили ленту в коде —
#  видео пересобирается тем же, а не «похожим» роликом.
#
#  Запуск:
#      python3 tools/render_intro.py --out intro.mp4 --audio voice.mp3
#      python3 tools/render_intro.py --out intro.mp4 --clean   # без «Пропустить»
#
#  Нужны: pillow, numpy, ffmpeg, шрифт Roboto (fonts-roboto-unhinted).
#  Подпись: OLIVER-200 · см. SIGNATURES.txt
# ═══════════════════════════════════════════════════════════════════════════
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TIMELINE_KT = os.path.join(ROOT, "core/src/main/kotlin/com/oliver200/launcher/core/intro/IntroTimeline.kt")
LOGO_KT = os.path.join(ROOT, "app/src/main/kotlin/com/oliver200/launcher/ui/IntroLogoView.kt")
BRAND_KT = os.path.join(ROOT, "core/src/main/kotlin/com/oliver200/launcher/core/intro/PlainIntroBrand.kt")
COLORS_XML = os.path.join(ROOT, "app/src/main/res/values/colors.xml")

FONT_BLACK = ["/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Black.ttf",
              "/usr/share/fonts/truetype/roboto/unhinted/Roboto-Black.ttf"]
FONT_MEDIUM = ["/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Medium.ttf",
               "/usr/share/fonts/truetype/roboto/unhinted/Roboto-Medium.ttf"]
FONT_REGULAR = ["/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf",
                "/usr/share/fonts/truetype/roboto/unhinted/Roboto-Regular.ttf"]

def android_colors(path):
    """Палитра берётся из ресурсов приложения — своих цветов у ролика нет."""
    out = {}
    for m in re.finditer(r'<color name="(\w+)">#([0-9A-Fa-f]{6,8})</color>', open(path, encoding="utf-8").read()):
        v = m.group(2)
        if len(v) == 8:
            v = v[2:]
        out[m.group(1)] = tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))
    return out


def font_path(candidates):
    for p in candidates:
        if os.path.isfile(p):
            return p
    raise SystemExit("Не найден шрифт Roboto: apt-get install fonts-roboto-unhinted")


# ─────────────────────────── чтение констант из кода ───────────────────────

def kotlin_consts(path):
    text = open(path, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"const val (\w+)\s*=\s*(-?[0-9_]+)L", text):
        out[m.group(1)] = int(m.group(2).replace("_", ""))
    for m in re.finditer(r"const val (\w+)\s*=\s*(-?[0-9.]+)f", text):
        out[m.group(1)] = float(m.group(2))
    return out


def cue_times(path):
    text = open(path, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"(BRAND|CARE)\((-?[0-9_]+)L\)", text):
        out[m.group(1)] = int(m.group(2).replace("_", ""))
    return out


def brand_texts(path):
    text = open(path, encoding="utf-8").read()
    out = {}
    for key in ("wordmarkTop", "wordmarkBottom", "badge", "platform", "care"):
        m = re.search(key + r'\s*=\s*"([^"]*)"', text)
        if not m:
            raise SystemExit("В PlainIntroBrand.kt не найдено поле " + key)
        out[key] = m.group(1)
    return out


# ────────────────────────────── кривые из :core ────────────────────────────

def clamp01(v):
    return 0.0 if v != v or v < 0 else (1.0 if v > 1 else v)


def out_cubic(t):
    i = 1.0 - clamp01(t)
    return 1.0 - i * i * i


def in_cubic(t):
    x = clamp01(t)
    return x * x * x


def in_out_cubic(t):
    x = clamp01(t)
    if x < 0.5:
        return 4 * x * x * x
    u = -2 * x + 2
    return 1 - u * u * u / 2


def out_back(t, overshoot=1.70158):
    x = clamp01(t)
    c3 = overshoot + 1
    i = x - 1
    return 1 + c3 * i * i * i + overshoot * i * i


class Script:
    """Точная копия IntroScript.frameAt на константах из исходника."""

    def __init__(self, c):
        self.c = c
        self.total = c["OUTRO_AT"] + c["OUTRO_MS"]

    def p(self, t, start, dur):
        return clamp01((t - start) / float(dur)) if dur > 0 else (1.0 if t >= start else 0.0)

    def at(self, t):
        c = self.c
        t = max(0, t)
        disc = self.p(t, c["MARK_AT"], c["MARK_MS"])
        row = self.p(t, c["ROW_AT"], c["ROW_MS"])
        cap = self.p(t, c["CAPTION_AT"], c["CAPTION_MS"])
        outro = self.p(t, c["OUTRO_AT"], c["OUTRO_MS"])
        return {
            "t": t,
            "backdrop": out_cubic(self.p(t, c["BACKDROP_AT"], c["BACKDROP_MS"])),
            "markAlpha": out_cubic(disc * 1.6),
            "markScale": c["MARK_SCALE_FROM"] + (1 - c["MARK_SCALE_FROM"]) * out_back(disc),
            "frameDraw": in_out_cubic(self.p(t, c["FRAME_AT"], c["FRAME_MS"])),
            "shine": in_out_cubic(self.p(t, c["SHINE_AT"], c["SHINE_MS"])),
            "titleReveal": out_cubic(self.p(t, c["TITLE_AT"], c["TITLE_MS"])),
            "rowAlpha": out_cubic(row),
            "rowRise": 1 - out_cubic(row),
            "captionAlpha": out_cubic(cap),
            "captionRise": 1 - out_cubic(cap),
            "master": 1 - in_cubic(outro),
        }


# ───────────────────────────────── отрисовка ───────────────────────────────

class Renderer:
    def __init__(self, w, h, geom, brand, script, show_skip, skip_label, colors):
        self.w, self.h, self.g, self.brand = w, h, geom, brand
        self.colors = colors
        self.ink = colors["intro_ink"]
        self.paper_ink = colors["intro_paper"]
        self.script = script
        self.show_skip = show_skip
        self.skip_label = skip_label
        self._layout()
        self._prerender()

    # Повторяет IntroLogoView.layoutMark один в один.
    def _layout(self):
        g, w, h = self.g, self.w, self.h
        self.cx, self.cy = w / 2.0, h * 0.42
        fw = min(w * g["MARK_W_PER_W"], h * g["MARK_W_PER_H"])
        fh = fw / g["FRAME_ASPECT"]
        self.frame_w = fw
        self.frame_rect = (self.cx - fw / 2, self.cy - fh / 2, self.cx + fw / 2, self.cy + fh / 2)
        self.stroke = fh * g["STROKE_PER_H"]
        pad = fh * g["PAD_PER_H"]
        l, t, r, b = self.frame_rect
        self.inner = (l + self.stroke / 2 + pad, t + self.stroke / 2 + pad,
                      r - self.stroke / 2 - pad, b - self.stroke / 2 - pad)
        inner_w = self.inner[2] - self.inner[0]
        inner_h = self.inner[3] - self.inner[1]

        self.tracking_title = -0.02   # letterSpacing титульной краски
        black = font_path(FONT_BLACK)
        top, bottom = self.brand["wordmarkTop"], self.brand["wordmarkBottom"]

        size_top = self._fit(black, top, inner_w, self.tracking_title)
        size_bottom = self._fit(black, bottom, inner_w, self.tracking_title)
        h_top = self._ink_height(black, size_top, top)
        h_bottom = self._ink_height(black, size_bottom, bottom)
        row_h = fh * g["ROW_PER_H"]
        gap_title = fh * g["GAP_TITLE_PER_H"]
        gap_row = fh * g["GAP_ROW_PER_H"]

        measured = h_top + gap_title + h_bottom + gap_row + row_h
        if measured > inner_h and measured > 0:
            k = inner_h / measured
            size_top *= k
            size_bottom *= k
            h_top *= k
            h_bottom *= k
            gap_title *= k
            gap_row *= k
            row_h *= k

        self.font_top = ImageFont.truetype(black, int(round(size_top)))
        self.font_bottom = ImageFont.truetype(black, int(round(size_bottom)))
        self.xs_top, w_top = self._positions(self.font_top, top, self.tracking_title * size_top)
        self.xs_bottom, w_bottom = self._positions(self.font_bottom, bottom, self.tracking_title * size_bottom)
        self.x0_top = self.cx - w_top / 2
        self.x0_bottom = self.cx - w_bottom / 2

        stack = h_top + gap_title + h_bottom + gap_row + row_h
        stack_top = self.cy - stack / 2
        self.base_top = stack_top + h_top
        self.base_bottom = self.base_top + gap_title + h_bottom
        row_top = self.base_bottom + gap_row
        self.row_h = row_h

        self.font_badge = ImageFont.truetype(black, int(round(row_h * 0.68)))
        badge_pad = row_h * 0.22
        badge_w = self.font_badge.getlength(self.brand["badge"])
        self.badge_box = (self.inner[0], row_top, self.inner[0] + badge_w + badge_pad * 2, row_top + row_h)
        self.badge_xy = (self.badge_box[0] + badge_pad,
                         row_top + row_h / 2 + self._ink_height(black, self.font_badge.size, self.brand["badge"]) / 2)

        gap_badge = row_h * 0.28
        plat_x = self.badge_box[2] + gap_badge
        plat_w = self.inner[2] - plat_x
        size_plat = self._fit(black, self.brand["platform"], plat_w, 0.0)
        self.font_plat = ImageFont.truetype(black, int(round(size_plat)))
        self.plat_xy = (plat_x, row_top + row_h / 2
                        + self._ink_height(black, self.font_plat.size, self.brand["platform"]) / 2)

        # Подпись под диском: 19sp, letterSpacing 0.04, 88dp от низа, всход 22dp.
        dp = h / 640.0
        self.font_caption = ImageFont.truetype(font_path(FONT_MEDIUM), int(round(19 * dp)))
        self.caption_track = 0.04 * self.font_caption.size
        self.caption_xs, cap_w = self._positions(self.font_caption, self.brand["care"], self.caption_track)
        self.caption_x0 = self.cx - cap_w / 2
        self.caption_y = h - 88 * dp
        self.caption_rise = 22 * dp
        self.font_skip = ImageFont.truetype(font_path(FONT_REGULAR), int(round(14 * dp)))
        self.skip_xy = (w - 24 * dp - self.font_skip.getlength(self.skip_label), 40 * dp)

    def _fit(self, path, text, target, tracking_em):
        probe = ImageFont.truetype(path, 100)
        base = sum(probe.getlength(ch) for ch in text) / 100.0 + tracking_em * len(text)
        return target / base if base > 0 else 1.0

    def _ink_height(self, path, size, text):
        f = ImageFont.truetype(path, int(round(size)))
        box = f.getbbox(text)
        return float(box[3] - box[1])

    def _positions(self, font, text, tracking_px):
        xs, x = [], 0.0
        for ch in text:
            xs.append(x)
            x += font.getlength(ch) + tracking_px
        return xs, x - tracking_px if text else 0.0

    def _prerender(self):
        w, h = self.w, self.h
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        dist = np.sqrt((xx - self.cx) ** 2 + (yy - self.cy) ** 2)

        # Лист: белый в центре, чуть темнее к краям — как в IntroLogoView.
        k = np.clip(dist / (max(w, h) * self.g["PAPER_VIGNETTE_R"]), 0, 1)[..., None]
        centre = np.array(self.colors["intro_paper"], np.float32)
        edge = np.array(self.colors["intro_paper_edge"], np.float32)
        self.paper = Image.fromarray((centre * (1 - k) + edge * k).astype(np.uint8), "RGB")
        self.paper_flat = Image.new("RGB", (w, h), self.colors["intro_paper"])
        self.black = Image.new("RGB", (w, h), (0, 0, 0))
        self.xx = xx

    @staticmethod
    def _alpha(layer, k):
        if k >= 0.999:
            return layer
        if k <= 0:
            return None
        out = layer.copy()
        out.putalpha(layer.getchannel("A").point(lambda v: int(v * k + 0.5)))
        return out

    def _stroke_layer(self, progress):
        layer = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        l, t, r, b = self.frame_rect
        s = self.stroke
        left = 2 * ((r - l) + (b - t)) * progress
        for (x0, y0), (x1, y1) in (((l, t), (r, t)), ((r, t), (r, b)),
                                   ((r, b), (l, b)), ((l, b), (l, t))):
            if left <= 0:
                break
            seg = abs(x1 - x0) + abs(y1 - y0)
            f = min(1.0, left / seg)
            xe, ye = x0 + (x1 - x0) * f, y0 + (y1 - y0) * f
            rx = sorted([x0, xe])
            ry = sorted([y0, ye])
            d.rectangle([rx[0] - s / 2, ry[0] - s / 2, rx[1] + s / 2, ry[1] + s / 2], fill=self.ink + (255,))
            left -= seg
        return layer

    def _letters_layer(self, reveal):
        layer = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        top, bottom = self.brand["wordmarkTop"], self.brand["wordmarkBottom"]
        total = len(top) + len(bottom)
        window = self.g["LETTER_WINDOW"]
        for text, font, xs, x0, base, off in (
            (top, self.font_top, self.xs_top, self.x0_top, self.base_top, 0),
            (bottom, self.font_bottom, self.xs_bottom, self.x0_bottom, self.base_bottom, len(top)),
        ):
            d = ImageDraw.Draw(layer)
            for i, ch in enumerate(text):
                start = (off + i) / total * (1 - window)
                local = clamp01((reveal - start) / window)
                if local <= 0:
                    continue
                d.text((x0 + xs[i], base), ch, font=font, fill=self.ink + (int(255 * local),), anchor="ls")
        return layer

    def _row_layer(self, rise):
        layer = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        shift = rise * self.row_h * 0.8
        x0, y0, x1, y1 = self.badge_box
        d.rectangle([x0, y0 + shift, x1, y1 + shift], fill=self.ink + (255,))
        d.text((self.badge_xy[0], self.badge_xy[1] + shift), self.brand["badge"],
               font=self.font_badge, fill=self.paper_ink + (255,), anchor="ls")
        d.text((self.plat_xy[0], self.plat_xy[1] + shift), self.brand["platform"],
               font=self.font_plat, fill=self.ink + (255,), anchor="ls")
        return layer

    def _shine_layer(self, progress, alpha):
        band = self.frame_w * self.g["SHINE_W_PER_MARK"]
        travel = self.frame_w + band
        start = self.frame_rect[0] - band + travel * progress
        centre = start + band / 2
        prof = np.clip(1 - np.abs((self.xx - centre) / (band / 2)), 0, 1)
        inside = np.zeros((self.h, self.w), np.float32)
        t0, b0 = int(self.frame_rect[1]), int(self.frame_rect[3])
        inside[max(0, t0):min(self.h, b0), :] = 1.0
        arr = np.zeros((self.h, self.w, 4), np.uint8)
        arr[..., :3] = 255
        arr[..., 3] = (prof * inside * 150 * alpha).astype(np.uint8)
        return Image.fromarray(arr, "RGBA")

    def frame(self, t_ms):
        f = self.script.at(t_ms)
        master = f["master"]

        # Лист гаснет вместе со сценой, поверх поднимается чёрная вуаль —
        # ровно как в IntroLogoView.onDraw.
        img = Image.blend(self.paper_flat, self.paper, clamp01(f["backdrop"] * master)).convert("RGBA")

        mark_alpha = f["markAlpha"] * master
        if mark_alpha > 0:
            mark = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
            if f["frameDraw"] > 0:
                mark = Image.alpha_composite(mark, self._stroke_layer(f["frameDraw"]))
            if f["titleReveal"] > 0:
                mark = Image.alpha_composite(mark, self._letters_layer(f["titleReveal"]))
            if f["rowAlpha"] > 0:
                row = self._alpha(self._row_layer(f["rowRise"]), f["rowAlpha"])
                if row is not None:
                    mark = Image.alpha_composite(mark, row)
            if 0 < f["shine"] < 1:
                mark = Image.alpha_composite(mark, self._shine_layer(f["shine"], 1.0))

            s = f["markScale"]
            if abs(s - 1) > 1e-3:
                mark = mark.transform(
                    (self.w, self.h), Image.AFFINE,
                    (1 / s, 0, self.cx - self.cx / s, 0, 1 / s, self.cy - self.cy / s),
                    resample=Image.BICUBIC)
            mark = self._alpha(mark, clamp01(mark_alpha))
            if mark is not None:
                img = Image.alpha_composite(img, mark)

        cap_alpha = clamp01(f["captionAlpha"] * master)
        if cap_alpha > 0:
            layer = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
            d = ImageDraw.Draw(layer)
            y = self.caption_y + f["captionRise"] * self.caption_rise
            ink = self.colors["intro_ink_soft"]
            for i, ch in enumerate(self.brand["care"]):
                d.text((self.caption_x0 + self.caption_xs[i], y), ch,
                       font=self.font_caption, fill=ink + (int(255 * cap_alpha),), anchor="ls")
            img = Image.alpha_composite(img, layer)

        if self.show_skip:
            appear = clamp01((f["t"] - 700) / 400.0) * master
            if appear > 0:
                layer = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
                ImageDraw.Draw(layer).text(self.skip_xy, self.skip_label, font=self.font_skip,
                                           fill=self.colors["intro_ink_faint"] + (int(255 * appear),))
                img = Image.alpha_composite(img, layer)

        out = img.convert("RGB")
        veil = 1 - master
        if veil > 0:
            out = Image.blend(out, self.black, clamp01(veil))
        return out


def main():
    ap = argparse.ArgumentParser(description="Собрать видео заставки Oliver-200")
    ap.add_argument("--out", default="intro.mp4")
    ap.add_argument("--audio", default=None, help="mp3/wav с голосом диктора")
    ap.add_argument("--width", type=int, default=1080)
    ap.add_argument("--height", type=int, default=1920)
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--clean", action="store_true", help="без кнопки «Пропустить»")
    ap.add_argument("--skip-label", default="Пропустить")
    ap.add_argument("--gif", default=None, help="дополнительно собрать GIF по этому пути")
    args = ap.parse_args()

    if shutil.which("ffmpeg") is None:
        raise SystemExit("Нужен ffmpeg: apt-get install ffmpeg")

    consts = kotlin_consts(TIMELINE_KT)
    geom = kotlin_consts(LOGO_KT)
    brand = brand_texts(BRAND_KT)
    cues = cue_times(TIMELINE_KT)
    script = Script(consts)

    print("лента из кода: всего %d мс, диктор на %d и %d мс"
          % (script.total, cues.get("BRAND", -1), cues.get("CARE", -1)))
    print("знак: %s / %s · %s %s · «%s»" % (brand["wordmarkTop"], brand["wordmarkBottom"],
                                            brand["badge"], brand["platform"], brand["care"]))

    renderer = Renderer(args.width, args.height, geom, brand, script,
                        show_skip=not args.clean, skip_label=args.skip_label,
                        colors=android_colors(COLORS_XML))

    frames = int(round(script.total / 1000.0 * args.fps))
    tmp = tempfile.mkdtemp(prefix="oliver-intro-")
    try:
        for i in range(frames):
            t = int(round(i * 1000.0 / args.fps))
            renderer.frame(t).save(os.path.join(tmp, "f%05d.png" % i))
            if i % 30 == 0:
                sys.stdout.write("\rкадр %d/%d" % (i, frames))
                sys.stdout.flush()
        print("\rотрисовано кадров: %d      " % frames)

        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(args.fps),
               "-i", os.path.join(tmp, "f%05d.png")]
        if args.audio:
            cmd += ["-i", args.audio]
        cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "18",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart"]
        if args.audio:
            cmd += ["-c:a", "aac", "-b:a", "160k", "-shortest"]
        cmd += [args.out]
        subprocess.run(cmd, check=True)
        print("готово:", args.out)

        if args.gif:
            palette = os.path.join(tmp, "p.png")
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", args.out,
                            "-vf", "fps=24,scale=480:-1:flags=lanczos,palettegen", palette], check=True)
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", args.out, "-i", palette,
                            "-lavfi", "fps=24,scale=480:-1:flags=lanczos[x];[x][1:v]paletteuse",
                            args.gif], check=True)
            print("готово:", args.gif)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
