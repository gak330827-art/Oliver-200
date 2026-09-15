#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
==============================================================================
 Oliver-200 · FX-рендер «ROSE LASER / DEEP SPACE»
------------------------------------------------------------------------------
 ФАЙЛ 1 из 2  ·  ПОДПИСЬ: OLIVER200-FX-PLAIN  ·  БЕЗ ШИФРОВКИ
 (открытый, читаемый исходник — эталон логики)
------------------------------------------------------------------------------
 Что делает:
   1. Вырезает персонажа с зигзагового фона (текстурно-цветовая сегментация
      с посевной реконструкцией — без нейросетей и без интернета).
   2. Строит космическую сцену: туманность, звёздное поле, газовый гигант
      с кольцами, луна, портальное кольцо, варп-полосы.
   3. Рисует РОЗОВЫЕ ЛАЗЕРЫ ИЗ ГЛАЗ: белое ядро → розовый → пурпурный ореол,
      блики в точке выхода, энергетические кольца вдоль луча.
   4. Взрыв астероида в точке попадания: ядро, ударная волна, осколки, искры.
   5. Плазменное пламя снизу (отсылка к референсу с огнём).
   6. Пост: bloom, хроматическая аберрация, глитч-срезы, зерно, виньетка.

 Безопасность (см. также SECURITY.md проекта):
   * пути проверяются (_safe_path): только обычные файлы, без симлинк-побега;
   * входное изображение ограничено по числу пикселей (защита от decompression
     bomb), формат проверяется по содержимому, а не по расширению;
   * нет eval/exec/pickle/os.system и сетевых вызовов вообще;
   * все числовые параметры CLI зажимаются в безопасный диапазон;
   * запись атомарная: tmp с правами 0600 → chmod 0644 → os.replace.

 Запуск:
   python3 oliver_laser_space_plain.py --src <фото.jpg> --out <результат.png>
==============================================================================
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

# ----------------------------------------------------------------------------
# Константы сцены
# ----------------------------------------------------------------------------

SIGNATURE = "OLIVER200-FX-PLAIN"

MAX_INPUT_PIXELS = 40_000_000          # защита от «бомбы сжатия»
MAX_CANVAS_PIXELS = 30_000_000

# Палитра фона исходника (зигзаг + рамка) — измерена по оригиналу.
BG_PALETTE = np.array(
    [[173, 75, 182], [255, 255, 255], [217, 234, 255], [248, 208, 255]],
    dtype=np.float32,
)

# Прямоугольник «это точно персонаж» — светлая щека головы совпадает по цвету
# с фоном, поэтому её защищаем от фонового правила (координаты исходника).
HEAD_KEEP_RECT = (264, 98, 430, 220)   # x0, y0, x1, y1

# Глаза волчьей маски в координатах исходника.
EYE_SRC = ((263.0, 128.0), (287.0, 130.0))

PINK_CORE = np.array([1.00, 0.97, 1.00], np.float32)
PINK_HOT = np.array([1.00, 0.42, 0.86], np.float32)
PINK_GLOW = np.array([1.00, 0.16, 0.66], np.float32)
CYAN_RIM = np.array([0.35, 0.85, 1.00], np.float32)


# ----------------------------------------------------------------------------
# Безопасные ввод/вывод
# ----------------------------------------------------------------------------

def _safe_path(raw: str, *, must_exist: bool) -> Path:
    """Нормализует путь и отсекает опасные случаи.

    Симлинк проверяется ДО resolve(): после разрешения ссылки проверять уже
    нечего — resolve() возвращает цель, и is_symlink() всегда False. Для
    записи ссылка опаснее всего: подменённый линк увёл бы вывод в чужой файл.
    """
    raw_path = Path(raw).expanduser()
    if raw_path.is_symlink():
        raise SystemExit(f"[fx] символические ссылки запрещены: {raw_path}")
    try:
        p = raw_path.resolve(strict=must_exist)
    except (OSError, RuntimeError) as exc:
        raise SystemExit(f"[fx] недопустимый путь {raw!r}: {exc}")
    if must_exist:
        if not p.is_file():
            raise SystemExit(f"[fx] не обычный файл: {p}")
    else:
        if p.exists() and not p.is_file():
            raise SystemExit(f"[fx] путь вывода занят не файлом: {p}")
        if not p.parent.is_dir():
            raise SystemExit(f"[fx] нет каталога для записи: {p.parent}")
    return p


def load_image(path: Path) -> np.ndarray:
    """Читает изображение через буфер (не даём OpenCV трогать ФС напрямую)."""
    size = path.stat().st_size
    if size <= 0 or size > 64 * 1024 * 1024:
        raise SystemExit(f"[fx] подозрительный размер файла: {size} Б")
    buf = np.frombuffer(path.read_bytes(), dtype=np.uint8)
    img = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit(f"[fx] не удалось декодировать изображение: {path}")
    h, w = img.shape[:2]
    if h * w > MAX_INPUT_PIXELS:
        raise SystemExit(f"[fx] слишком большое изображение: {w}x{h}")
    return img


def save_image_atomic(path: Path, img_bgr: np.ndarray) -> None:
    ok, buf = cv2.imencode(".png", img_bgr, [cv2.IMWRITE_PNG_COMPRESSION, 6])
    if not ok:
        raise SystemExit("[fx] кодирование PNG не удалось")
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".fx-", suffix=".png")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(buf.tobytes())
        # mkstemp создаёт файл с 0600 — никто не видит наполовину записанный
        # результат; готовый кадр публикуем уже обычными правами.
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


# ----------------------------------------------------------------------------
# Утилиты
# ----------------------------------------------------------------------------

def _ellipse_kernel(n: int) -> np.ndarray:
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (n, n))


def local_std(gray: np.ndarray, k: int) -> np.ndarray:
    m = cv2.blur(gray, (k, k))
    s = cv2.blur(gray * gray, (k, k))
    return np.sqrt(np.maximum(s - m * m, 0.0))


def fill_holes(mask: np.ndarray) -> np.ndarray:
    pad = cv2.copyMakeBorder(mask, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
    ff = np.zeros((pad.shape[0] + 2, pad.shape[1] + 2), np.uint8)
    cv2.floodFill(pad, ff, (0, 0), 1)
    holes = (pad[1:-1, 1:-1] == 0).astype(np.uint8)
    return np.maximum(mask, holes)


def largest_component(mask: np.ndarray) -> np.ndarray:
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    if n <= 1:
        return mask
    best = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    return (lab == best).astype(np.uint8)


def fbm(h: int, w: int, rng: np.random.Generator, octaves: int = 6,
        base: int = 4, gain: float = 0.5, warp: float = 0.0) -> np.ndarray:
    """Фрактальный шум значений: суммируем апскейленные случайные сетки."""
    out = np.zeros((h, w), np.float32)
    amp, total, freq = 1.0, 0.0, base
    for _ in range(octaves):
        grid = rng.random((max(2, freq), max(2, freq))).astype(np.float32)
        layer = cv2.resize(grid, (w, h), interpolation=cv2.INTER_CUBIC)
        out += amp * layer
        total += amp
        amp *= gain
        freq *= 2
    out /= max(total, 1e-6)
    if warp > 0.0:
        wx = (fbm(h, w, rng, 3, 3) - 0.5) * warp
        wy = (fbm(h, w, rng, 3, 3) - 0.5) * warp
        gx, gy = np.meshgrid(np.arange(w, dtype=np.float32),
                             np.arange(h, dtype=np.float32))
        out = cv2.remap(out, gx + wx, gy + wy, cv2.INTER_LINEAR,
                        borderMode=cv2.BORDER_REFLECT)
    return np.clip(out, 0.0, 1.0)


def smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / max(edge1 - edge0, 1e-6), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def add_rgb(dst: np.ndarray, intensity: np.ndarray, color: np.ndarray) -> None:
    dst += intensity[:, :, None] * color[None, None, :]


def gauss(img: np.ndarray, sigma: float) -> np.ndarray:
    if sigma <= 0:
        return img
    return cv2.GaussianBlur(img, (0, 0), sigma, borderType=cv2.BORDER_REPLICATE)


# ----------------------------------------------------------------------------
# 1. Сегментация персонажа
# ----------------------------------------------------------------------------

def extract_character(bgr: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Возвращает (RGB float 0..1, alpha float 0..1) для персонажа."""
    h, w = bgr.shape[:2]
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.float32)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)

    s3 = local_std(gray, 3)
    s9 = local_std(gray, 9)
    s21 = local_std(gray, 21)

    dist_pal = np.min(
        np.linalg.norm(rgb[:, :, None, :] - BG_PALETTE[None, None, :, :], axis=3),
        axis=2,
    )
    min_channel = rgb.min(axis=2)

    keep = np.zeros((h, w), np.uint8)
    x0, y0, x1, y1 = HEAD_KEEP_RECT
    keep[max(0, y0):min(h, y1), max(0, x0):min(w, x1)] = 1

    # Плоский фон-палитра + идеально белые полосы зигзага.
    flat_bg = (dist_pal < 28) & (s3 < 5) & (s9 < 9)
    pure_white = (min_channel >= 238) & (s9 < 20)
    mask_b = ((flat_bg | pure_white).astype(np.uint8)) * (1 - keep)

    # Посев: «абсолютно» фоновые пиксели. Компонента mask_b без посева —
    # это плоский участок самого персонажа, его не трогаем.
    seed = (((dist_pal < 9) & (s9 < 2.5) & (s21 < 8)).astype(np.uint8)) * mask_b
    n, lab, _, _ = cv2.connectedComponentsWithStats(mask_b, 8)
    has_seed = np.zeros(n, bool)
    ids = lab[seed == 1]
    has_seed[np.unique(ids[ids > 0])] = True
    has_seed[0] = False
    bg = has_seed[lab].astype(np.uint8)

    white_strict = ((min_channel >= 240) & (s9 < 20)).astype(np.uint8)
    bg = np.maximum(bg, white_strict)

    # Съедаем сеть тонких переходных пикселей между полосами зигзага,
    # затем возвращаем контур обратно тем же радиусом.
    r = 13
    bg_wide = np.maximum(cv2.dilate(bg, _ellipse_kernel(r)) * (1 - keep),
                         cv2.dilate(white_strict, _ellipse_kernel(5)))
    fg = largest_component((1 - bg_wide).astype(np.uint8))
    fg = fill_holes(fg)
    fg = cv2.dilate(fg, _ellipse_kernel(r))
    fg = (cv2.medianBlur(fg * 255, 7) // 255).astype(np.uint8)
    fg = fill_holes(largest_component(fg))

    core = cv2.erode(fg, _ellipse_kernel(5))
    alpha = gauss(core.astype(np.float32), 1.6)
    alpha = np.clip(alpha, 0.0, 1.0)

    return rgb / 255.0, alpha


# ----------------------------------------------------------------------------
# 2. Космический фон
# ----------------------------------------------------------------------------

def render_space(h: int, w: int, rng: np.random.Generator) -> np.ndarray:
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx, ny = xx / w, yy / h

    # Базовый градиент: тёмно-фиолетовый верх → почти чёрный низ.
    sky = np.zeros((h, w, 3), np.float32)
    grad = (1.0 - ny) ** 1.6
    sky[:, :, 0] = 0.030 + 0.055 * grad
    sky[:, :, 1] = 0.014 + 0.020 * grad
    sky[:, :, 2] = 0.075 + 0.145 * grad

    # Туманности: три слоя fbm с разными палитрами.
    layers = [
        (fbm(h, w, rng, 7, 3, 0.55, warp=90.0), np.array([1.00, 0.18, 0.62], np.float32), 0.42),
        (fbm(h, w, rng, 6, 2, 0.58, warp=70.0), np.array([0.45, 0.20, 1.00], np.float32), 0.34),
        (fbm(h, w, rng, 6, 4, 0.50, warp=60.0), np.array([0.15, 0.85, 1.00], np.float32), 0.38),
    ]
    for noise, color, strength in layers:
        cloud = smoothstep(0.46, 0.88, noise) ** 1.7
        falloff = smoothstep(1.25, 0.05, np.hypot(nx - 0.35, (ny - 0.42) * 1.25))
        add_rgb(sky, cloud * falloff * strength, color)

    # Тёмные пылевые прожилки.
    dust = smoothstep(0.55, 0.95, fbm(h, w, rng, 5, 5, 0.55, warp=40.0))
    sky *= (1.0 - 0.55 * dust)[:, :, None]

    # Звёзды: степенное распределение яркости.
    count = int(h * w / 950)
    sx = rng.integers(0, w, count)
    sy = rng.integers(0, h, count)
    mag = rng.random(count).astype(np.float32) ** 7.0
    stars = np.zeros((h, w), np.float32)
    np.add.at(stars, (sy, sx), mag)
    tint = np.zeros((h, w, 3), np.float32)
    hue = rng.random(count).astype(np.float32)
    np.add.at(tint[:, :, 0], (sy, sx), mag * (0.75 + 0.45 * hue))
    np.add.at(tint[:, :, 1], (sy, sx), mag * (0.80 + 0.20 * hue))
    np.add.at(tint[:, :, 2], (sy, sx), mag * (1.00 - 0.10 * hue))
    sky += gauss(tint, 0.7) * 2.1
    sky += gauss(stars, 2.4)[:, :, None] * 0.30

    # Крупные звёзды с дифракционными лучами.
    bright = np.zeros((h, w), np.float32)
    for _ in range(26):
        bx, by = int(rng.integers(0, w)), int(rng.integers(0, h))
        power = float(rng.uniform(0.5, 1.0))
        length = int(rng.uniform(16, 52))
        cv2.line(bright, (bx - length, by), (bx + length, by), power * 0.55, 1)
        cv2.line(bright, (bx, by - length), (bx, by + length), power * 0.55, 1)
        cv2.circle(bright, (bx, by), 2, power, -1)
    sky += gauss(bright, 1.1)[:, :, None] * np.array([0.80, 0.74, 0.80], np.float32)

    return sky


def render_planet(canvas: np.ndarray, cx: float, cy: float, radius: float,
                  rng: np.random.Generator, *, base=(0.55, 0.16, 0.62),
                  rings: bool = True, light=(-0.55, -0.45)) -> None:
    """Газовый гигант: полосы, терминатор, лимб, кольца."""
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx = (xx - cx) / radius
    dy = (yy - cy) / radius
    rr = np.hypot(dx, dy)

    lx, ly = light
    ln = float(np.hypot(lx, ly, dtype=np.float32) or 1.0)
    lx, ly = lx / ln, ly / ln

    inside = rr <= 1.0
    nz = np.sqrt(np.maximum(1.0 - np.minimum(rr, 1.0) ** 2, 0.0))
    lambert = np.clip(dx * lx + dy * ly + 0.62 * nz, 0.0, 1.0)

    # Полосы по «широте» + турбулентность.
    lat = np.arcsin(np.clip(dy, -1.0, 1.0))
    turb = fbm(h, w, rng, 5, 6, 0.55, warp=26.0)
    bands = 0.5 + 0.5 * np.sin(lat * 11.0 + turb * 3.4)
    tex = 0.62 + 0.38 * bands

    body = np.zeros((h, w, 3), np.float32)
    col = np.array(base, np.float32)
    for c in range(3):
        body[:, :, c] = col[c] * tex * (0.10 + 0.95 * lambert)

    # Лимбовое свечение и атмосфера снаружи.
    limb = np.clip((rr - 0.86) / 0.14, 0.0, 1.0) ** 2 * inside
    add_rgb(body, limb * 0.42, np.array([1.0, 0.45, 0.95], np.float32))
    halo = np.exp(-np.clip(rr - 1.0, 0.0, 6.0) * 5.0) * (~inside)
    add_rgb(body, halo * 0.22, np.array([0.85, 0.30, 1.00], np.float32))

    mask = (inside.astype(np.float32))
    mask = gauss(mask, 1.2)[:, :, None]
    canvas *= (1.0 - mask * 0.96)
    canvas += body * np.maximum(mask, (halo * 0.5)[:, :, None])

    if not rings:
        return

    # Кольца: наклонённый эллипс, прорисован как кольцевой диск.
    tilt = 0.26
    rx = (xx - cx) / (radius * 1.95)
    ry = (yy - cy) / (radius * 1.95 * tilt)
    rad = np.hypot(rx, ry)
    ring = (smoothstep(0.52, 0.60, rad) * (1.0 - smoothstep(0.92, 1.0, rad)))
    stripes = 0.62 + 0.38 * np.sin(rad * 24.0)
    ring = ring * stripes
    ring *= 1.0 - 0.75 * (inside & (yy < cy))          # задняя часть за планетой
    ring = gauss(ring, 1.0)
    add_rgb(canvas, ring * 0.30, np.array([1.00, 0.62, 0.92], np.float32))


def render_portal_ring(canvas: np.ndarray, cx: float, cy: float,
                       rx: float, ry: float, rng: np.random.Generator) -> None:
    """Энергетический диск-портал за персонажем."""
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u = (xx - cx) / rx
    v = (yy - cy) / ry
    rad = np.hypot(u, v)
    band = np.exp(-((rad - 1.0) ** 2) / (2 * 0.018 ** 2))
    inner = np.exp(-((rad - 0.82) ** 2) / (2 * 0.06 ** 2)) * 0.35
    ang = np.arctan2(v, u)
    flicker = 0.7 + 0.3 * np.sin(ang * 9.0 + rad * 18.0)
    total = gauss((band + inner) * flicker, 1.6)
    add_rgb(canvas, total * 0.40, PINK_GLOW)
    add_rgb(canvas, gauss(band, 7.0) * 0.16, np.array([0.70, 0.25, 1.00], np.float32))


def render_warp_streaks(canvas: np.ndarray, cx: float, cy: float,
                        rng: np.random.Generator, count: int = 150) -> None:
    h, w = canvas.shape[:2]
    layer = np.zeros((h, w), np.float32)
    diag = float(np.hypot(h, w))
    for _ in range(count):
        ang = float(rng.uniform(0, 2 * np.pi))
        r0 = float(rng.uniform(0.22, 0.75)) * diag
        r1 = r0 + float(rng.uniform(0.05, 0.28)) * diag
        p0 = (int(cx + np.cos(ang) * r0), int(cy + np.sin(ang) * r0))
        p1 = (int(cx + np.cos(ang) * r1), int(cy + np.sin(ang) * r1))
        cv2.line(layer, p0, p1, float(rng.uniform(0.10, 0.45)), 1, cv2.LINE_AA)
    layer = gauss(layer, 1.3)
    add_rgb(canvas, layer * 0.26, np.array([1.00, 0.55, 0.95], np.float32))


# ----------------------------------------------------------------------------
# 3. Лазеры, взрыв, пламя
# ----------------------------------------------------------------------------

def _segment_distance(h: int, w: int, p0, p1) -> tuple[np.ndarray, np.ndarray]:
    """Расстояние до отрезка и нормированная координата вдоль него."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ax, ay = float(p0[0]), float(p0[1])
    bx, by = float(p1[0]), float(p1[1])
    vx, vy = bx - ax, by - ay
    length2 = max(vx * vx + vy * vy, 1e-6)
    t = np.clip(((xx - ax) * vx + (yy - ay) * vy) / length2, 0.0, 1.0)
    px = ax + t * vx
    py = ay + t * vy
    return np.hypot(xx - px, yy - py), t


def render_laser(canvas: np.ndarray, p0, p1, rng: np.random.Generator,
                 width: float = 11.0, overshoot: float = 1.35) -> None:
    """Розовый лазер: ядро, тело, ореол, кольца энергии."""
    h, w = canvas.shape[:2]
    # Луч продлеваем за цель, чтобы он не «обрубался».
    ex = p0[0] + (p1[0] - p0[0]) * overshoot
    ey = p0[1] + (p1[1] - p0[1]) * overshoot
    dist, t = _segment_distance(h, w, p0, (ex, ey))

    # Лёгкая волнистость — луч «дышит».
    wobble = (fbm(h, w, rng, 4, 8, 0.5) - 0.5) * width * 0.9
    dist = np.abs(dist + wobble * smoothstep(0.02, 0.5, t))

    taper = 1.0 + 0.55 * t                       # к концу луч шире
    sigma_core = width * 0.30 * taper
    sigma_body = width * 0.95 * taper
    sigma_halo = width * 1.90 * taper

    core = np.exp(-(dist ** 2) / (2 * sigma_core ** 2))
    body = np.exp(-(dist ** 2) / (2 * sigma_body ** 2))
    halo = np.exp(-(dist ** 2) / (2 * sigma_halo ** 2))

    fade = 1.0 - smoothstep(0.80, 1.0, t)        # мягкий хвост
    add_rgb(canvas, core * fade * 1.05, PINK_CORE)
    add_rgb(canvas, body * fade * 0.60, PINK_HOT)
    add_rgb(canvas, halo * fade * 0.26, PINK_GLOW)

    # Кольца энергии, бегущие вдоль луча.
    rings = np.exp(-((dist / (sigma_body * 1.9)) ** 2)) * \
        (0.5 + 0.5 * np.sin(t * 58.0)) ** 6
    add_rgb(canvas, rings * fade * 0.30, PINK_HOT)

    # Вспышка в точке выхода из глаза.
    render_flare(canvas, p0, radius=width * 1.35, power=0.22)


def render_flare(canvas: np.ndarray, p, radius: float, power: float = 1.0) -> None:
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.hypot(xx - float(p[0]), yy - float(p[1]))
    glow = np.exp(-(d ** 2) / (2 * (radius * 0.45) ** 2))
    wide = np.exp(-(d ** 2) / (2 * (radius * 1.6) ** 2))
    add_rgb(canvas, glow * power * 0.95, PINK_CORE)
    add_rgb(canvas, wide * power * 0.42, PINK_HOT)

    spikes = np.zeros((h, w), np.float32)
    cx, cy = int(p[0]), int(p[1])
    for ang, ln in ((0.0, 1.5), (np.pi / 2, 0.8), (np.pi / 4, 0.5), (-np.pi / 4, 0.5)):
        dx = int(np.cos(ang) * radius * ln)
        dy = int(np.sin(ang) * radius * ln)
        cv2.line(spikes, (cx - dx, cy - dy), (cx + dx, cy + dy), 0.9, 2, cv2.LINE_AA)
    add_rgb(canvas, gauss(spikes, 2.2) * power * 0.45, PINK_HOT)


def render_impact(canvas: np.ndarray, p, radius: float,
                  rng: np.random.Generator, from_dir=(1.0, -0.6)) -> None:
    """Астероид, который лучи режут на части: тело, раскалённый кратер,
    трещины, ударные волны, разлетающиеся обломки и искры."""
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = float(p[0]), float(p[1])
    d = np.hypot(xx - cx, yy - cy)
    ang = np.arctan2(yy - cy, xx - cx)

    fx, fy = from_dir
    fn = float(np.hypot(fx, fy)) or 1.0
    fx, fy = fx / fn, fy / fn
    hx, hy = cx + fx * radius * 0.62, cy + fy * radius * 0.62   # точка входа лучей
    dh = np.hypot(xx - hx, yy - hy)

    # --- тело астероида: сфера с шумовым рельефом ---
    noise = fbm(h, w, rng, 5, 7, 0.5)
    edge = radius * (0.94 + 0.085 * np.sin(ang * 3.0 + noise * 4.0)
                     + 0.045 * np.sin(ang * 7.0 + noise * 2.0))
    body = (d < edge).astype(np.float32)
    nz = np.sqrt(np.maximum(1.0 - np.minimum(d / radius, 1.0) ** 2, 0.0))
    lam = np.clip(((xx - cx) * fx + (yy - cy) * fy) / radius + 0.55 * nz, 0.0, 1.0)
    surf = (0.30 + 0.70 * noise) * (0.12 + 0.88 * lam)

    craters = np.zeros((h, w), np.float32)
    for _ in range(9):
        a = float(rng.uniform(0, 2 * np.pi))
        rr = float(rng.uniform(0.0, 0.75)) * radius
        cv2.circle(craters,
                   (int(cx + np.cos(a) * rr), int(cy + np.sin(a) * rr)),
                   int(max(3, rng.uniform(0.07, 0.20) * radius)), 1.0, -1)
    surf *= (1.0 - 0.45 * gauss(craters, 2.0)) * body

    body_soft = gauss(body, 1.2)[:, :, None]
    canvas *= (1.0 - body_soft * 0.97)
    add_rgb(canvas, gauss(surf, 1.0) * 0.72,
            np.array([0.56, 0.46, 0.60], np.float32))
    limb = body * np.clip((d / radius - 0.84) / 0.16, 0.0, 1.0) ** 2
    add_rgb(canvas, gauss(limb, 1.5) * 0.30, PINK_GLOW)

    # --- трещины, светящиеся изнутри ---
    cracks = np.zeros((h, w), np.float32)
    for _ in range(7):
        pts, rr, aa = [(hx, hy)], 0.0, float(rng.uniform(0, 2 * np.pi))
        while rr < radius * 1.15:
            rr += radius * 0.18
            aa += float(rng.uniform(-0.40, 0.40))
            pts.append((hx + np.cos(aa) * rr, hy + np.sin(aa) * rr))
        cv2.polylines(cracks, [np.array(pts, np.int32).reshape(-1, 1, 2)],
                      False, 1.0, 3, cv2.LINE_AA)
    cracks *= body
    add_rgb(canvas, gauss(cracks, 1.2) * 0.75, PINK_HOT)
    add_rgb(canvas, gauss(cracks, 6.0) * 0.30, PINK_GLOW)

    # --- раскалённый кратер и выброс плазмы навстречу лучам ---
    add_rgb(canvas, np.exp(-(dh ** 2) / (2 * (radius * 0.17) ** 2)) * 1.10, PINK_CORE)
    add_rgb(canvas, np.exp(-(dh ** 2) / (2 * (radius * 0.45) ** 2)) * 0.70, PINK_HOT)
    plume_t = np.clip(((xx - hx) * fx + (yy - hy) * fy) / (radius * 2.2), 0.0, 1.0)
    plume_r = np.abs((xx - hx) * fy - (yy - hy) * fx)
    plume = np.exp(-(plume_r ** 2) / (2 * (radius * (0.16 + 0.55 * plume_t)) ** 2))
    plume *= (1.0 - plume_t) * (plume_t > 0.01)
    add_rgb(canvas, plume * 0.85, PINK_HOT)
    add_rgb(canvas, np.exp(-(d ** 2) / (2 * (radius * 2.5) ** 2)) * 0.30, PINK_GLOW)

    # --- ударные волны ---
    for rad, amp, thick in ((radius * 1.50, 0.80, 0.050),
                            (radius * 2.30, 0.38, 0.085),
                            (radius * 3.25, 0.16, 0.135)):
        ring = np.exp(-((d - rad) ** 2) / (2 * (radius * thick) ** 2))
        add_rgb(canvas, ring * amp, np.array([1.0, 0.72, 0.98], np.float32))

    # --- обломки породы и искры ---
    sparks = np.zeros((h, w), np.float32)
    chunks = np.zeros((h, w), np.float32)
    for _ in range(130):
        a = float(rng.uniform(0, 2 * np.pi))
        r0 = float(rng.uniform(1.05, 3.4)) * radius
        r1 = r0 + float(rng.uniform(0.10, 0.45)) * radius
        cv2.line(sparks,
                 (int(cx + np.cos(a) * r0), int(cy + np.sin(a) * r0)),
                 (int(cx + np.cos(a) * r1), int(cy + np.sin(a) * r1)),
                 float(rng.uniform(0.3, 1.0)), 1, cv2.LINE_AA)
    for _ in range(18):
        a = float(rng.uniform(0, 2 * np.pi))
        rr = float(rng.uniform(1.20, 3.1)) * radius
        size = int(max(2, rng.uniform(0.05, 0.13) * radius))
        cv2.circle(chunks, (int(cx + np.cos(a) * rr), int(cy + np.sin(a) * rr)),
                   size, 1.0, -1)
    chunk_soft = gauss(chunks, 1.0)[:, :, None]
    canvas *= (1.0 - chunk_soft * 0.80)
    add_rgb(canvas, gauss(chunks, 1.0) * 0.40, np.array([0.50, 0.36, 0.58], np.float32))
    add_rgb(canvas, gauss(chunks, 3.0) * 0.30, PINK_GLOW)
    add_rgb(canvas, gauss(sparks, 1.0) * 0.55, PINK_HOT)
    add_rgb(canvas, gauss(sparks, 5.0) * 0.22, PINK_GLOW)


def render_beam_rocks(canvas: np.ndarray, p0, p1, rng: np.random.Generator,
                      count: int = 3) -> None:
    """Мелкие астероиды на пути лучей: раскалённый срез и разлёт искр."""
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ax, ay = float(p0[0]), float(p0[1])
    bx, by = float(p1[0]), float(p1[1])
    vx, vy = bx - ax, by - ay
    vlen = float(np.hypot(vx, vy)) or 1.0
    ux, uy = vx / vlen, vy / vlen
    for i in range(count):
        t = float(rng.uniform(0.50, 0.96))
        side = float(np.sign(rng.uniform(-1, 1))) * float(rng.uniform(0.025, 0.065)) * w
        cx = ax + ux * vlen * t - uy * side
        cy = ay + uy * vlen * t + ux * side
        radius = float(rng.uniform(0.014, 0.028)) * w
        d = np.hypot(xx - cx, yy - cy)
        ang = np.arctan2(yy - cy, xx - cx)
        noise = fbm(h, w, rng, 4, 8, 0.5)
        edge = radius * (0.92 + 0.14 * np.sin(ang * 3.0 + noise * 4.0))
        body = (d < edge).astype(np.float32)
        nz = np.sqrt(np.maximum(1.0 - np.minimum(d / radius, 1.0) ** 2, 0.0))
        lam = np.clip(-((xx - cx) * ux + (yy - cy) * uy) / radius + 0.6 * nz, 0.0, 1.0)
        surf = body * (0.28 + 0.72 * noise) * (0.10 + 0.90 * lam)
        canvas *= (1.0 - gauss(body, 1.0)[:, :, None] * 0.95)
        add_rgb(canvas, gauss(surf, 1.0) * 0.95,
                np.array([0.62, 0.52, 0.66], np.float32))
        # раскалённая кромка со стороны луча
        hot = body * np.clip(-((xx - cx) * ux + (yy - cy) * uy) / radius, 0.0, 1.0) ** 2
        add_rgb(canvas, gauss(hot, 1.2) * 1.10, PINK_HOT)
        add_rgb(canvas, gauss(hot, 6.0) * 0.55, PINK_GLOW)
        sparks = np.zeros((h, w), np.float32)
        for _ in range(26):
            a = float(rng.uniform(0, 2 * np.pi))
            r0 = float(rng.uniform(1.0, 2.6)) * radius
            r1 = r0 + float(rng.uniform(0.15, 0.6)) * radius
            cv2.line(sparks,
                     (int(cx + np.cos(a) * r0), int(cy + np.sin(a) * r0)),
                     (int(cx + np.cos(a) * r1), int(cy + np.sin(a) * r1)),
                     float(rng.uniform(0.3, 1.0)), 1, cv2.LINE_AA)
        add_rgb(canvas, gauss(sparks, 1.0) * 0.45, PINK_HOT)
        add_rgb(canvas, gauss(sparks, 5.0) * 0.18, PINK_GLOW)


def render_asteroid_field(canvas: np.ndarray, rng: np.random.Generator,
                          count: int = 14) -> None:
    """Мелкие камни, дрейфующие в нижней части кадра."""
    h, w = canvas.shape[:2]
    rocks = np.zeros((h, w), np.float32)
    lit = np.zeros((h, w), np.float32)
    for _ in range(count):
        cx = int(rng.uniform(0.02, 0.55) * w)
        cy = int(rng.uniform(0.70, 0.99) * h)
        r = int(rng.uniform(0.004, 0.017) * w)
        cv2.circle(rocks, (cx, cy), r, 1.0, -1)
        cv2.circle(lit, (cx - r // 3, cy - r // 3), max(1, r // 2), 1.0, -1)
    canvas *= (1.0 - gauss(rocks, 1.0)[:, :, None] * 0.85)
    add_rgb(canvas, gauss(rocks, 1.0) * 0.16, np.array([0.45, 0.34, 0.55], np.float32))
    add_rgb(canvas, gauss(lit, 1.2) * 0.30, PINK_GLOW)


def render_arcs(canvas: np.ndarray, char_alpha: np.ndarray,
                rng: np.random.Generator, count: int = 14) -> None:
    """Электрические разряды, ломающиеся вдоль силуэта персонажа."""
    h, w = canvas.shape[:2]
    solid = (char_alpha > 0.5).astype(np.uint8)
    band = cv2.dilate(solid, _ellipse_kernel(17)) - solid
    ys, xs = np.nonzero(band)
    if ys.size == 0:
        return
    layer = np.zeros((h, w), np.float32)
    for _ in range(count):
        i = int(rng.integers(0, ys.size))
        x, y = float(xs[i]), float(ys[i])
        ang = float(rng.uniform(0, 2 * np.pi))
        pts = [(x, y)]
        for _ in range(int(rng.integers(3, 6))):
            ang += float(rng.uniform(-1.1, 1.1))
            step = float(rng.uniform(0.008, 0.020)) * w
            x += np.cos(ang) * step
            y += np.sin(ang) * step
            pts.append((x, y))
        cv2.polylines(layer, [np.array(pts, np.int32).reshape(-1, 1, 2)],
                      False, float(rng.uniform(0.6, 1.0)), 3, cv2.LINE_AA)
    add_rgb(canvas, gauss(layer, 1.0) * 1.25, PINK_CORE)
    add_rgb(canvas, gauss(layer, 5.0) * 0.95, PINK_HOT)
    add_rgb(canvas, gauss(layer, 20.0) * 0.55, PINK_GLOW)


def render_embers(canvas: np.ndarray, rng: np.random.Generator,
                  count: int = 150) -> None:
    """Парящие искры-угольки по всему кадру — воздух «живой»."""
    h, w = canvas.shape[:2]
    layer = np.zeros((h, w), np.float32)
    for _ in range(count):
        x, y = int(rng.integers(0, w)), int(rng.integers(0, h))
        mag = float(rng.random()) ** 2.5
        cv2.circle(layer, (x, y), 1 + int(mag * 3.0), 0.25 + mag, -1)
    add_rgb(canvas, gauss(layer, 0.9) * 0.32, PINK_CORE)
    add_rgb(canvas, gauss(layer, 4.0) * 0.45, PINK_HOT)


def render_silhouette_flames(canvas: np.ndarray, char_alpha: np.ndarray,
                             rng: np.random.Generator) -> None:
    """Плазменное пламя, срывающееся с силуэта персонажа вверх."""
    h, w = canvas.shape[:2]
    step = max(2, h // 260)
    acc = np.zeros((h, w), np.float32)
    base = gauss(char_alpha, 2.0)
    for i in range(1, 30):
        shifted = np.roll(base, -i * step, axis=0)
        shifted[h - i * step:, :] = 0.0
        acc = np.maximum(acc, shifted * (1.0 - i / 30.0) ** 1.7)
    turb = fbm(h, w, rng, 6, 6, 0.55, warp=30.0)
    acc = np.clip(acc * (0.18 + 1.85 * turb) - 0.16, 0.0, 1.0) ** 1.25
    acc *= (1.0 - char_alpha)
    acc = gauss(acc, 2.0)
    add_rgb(canvas, acc * 0.85, PINK_GLOW)
    add_rgb(canvas, (acc ** 2.2) * 0.80, PINK_HOT)
    add_rgb(canvas, (acc ** 5.0) * 0.60, np.array([1.0, 0.88, 0.80], np.float32))


def render_plasma_fire(canvas: np.ndarray, rng: np.random.Generator) -> None:
    """Плазменное пламя вдоль нижней кромки кадра."""
    h, w = canvas.shape[:2]
    yy = np.mgrid[0:h, 0:w][0].astype(np.float32) / h
    n1 = fbm(h, w, rng, 6, 5, 0.55, warp=55.0)
    n2 = fbm(h, w, rng, 5, 9, 0.50, warp=35.0)
    rise = smoothstep(0.74, 1.06, yy)
    flame = np.clip((n1 * 0.65 + n2 * 0.35) * 1.6 - (1.0 - rise) * 1.25, 0.0, 1.0)
    flame = flame ** 1.5
    add_rgb(canvas, flame * 0.48, PINK_GLOW)
    add_rgb(canvas, (flame ** 2.4) * 0.42, PINK_HOT)
    add_rgb(canvas, (flame ** 5.0) * 0.26, np.array([1.0, 0.85, 0.72], np.float32))


# ----------------------------------------------------------------------------
# 4. Компоновка персонажа
# ----------------------------------------------------------------------------

def place_character(canvas: np.ndarray, rgb: np.ndarray, alpha: np.ndarray,
                    scale: float, offset: tuple[int, int],
                    rng: np.random.Generator) -> np.ndarray:
    """Вклеивает персонажа, возвращает его альфу в координатах холста."""
    h, w = canvas.shape[:2]
    sh, sw = alpha.shape
    nw, nh = int(round(sw * scale)), int(round(sh * scale))
    rgb_s = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LANCZOS4)
    a_s = cv2.resize(alpha, (nw, nh), interpolation=cv2.INTER_LINEAR)

    # Лёгкий unsharp — компенсируем апскейл.
    rgb_s = np.clip(rgb_s * 1.32 - gauss(rgb_s, 2.2) * 0.32, 0.0, 1.4)

    ox, oy = offset
    full_rgb = np.zeros((h, w, 3), np.float32)
    full_a = np.zeros((h, w), np.float32)
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(w, ox + nw), min(h, oy + nh)
    if x0 >= x1 or y0 >= y1:
        raise SystemExit("[fx] персонаж вне холста — проверьте scale/offset")
    full_rgb[y0:y1, x0:x1] = rgb_s[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    full_a[y0:y1, x0:x1] = a_s[y0 - oy:y1 - oy, x0 - ox:x1 - ox]

    # Цветокоррекция: холодные тени, пурпурный подсвет, контраст.
    graded = full_rgb.copy()
    luma = graded @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    graded = np.clip((graded - 0.48) * 1.26 + 0.48, 0.0, 1.5)
    graded[:, :, 0] += (1.0 - luma) * 0.08
    graded[:, :, 2] += (1.0 - luma) * 0.14
    graded[:, :, 1] *= 0.93
    luma2 = (graded @ np.array([0.2126, 0.7152, 0.0722], np.float32))[:, :, None]
    graded = np.clip(luma2 + (graded - luma2) * 1.30, 0.0, 1.6)   # сочнее

    # Контровой свет: розовый слева-снизу (от взрыва), голубой справа-сверху.
    gx = cv2.Sobel(full_a, cv2.CV_32F, 1, 0, ksize=5)
    gy = cv2.Sobel(full_a, cv2.CV_32F, 0, 1, ksize=5)
    edge = np.clip(np.hypot(gx, gy), 0.0, 1.0)
    edge_band = gauss(edge, 2.0)
    for (lx, ly), color, power in (((-0.80, 0.60), PINK_HOT, 0.55),
                                   ((0.78, -0.62), CYAN_RIM, 0.40)):
        facing = np.clip(-(gx * lx + gy * ly), 0.0, None)
        facing = facing / (facing.max() + 1e-6)
        add_rgb(graded, facing * edge_band * power * 1.6, color)

    # Наружное свечение силуэта.
    glow = gauss(full_a, 22.0) * 0.16 + gauss(full_a, 64.0) * 0.07
    add_rgb(canvas, glow * (1.0 - full_a), PINK_GLOW)

    a3 = np.clip(full_a, 0.0, 1.0)[:, :, None]
    np.multiply(canvas, 1.0 - a3, out=canvas)
    canvas += graded * a3
    return np.clip(full_a, 0.0, 1.0)


# ----------------------------------------------------------------------------
# 5. Пост-обработка
# ----------------------------------------------------------------------------

def post_process(img: np.ndarray, rng: np.random.Generator, *,
                 glitch: bool = True) -> np.ndarray:
    h, w = img.shape[:2]
    out = img.copy()

    # Bloom на нескольких масштабах.
    bright = np.clip(out - 0.80, 0.0, None)
    bloom = (gauss(bright, 6.0) * 0.30 + gauss(bright, 18.0) * 0.22 +
             gauss(bright, 48.0) * 0.16)
    out += bloom

    # Хроматическая аберрация.
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = w / 2.0, h / 2.0
    for ch, k in ((0, 1.0032), (2, 0.9970)):
        mx = cx + (xx - cx) * k
        my = cy + (yy - cy) * k
        out[:, :, ch] = cv2.remap(out[:, :, ch], mx, my, cv2.INTER_LINEAR,
                                  borderMode=cv2.BORDER_REFLECT)

    if glitch:
        for _ in range(4):
            y = int(rng.integers(0, h - 12))
            hh = int(rng.integers(3, 14))
            shift = int(rng.integers(-9, 10))
            band = out[y:y + hh]
            out[y:y + hh, :, 0] = np.roll(band[:, :, 0], shift, axis=1)
            out[y:y + hh, :, 2] = np.roll(band[:, :, 2], -shift, axis=1)

    # Виньетка.
    r = np.hypot((xx - cx) / cx, (yy - cy) / cy)
    out *= (1.0 - 0.42 * smoothstep(0.55, 1.45, r))[:, :, None]

    # Тон-маппинг (фильмик) + насыщенность.
    out = np.clip(out, 0.0, None)
    out = out / (1.0 + out) * 1.12
    luma = (out @ np.array([0.2126, 0.7152, 0.0722], np.float32))[:, :, None]
    out = np.clip(luma + (out - luma) * 1.26, 0.0, 1.0)
    out = np.clip((out - 0.45) * 1.14 + 0.45, 0.0, 1.0)     # плотнее тени

    # Зерно.
    grain = rng.normal(0.0, 0.016, (h, w, 1)).astype(np.float32)
    out = np.clip(out + grain, 0.0, 1.0)
    return out


def draw_caption(img: np.ndarray, text: str) -> np.ndarray:
    """Крупная фонк-подпись сверху (Cyrillic-safe)."""
    from PIL import Image, ImageDraw, ImageFont

    h, w = img.shape[:2]
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    draw = ImageDraw.Draw(pil)
    candidates = (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
    )
    font_path = next((c for c in candidates if os.path.isfile(c)), None)
    if font_path is None:
        raise SystemExit("[fx] не нашёл жирный TTF с кириллицей для подписи")
    size = int(w * 0.105)
    while size > 10:
        font = ImageFont.truetype(font_path, size)
        bbox = draw.textbbox((0, 0), text, font=font, stroke_width=max(2, size // 16))
        if bbox[2] - bbox[0] <= w * 0.92:
            break
        size -= 2
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    x = (w - tw) / 2 - bbox[0]
    y = h * 0.028 - bbox[1]
    sw = max(2, size // 16)
    for dx, dy, col in ((-11, 4, (255, 30, 165)), (11, -4, (70, 225, 255))):
        draw.text((x + dx, y + dy), text, font=font, fill=col)
    draw.text((x, y), text, font=font, fill=(255, 255, 255),
              stroke_width=sw, stroke_fill=(70, 0, 48))
    return np.asarray(pil).astype(np.float32) / 255.0


# ----------------------------------------------------------------------------
# 6. Сцена целиком
# ----------------------------------------------------------------------------

def border_ramp(shape: tuple[int, int], band: int) -> np.ndarray:
    """Линейное затухание к краям кадра исходника."""
    h, w = shape
    band = max(1, min(band, h // 2, w // 2))
    k = np.linspace(0.0, 1.0, band, dtype=np.float32)
    rx = np.ones(w, np.float32)
    ry = np.ones(h, np.float32)
    rx[:band], rx[-band:] = k, k[::-1]
    ry[:band], ry[-band:] = k, k[::-1]
    return np.minimum(ry[:, None], rx[None, :])


def paste_scaled(src: np.ndarray, scale: float, offset: tuple[int, int],
                 shape: tuple[int, int]) -> np.ndarray:
    """Масштабирует карту и кладёт её на холст по тем же правилам, что и
    персонажа (нужно, чтобы маски совпадали пиксель в пиксель)."""
    h, w = shape
    sh, sw = src.shape[:2]
    nw, nh = int(round(sw * scale)), int(round(sh * scale))
    res = cv2.resize(src, (nw, nh), interpolation=cv2.INTER_LINEAR)
    out = np.zeros((h, w), np.float32)
    ox, oy = offset
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(w, ox + nw), min(h, oy + nh)
    if x0 < x1 and y0 < y1:
        out[y0:y1, x0:x1] = res[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    return out


def render_dissolve(canvas: np.ndarray, band_mask: np.ndarray,
                    rng: np.random.Generator, count: int = 380) -> None:
    """Срезы кадра исходника рассыпаются искрами — «дезинтеграция»,
    вместо прямой отрубленной грани."""
    h, w = canvas.shape[:2]
    weight = band_mask.astype(np.float64).ravel()
    total = weight.sum()
    if total <= 0:
        return
    idx = rng.choice(weight.size, size=min(count, weight.size),
                     replace=False, p=weight / total)
    ys, xs = np.unravel_index(idx, (h, w))
    layer = np.zeros((h, w), np.float32)
    jitter = rng.normal(0.0, 6.0, (2, ys.size))
    for x, y, jx, jy, mag in zip(xs, ys, jitter[0], jitter[1],
                                 rng.random(ys.size) ** 2.2):
        px, py = int(x + jx), int(y + jy)
        if 0 <= px < w and 0 <= py < h:
            cv2.circle(layer, (px, py), 1 + int(mag * 2.4), float(0.35 + mag), -1)
    add_rgb(canvas, gauss(layer, 0.8) * 0.85, PINK_CORE)
    add_rgb(canvas, gauss(layer, 3.5) * 0.55, PINK_HOT)
    add_rgb(canvas, gauss(layer, 14.0) * 0.30, PINK_GLOW)


def compose(src_bgr: np.ndarray, width: int, height: int, seed: int,
            caption: str | None) -> np.ndarray:
    rng = np.random.default_rng(seed)

    rgb, alpha_raw = extract_character(src_bgr)
    ramp = border_ramp(alpha_raw.shape, 9)
    alpha = alpha_raw * ramp
    cut_src = ((ramp < 0.60) & (alpha_raw > 0.30)).astype(np.float32)
    ys, xs = np.nonzero(alpha > 0.35)
    if ys.size == 0:
        raise SystemExit("[fx] персонаж не найден на исходнике")
    sy0, sy1 = int(ys.min()), int(ys.max()) + 1
    sx0, sx1 = int(xs.min()), int(xs.max()) + 1

    # Кадрируем так, чтобы все три среза исходного кадра (левый, правый и
    # верхний) ушли ровно за края холста — прямых «отрубленных» граней не видно.
    scale = min((height * 0.920) / (sy1 - sy0), (width * 1.000) / (sx1 - sx0))
    off_x = int(round((width - (sx1 - sx0) * scale) / 2.0))
    off_y = 0

    def to_canvas(pt):
        return (off_x + (pt[0] - sx0) * scale, off_y + (pt[1] - sy0) * scale)

    eyes = [to_canvas(p) for p in EYE_SRC]
    target = (-width * 0.08, height * 0.470)

    # --- дальний план ---
    canvas = render_space(height, width, rng)
    render_planet(canvas, width * 1.06, height * 0.055, height * 0.235, rng,
                  base=(0.62, 0.18, 0.70), rings=True, light=(-0.75, 0.30))
    render_planet(canvas, width * 0.115, height * 0.905, height * 0.036, rng,
                  base=(0.42, 0.40, 0.55), rings=False, light=(-0.6, -0.5))
    render_warp_streaks(canvas, eyes[0][0], eyes[0][1], rng)
    render_portal_ring(canvas, width * 0.52, height * 0.60,
                       width * 0.52, height * 0.19, rng)
    render_asteroid_field(canvas, rng)

    # --- персонаж ---
    char_alpha = place_character(canvas, rgb[sy0:sy1, sx0:sx1],
                                 alpha[sy0:sy1, sx0:sx1], scale,
                                 (off_x, off_y), rng)

    # --- лазеры и взрыв поверх ---
    cut_band = paste_scaled(cut_src[sy0:sy1, sx0:sx1], scale, (off_x, off_y),
                            (height, width))
    render_dissolve(canvas, cut_band, rng)

    render_silhouette_flames(canvas, char_alpha, rng)
    render_arcs(canvas, char_alpha, rng)
    render_embers(canvas, rng)

    render_beam_rocks(canvas, eyes[0], target, rng)
    # Оба луча идут параллельно: цель каждого смещена на его же
    # отступ от центра глаз — тогда видно именно два луча, а не один.
    mid = ((eyes[0][0] + eyes[1][0]) / 2.0, (eyes[0][1] + eyes[1][1]) / 2.0)
    for eye in eyes:
        aim = (target[0] + (eye[0] - mid[0]) * 1.35,
               target[1] + (eye[1] - mid[1]) * 1.35)
        render_laser(canvas, eye, aim, rng, width=width * 0.0062)

    render_plasma_fire(canvas, rng)

    # Розовый отсвет лучей, «прилипающий» к силуэту персонажа.
    spill = np.zeros((height, width), np.float32)
    cv2.line(spill, (int(eyes[0][0]), int(eyes[0][1])),
             (int(target[0]), int(target[1])), 1.0, int(width * 0.05), cv2.LINE_AA)
    spill = gauss(spill, width * 0.04) * char_alpha
    add_rgb(canvas, spill * 0.15, PINK_HOT)

    # Морда должна быть освещена собственными лучами.
    face = np.zeros((height, width), np.float32)
    cv2.circle(face, (int(eyes[0][0]), int(eyes[0][1])),
               int(width * 0.030), 1.0, -1)
    face = gauss(face, width * 0.028) * char_alpha
    face /= max(float(face.max()), 1e-6)
    add_rgb(canvas, face * 0.16, PINK_HOT)
    add_rgb(canvas, face ** 3 * 0.09, PINK_CORE)

    out = post_process(canvas, rng)
    if caption:
        out = draw_caption(out, caption)
    return out


# ----------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------

def _clamp(value: int, lo: int, hi: int, name: str) -> int:
    if not lo <= value <= hi:
        raise SystemExit(f"[fx] {name} вне диапазона [{lo}, {hi}]: {value}")
    return value


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Oliver-200 FX: розовые лазеры из глаз + космос + ультра-эффекты")
    ap.add_argument("--src", required=True, help="исходное фото персонажа")
    ap.add_argument("--out", required=True, help="куда записать PNG")
    ap.add_argument("--width", type=int, default=1400)
    ap.add_argument("--height", type=int, default=1750)
    ap.add_argument("--seed", type=int, default=2077)
    ap.add_argument("--caption", default=None,
                    help="крупная подпись сверху (например «Я ФИКСИРУЮ»)")
    ap.add_argument("--cutout", default=None,
                    help="дополнительно сохранить вырезанного персонажа (PNG RGBA)")
    args = ap.parse_args(argv)

    width = _clamp(args.width, 256, 6000, "--width")
    height = _clamp(args.height, 256, 6000, "--height")
    if width * height > MAX_CANVAS_PIXELS:
        raise SystemExit("[fx] запрошенный холст слишком велик")
    seed = _clamp(args.seed, 0, 2 ** 31 - 1, "--seed")

    src_path = _safe_path(args.src, must_exist=True)
    out_path = _safe_path(args.out, must_exist=False)

    src = load_image(src_path)
    print(f"[fx] {SIGNATURE}: исходник {src.shape[1]}x{src.shape[0]}")

    if args.cutout:
        cut_path = _safe_path(args.cutout, must_exist=False)
        if cut_path == out_path:
            raise SystemExit("[fx] --cutout и --out не могут совпадать")
        rgb, alpha = extract_character(src)
        rgba = np.dstack([
            (np.clip(rgb, 0, 1) * 255).astype(np.uint8)[:, :, ::-1],
            (np.clip(alpha, 0, 1) * 255).astype(np.uint8),
        ])
        save_image_atomic(cut_path, rgba)
        print(f"[fx] вырезка: {cut_path}")

    out = compose(src, width, height, seed, args.caption)
    bgr = (np.clip(out, 0, 1) * 255.0 + 0.5).astype(np.uint8)[:, :, ::-1]
    save_image_atomic(out_path, bgr)
    print(f"[fx] готово: {out_path} ({width}x{height})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
