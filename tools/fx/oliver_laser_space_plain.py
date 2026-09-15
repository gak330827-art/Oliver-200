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

# Стопка подбородков: откуда берётся «кожа» и где начинается каскад валиков
# (всё в координатах исходника).
CHIN_SKIN_RECT = (258, 150, 400, 232)      # x0, y0, x1, y1 — участок под челюстью
CHIN_ORIGIN = (302.0, 210.0)               # центр самого верхнего валика
CHIN_HALF = (64.0, 18.0)                   # половина ширины и высоты валика
CHIN_STEP = 19.0                           # шаг каскада вниз
CHIN_GROW = 0.105                          # каждый следующий валик шире
CHIN_DRIFT_X = 4.0                         # снос вправо, вдоль корпуса

# Камера для сгенерированного персонажа (мир: Y вверх, ступни на y=0).
CAM_TARGET = (-0.85, 3.30, 0.0)
CAM_DIR = (0.2205, 0.1103, 0.9691)         # нормированное направление на камеру
CAM_DIST = 21.0
CAM_FOV = 32.0
RENDER_SSAA = 2

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
    """Плавная ступенька. Работает и «наоборот», когда edge0 > edge1:
    раньше знаменатель подрезался снизу через max(...), из-за чего убывающая
    ступенька вырождалась в жёсткий инвертированный порог."""
    span = edge1 - edge0
    if abs(span) < 1e-9:
        span = 1e-9 if span >= 0 else -1e-9
    t = np.clip((x - edge0) / span, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def add_rgb(dst: np.ndarray, intensity: np.ndarray, color: np.ndarray) -> None:
    dst += intensity[:, :, None] * color[None, None, :]


def gauss(img: np.ndarray, sigma: float) -> np.ndarray:
    if sigma <= 0:
        return img
    return cv2.GaussianBlur(img, (0, 0), sigma, borderType=cv2.BORDER_REPLICATE)


# ============================================================================
#  ГЕНЕРАЦИЯ ПЕРСОНАЖА: геометрия + собственный софтверный растеризатор
#  Ни одного пикселя из фотографии — только математика.
# ============================================================================

M_FUR, M_SHIRT, M_CAP, M_MUZZLE, M_DARK, M_SHOE, M_SOLE, M_GOLD, M_BOWL, M_GARLIC = range(10)

def rot_x(a):
    c,s=np.cos(a),np.sin(a); return np.array([[1,0,0],[0,c,-s],[0,s,c]],np.float32)
def rot_y(a):
    c,s=np.cos(a),np.sin(a); return np.array([[c,0,s],[0,1,0],[-s,0,c]],np.float32)
def rot_z(a):
    c,s=np.cos(a),np.sin(a); return np.array([[c,-s,0],[s,c,0],[0,0,1]],np.float32)

CUBE = np.array([[-1,-1,-1],[1,-1,-1],[1,1,-1],[-1,1,-1],
                 [-1,-1, 1],[1,-1, 1],[1,1, 1],[-1,1, 1]],np.float32)*0.5
QUADS = [(0,3,2,1,(0,0,-1)),(4,5,6,7,(0,0,1)),(0,1,5,4,(0,-1,0)),
         (3,7,6,2,(0,1,0)),(0,4,7,3,(-1,0,0)),(1,2,6,5,(1,0,0))]

class Scene:
    """Накопитель геометрии: мир, локальные координаты (для текстур), грани."""
    def __init__(self):
        self.V=[]; self.L=[]; self.F=[]; self.N=[]; self.M=[]; self.n=0

    def push(self, world, local, R, mat):
        base=self.n                      # именно число ВЕРШИН, не коробок
        self.V.append(world); self.L.append(local); self.n+=8
        for a,b,c,d,nrm in QUADS:
            nn=np.asarray(nrm,np.float32)@R.T
            self.F.append((base+a,base+b,base+c)); self.N.append(nn); self.M.append(mat)
            self.F.append((base+a,base+c,base+d)); self.N.append(nn); self.M.append(mat)

    def box(self, center, size, mat, R=None, pivot=None):
        R = np.eye(3,dtype=np.float32) if R is None else R
        c=np.asarray(center,np.float32); local=CUBE*np.asarray(size,np.float32)
        if pivot is None:
            world = local@R.T + c
        else:
            pv=np.asarray(pivot,np.float32)
            world = (local + (c-pv))@R.T + pv
        self.push(world, local, R, mat)

    def finish(self):
        self.V=np.concatenate(self.V,0); self.L=np.concatenate(self.L,0)
        self.F=np.array(self.F,np.int32); self.N=np.array(self.N,np.float32)
        self.M=np.array(self.M,np.int32)
        self.N/= (np.linalg.norm(self.N,axis=1,keepdims=True)+1e-9)


# ----------------------------------------------------------------- персонаж
# Блочный шрифт 3x5: надпись на кепке набирается коробками, а не текстурой.
BLOCK_FONT = {
    "O": ("111", "101", "101", "101", "111"),
    "L": ("100", "100", "100", "100", "111"),
    "I": ("111", "010", "010", "010", "111"),
    "V": ("101", "101", "101", "101", "010"),
    "E": ("111", "100", "110", "100", "111"),
    "R": ("110", "101", "110", "101", "101"),
    "2": ("111", "001", "111", "100", "111"),
    "0": ("111", "101", "101", "101", "111"),
    "-": ("000", "000", "111", "000", "000"),
    " ": ("000", "000", "000", "000", "000"),
}


def block_text_cells(text: str):
    """Ячейки надписи: (колонка, строка) для каждого зажжённого пикселя."""
    cells, col = [], 0
    for ch in text.upper():
        glyph = BLOCK_FONT.get(ch)
        if glyph is None:
            col += 4
            continue
        for row, bits in enumerate(glyph):
            for i, bit in enumerate(bits):
                if bit == "1":
                    cells.append((col + i, row))
        col += 4
    width = max(col - 1, 1)
    return cells, width


def build(chins: int = 10, cap_text: str = "OLIVER"):
    """Собирает персонажа из коробок. Все размеры выведены из пропорций,
    чтобы правки не рассыпали остальную фигуру."""
    sc = Scene()
    body = rot_y(np.deg2rad(-26.0))

    LEG_H   = 1.62                      # коротконогий, «квадратный» силуэт
    TORSO_H = 2.35
    torso_y = LEG_H + TORSO_H * 0.5
    neck_y  = LEG_H + TORSO_H + 0.22
    head_y  = neck_y + 1.28
    sh_y    = LEG_H + TORSO_H - 0.10    # высота плеча
    ARM_H   = 2.10

    def B(center, size, mat, extra=None, pivot=None):
        R = body if extra is None else body @ extra
        c = np.asarray(center, np.float32)
        if pivot is None:
            sc.box(c @ body.T, size, mat, R)
        else:
            pv = np.asarray(pivot, np.float32)
            local = CUBE * np.asarray(size, np.float32)
            world = (local + (c - pv)) @ R.T + pv @ body.T
            sc.push(world, local, R, mat)

    # ноги и кроссовки
    for sx in (-1, 1):
        B((0.66 * sx, LEG_H * 0.5, 0.0), (1.02, LEG_H + 0.1, 1.08), M_FUR)
        B((0.66 * sx, 0.32, 0.20), (1.18, 0.62, 1.56), M_SHOE)
        B((0.66 * sx, 0.08, 0.20), (1.22, 0.20, 1.60), M_SOLE)
    # корпус и шея
    B((0.0, torso_y, 0.0), (2.95, TORSO_H, 1.75), M_SHIRT)
    B((0.0, neck_y, 0.0), (1.00, 0.60, 1.00), M_FUR)

    # руки: знак поворота зависит от стороны, иначе обе уходят внутрь корпуса
    hands = {}
    for sx, ang_z, ang_x in ((-1, -46.0, -6.0), (1, 34.0, 10.0)):
        Rarm = rot_z(np.deg2rad(ang_z)) @ rot_x(np.deg2rad(ang_x))
        shoulder = np.array([1.74 * sx, sh_y, 0.0], np.float32)
        B(shoulder + np.array([0, -ARM_H * 0.5, 0], np.float32),
          (0.96, ARM_H, 1.00), M_FUR, extra=Rarm, pivot=shoulder)
        B(shoulder + np.array([0, -0.30, 0], np.float32),
          (1.10, 0.84, 1.12), M_SHIRT, extra=Rarm, pivot=shoulder)
        hands[sx] = shoulder + np.array([0, -ARM_H, 0], np.float32) @ Rarm.T

    # голова и всё, что на ней
    head = rot_y(np.deg2rad(-18.0)) @ rot_x(np.deg2rad(10.0))
    hc = np.array([0.0, head_y, 0.0], np.float32)
    Rh = body @ head

    def H(off, size, mat):
        local = CUBE * np.asarray(size, np.float32)
        world = (local + np.asarray(off, np.float32)) @ Rh.T + hc @ body.T
        sc.push(world, local, Rh, mat)

    H((0, 0, 0), (1.95, 1.75, 1.75), M_FUR)
    H((-0.20, -0.26, 1.08), (1.08, 0.82, 0.80), M_MUZZLE)   # морда
    H((-0.20, -0.40, 1.48), (0.44, 0.34, 0.22), M_DARK)     # нос
    for sx in (-1, 1):
        H((0.60 * sx, 0.98, -0.10), (0.42, 0.56, 0.30), M_FUR)      # уши
    for sx in (-1, 1):
        H((0.44 * sx - 0.06, 0.20, 0.90), (0.44, 0.14, 0.10), M_DARK)  # глаза
    for sx in (-1, 1):                                       # насупленные брови
        H((0.44 * sx - 0.06, 0.34, 0.88), (0.50, 0.09, 0.09), M_DARK)
    H((0.0, 0.86, -0.05), (2.08, 0.62, 1.86), M_CAP)        # кепка
    H((-0.06, 0.62, 1.20), (1.94, 0.18, 1.30), M_CAP)       # козырёк

    if cap_text:                                             # надпись на кепке
        cells, tw = block_text_cells(cap_text)
        cell = min(1.34 / max(tw, 1), 0.072)
        x_left = -0.5 * tw * cell
        y_top = 1.02
        for cx_i, row in cells:
            H((x_left + (cx_i + 0.5) * cell, y_top - (row + 0.5) * cell, 0.905),
              (cell * 0.92, cell * 0.92, 0.07), M_SOLE)

    # цепь и подвеска
    for k in range(18):
        a = 2 * np.pi * k / 18
        B((np.sin(a) * 1.35, neck_y - 0.20, np.cos(a) * 0.95),
          (0.23, 0.23, 0.23), M_GOLD)
    B((0.10, neck_y - 0.72, 0.92), (0.32, 0.44, 0.24), M_GOLD)

    # каскад подбородков: валики перекрывают друг друга, поэтому верхние
    # грани не видны и стопка читается складками, а не лесенкой
    for i in range(1, chins + 1):
        t = i / float(chins)
        # каждый следующий валик шире и чуть сильнее нависает вперёд: камера
        # смотрит сверху, поэтому его верхняя грань ловит свет и между
        # складками появляется читаемая линия
        B((-0.12, head_y - 0.70 - 0.205 * i, 1.00 + 0.22 * t),
          (0.90 + 1.30 * t, 0.26, 0.60 + 0.30 * t), M_FUR)

    # миска с чесноком — в поднятой руке
    hl = hands[-1]
    B((hl[0] - 0.10, hl[1] + 0.22, hl[2] + 0.35), (1.05, 0.38, 1.05), M_BOWL)
    B((hl[0] - 0.10, hl[1] + 0.58, hl[2] + 0.35), (0.66, 0.52, 0.66), M_GARLIC)
    # указательный палец — опущенная рука
    hr = hands[1]
    B((hr[0] + 0.10, hr[1] - 0.45, hr[2] + 0.30), (0.34, 0.95, 0.34), M_FUR)

    sc.finish()
    eyes = np.array([(np.array([0.44 * sx - 0.06, 0.20, 1.02], np.float32) @ Rh.T)
                     + hc @ body.T for sx in (-1, 1)], np.float32)
    return sc, eyes


# ----------------------------------------------------------------- камера
def look_at(eye,target,up=(0,1,0)):
    eye=np.asarray(eye,np.float32); target=np.asarray(target,np.float32)
    f=target-eye; f/=np.linalg.norm(f)
    r=np.cross(f,np.asarray(up,np.float32)); r/=np.linalg.norm(r)
    u=np.cross(r,f)
    return np.stack([r,u,-f],0), eye

def project(P,R,eye,W,H,fov_deg):
    cam=(P-eye)@R.T
    z=-cam[:,2]
    fl=1.0/np.tan(np.deg2rad(fov_deg)*0.5)
    aspect=W/float(H)
    zz=np.maximum(z,1e-4)
    sx=(cam[:,0]/zz*fl/aspect*0.5+0.5)*W
    sy=(0.5-cam[:,1]/zz*fl*0.5)*H
    return sx,sy,z

# ----------------------------------------------------------------- 3D-шум
def _hash3(ix,iy,iz):
    n=(ix*np.int64(374761393)+iy*np.int64(668265263)+iz*np.int64(1274126177))
    n=(n ^ (n>>np.int64(13)))*np.int64(1274126177)
    n=n ^ (n>>np.int64(16))
    return (n & np.int64(0xFFFFFF)).astype(np.float32)/float(0xFFFFFF)

def vnoise3(p):
    i=np.floor(p).astype(np.int64); f=p-i
    f=f*f*(3.0-2.0*f)
    x0,y0,z0=i[:,0],i[:,1],i[:,2]
    out=0.0
    for dz in (0,1):
        for dy in (0,1):
            for dx in (0,1):
                h=_hash3(x0+dx,y0+dy,z0+dz)
                wx=f[:,0] if dx else 1.0-f[:,0]
                wy=f[:,1] if dy else 1.0-f[:,1]
                wz=f[:,2] if dz else 1.0-f[:,2]
                out=out+h*wx*wy*wz
    return out

def fbm3(p,oct=4,freq=1.0,gain=0.5):
    tot=np.zeros(p.shape[0],np.float32); amp=1.0; norm=0.0
    for _ in range(oct):
        tot+=amp*vnoise3(p*freq); norm+=amp; amp*=gain; freq*=2.02
    return tot/norm

# ----------------------------------------------------------------- материалы
def albedo(mat, L, W, pal):
    """Процедурная расцветка детали; базовые тона берутся из палитры."""
    n=L.shape[0]
    out=np.zeros((n,3),np.float32)
    C=lambda k: np.array(pal[k],np.float32)
    if mat==M_FUR:
        t=fbm3(W*1.25+17.0,4)
        v=np.clip((t-0.36)/0.34,0,1); v=v*v*(3-2*v)
        dark,mid,lite=C("fur_dark"),C("fur_mid"),C("fur_lite")
        out=dark+(mid-dark)*v[:,None]
        hi=np.clip((t-0.66)/0.22,0,1)
        out=out+(lite-out)*(hi*hi)[:,None]
    elif mat==M_SHIRT:
        t=fbm3(W*3.5+3.0,3)
        out=C("shirt")*(0.85+0.30*t)[:,None]
    elif mat==M_CAP:
        t=fbm3(W*6.0+41.0,3)
        out=C("cap")*(0.88+0.22*t)[:,None]
    elif mat==M_MUZZLE:
        t=fbm3(W*4.0+7.0,3)
        out=C("muzzle")*(0.80+0.30*t)[:,None]
    elif mat==M_DARK:
        out[:]=C("dark")
    elif mat==M_SHOE:
        out[:]=C("shoe")
    elif mat==M_SOLE:
        out[:]=C("sole")
    elif mat==M_GOLD:
        out[:]=C("gold")
    elif mat==M_BOWL:
        out[:]=C("bowl")
    elif mat==M_GARLIC:
        t=fbm3(W*7.0+90.0,3)
        out=C("garlic")*(0.82+0.25*t)[:,None]
    return out


PALETTE_DEFAULT = {
    "fur_dark": (0.36, 0.10, 0.44), "fur_mid": (0.68, 0.24, 0.76),
    "fur_lite": (0.92, 0.62, 0.95), "shirt": (0.40, 0.19, 0.15),
    "cap": (0.72, 0.72, 0.70), "muzzle": (0.82, 0.80, 0.80),
    "dark": (0.05, 0.03, 0.06), "shoe": (0.14, 0.58, 0.62),
    "sole": (0.88, 0.90, 0.92), "gold": (0.95, 0.70, 0.16),
    "bowl": (0.18, 0.50, 0.30), "garlic": (0.92, 0.90, 0.86),
}


def sample_palette(src_bgr: np.ndarray) -> dict:
    """Снимает палитру персонажа с фотографии.

    Пиксели силуэта раскладываются по цветовым семьям (мех, футболка,
    нейтральный верх — кепка, нейтральная середина — морда, белое, бирюза,
    золото, зелень, тень), и из каждой берутся устойчивые перцентили по
    яркости. Дальше эти цвета идут в материалы 3D-модели: геометрия
    генерируется, а расцветка — измеренная, не выдуманная.
    """
    rgb, alpha = extract_character(src_bgr)
    h, w = alpha.shape
    mask = alpha > 0.7
    hsv = cv2.cvtColor((np.clip(rgb, 0, 1) * 255).astype(np.uint8),
                       cv2.COLOR_RGB2HSV)
    hue = hsv[:, :, 0].astype(np.float32) * 2.0
    sat = hsv[:, :, 1].astype(np.float32) / 255.0
    val = hsv[:, :, 2].astype(np.float32) / 255.0
    ny = np.mgrid[0:h, 0:w][0].astype(np.float32) / h

    families = {
        "fur":     mask & (hue > 265) & (hue < 330) & (sat > 0.30) & (val > 0.12),
        "shirt":   mask & ((hue < 40) | (hue > 352)) & (sat > 0.18) & (sat < 0.85)
                        & (val > 0.10) & (val < 0.62),
        "cap":     mask & (sat < 0.18) & (val > 0.30) & (ny < 0.16),
        "muzzle":  mask & (sat < 0.20) & (val > 0.30) & (ny >= 0.12) & (ny < 0.30),
        "white":   mask & (sat < 0.14) & (val > 0.72),
        "teal":    mask & (hue > 160) & (hue < 205) & (sat > 0.25),
        "gold":    mask & (hue > 35) & (hue < 62) & (sat > 0.45) & (val > 0.35),
        "green":   mask & (hue > 85) & (hue < 160) & (sat > 0.25),
        "dark":    mask & (val < 0.14),
    }

    def pick(name, q, gain=1.0):
        px = rgb[families[name]]
        if px.shape[0] < 120:
            return None
        lum = px @ np.array([0.2126, 0.7152, 0.0722], np.float32)
        val_ = px[np.argsort(lum)[int(q * (px.shape[0] - 1))]]
        return tuple(float(np.clip(c * gain, 0.0, 1.0)) for c in val_)

    # Фото уже несёт собственный свет, поэтому небольшой подъём: цвет идёт
    # в альбедо, которое мы освещаем заново.
    wanted = {
        "fur_dark": ("fur", 0.15, 1.05), "fur_mid": ("fur", 0.50, 1.10),
        "fur_lite": ("fur", 0.88, 1.15), "shirt": ("shirt", 0.50, 1.15),
        "cap": ("cap", 0.50, 1.25), "muzzle": ("muzzle", 0.50, 1.25),
        "dark": ("dark", 0.50, 1.00), "shoe": ("teal", 0.60, 1.35),
        "sole": ("white", 0.50, 1.05), "gold": ("gold", 0.70, 1.25),
        "bowl": ("green", 0.88, 1.30), "garlic": ("white", 0.88, 1.00),
    }
    pal = dict(PALETTE_DEFAULT)
    taken = []
    for key, (fam, q, gain) in wanted.items():
        got = pick(fam, q, gain)
        if got is not None:
            pal[key] = got
            taken.append(key)
    print(f"[fx] палитра снята с фото: {len(taken)} из {len(wanted)} цветов")
    return pal


SPEC = {M_FUR:0.12, M_SHIRT:0.05, M_CAP:0.10, M_MUZZLE:0.14, M_DARK:0.35,
        M_SHOE:0.30, M_SOLE:0.22, M_GOLD:0.85, M_BOWL:0.25, M_GARLIC:0.15}

# ----------------------------------------------------------------- растеризация
def _tri_setup(sx,sy,F):
    x0,x1,x2=sx[F[:,0]],sx[F[:,1]],sx[F[:,2]]
    y0,y1,y2=sy[F[:,0]],sy[F[:,1]],sy[F[:,2]]
    area=(x1-x0)*(y2-y0)-(x2-x0)*(y1-y0)
    return x0,y0,x1,y1,x2,y2,area

def _cover(x0,y0,x1,y1,x2,y2,area,W,H):
    """Пиксели, накрытые треугольником. Знак площади не важен: обходим
    обе намотки, иначе половина граней куба молча исчезает."""
    bx0=max(0,int(np.floor(min(x0,x1,x2)))); bx1=min(W,int(np.ceil(max(x0,x1,x2)))+1)
    by0=max(0,int(np.floor(min(y0,y1,y2)))); by1=min(H,int(np.ceil(max(y0,y1,y2)))+1)
    if bx0>=bx1 or by0>=by1 or area==0.0:
        return None
    PX,PY=np.meshgrid(np.arange(bx0,bx1,dtype=np.float32)+0.5,
                      np.arange(by0,by1,dtype=np.float32)+0.5)
    e0=(x1-PX)*(y2-PY)-(x2-PX)*(y1-PY)
    e1=(x2-PX)*(y0-PY)-(x0-PX)*(y2-PY)
    e2=area-e0-e1
    if area>0: m=(e0>=0)&(e1>=0)&(e2>=0)
    else:      m=(e0<=0)&(e1<=0)&(e2<=0)
    if not m.any():
        return None
    inv=1.0/area
    return bx0,by0,bx1,by1,m,e0*inv,e1*inv,e2*inv


def depth_pass(sc,R,eye,W,H,fov):
    sx,sy,z=project(sc.V,R,eye,W,H,fov)
    zbuf=np.full((H,W),1e9,np.float32); idbuf=np.full((H,W),-1,np.int32)
    F=sc.F
    x0,y0,x1,y1,x2,y2,area=_tri_setup(sx,sy,F)
    zv=z[F]
    cen=sc.V[F].mean(1)
    facing=((cen-eye)*sc.N).sum(1)
    for t in range(F.shape[0]):
        if facing[t]>=0.0 or zv[t].min()<=1e-3:      # отсечение задних граней
            continue
        r=_cover(x0[t],y0[t],x1[t],y1[t],x2[t],y2[t],area[t],W,H)
        if r is None: continue
        bx0,by0,bx1,by1,m,w0,w1,w2=r
        iz=w0/zv[t,0]+w1/zv[t,1]+w2/zv[t,2]
        d=1.0/np.maximum(iz,1e-9)
        sub=zbuf[by0:by1,bx0:bx1]
        upd=m&(d<sub)
        sub[upd]=d[upd]
        idbuf[by0:by1,bx0:bx1][upd]=t
    return zbuf,idbuf,(sx,sy,z)


def shadow_map(sc, light_dir, size=1200):
    """Ортографическая карта глубины со стороны источника."""
    L=np.asarray(light_dir,np.float32); L/=np.linalg.norm(L)
    up=np.array([0,1,0],np.float32)
    if abs(L@up)>0.95: up=np.array([1,0,0],np.float32)
    r=np.cross(L,up); r/=np.linalg.norm(r); u=np.cross(r,L)
    B=np.stack([r,u,L],0)
    P=sc.V@B.T
    lo=P.min(0)-0.4; hi=P.max(0)+0.4
    span=np.maximum(hi-lo,1e-3)
    zb=np.full((size,size),1e9,np.float32)
    sx=(P[:,0]-lo[0])/span[0]*(size-1)
    sy=(P[:,1]-lo[1])/span[1]*(size-1)
    dz=P[:,2]-lo[2]
    F=sc.F
    x0,y0,x1,y1,x2,y2,area=_tri_setup(sx,sy,F)
    dv=dz[F]
    for t in range(F.shape[0]):
        r=_cover(x0[t],y0[t],x1[t],y1[t],x2[t],y2[t],area[t],size,size)
        if r is None: continue
        bx0,by0,bx1,by1,m,w0,w1,w2=r
        d=w0*dv[t,0]+w1*dv[t,1]+w2*dv[t,2]
        sub=zb[by0:by1,bx0:bx1]
        upd=m&(d<sub); sub[upd]=d[upd]
    return zb,B,lo,span

def render_character(W,H,cam_eye,cam_target,fov,chins=10,ssaa=2,pal=None,
                     cap_text='OLIVER'):
    pal = PALETTE_DEFAULT if pal is None else pal
    sc,eyes=build(chins,cap_text)
    RW,RH=W*ssaa,H*ssaa
    R,eye=look_at(cam_eye,cam_target)
    zbuf,idbuf,(sx,sy,z)=depth_pass(sc,R,eye,RW,RH,fov)

    KEY=np.array([-0.62,0.42,0.66],np.float32); KEY/=np.linalg.norm(KEY)
    FILL=np.array([0.70,0.35,0.42],np.float32); FILL/=np.linalg.norm(FILL)
    smap,SB,slo,sspan=shadow_map(sc,KEY)

    col=np.zeros((RH,RW,3),np.float32)
    alpha=(idbuf>=0).astype(np.float32)
    F=sc.F; V=sc.V; L=sc.L
    x0,y0,x1,y1,x2,y2,area=_tri_setup(sx,sy,F)
    zv=z[F]
    view_dir=None
    for t in range(F.shape[0]):
        bx0=max(0,int(np.floor(min(x0[t],x1[t],x2[t])))); bx1=min(RW,int(np.ceil(max(x0[t],x1[t],x2[t])))+1)
        by0=max(0,int(np.floor(min(y0[t],y1[t],y2[t])))); by1=min(RH,int(np.ceil(max(y0[t],y1[t],y2[t])))+1)
        if bx0>=bx1 or by0>=by1 or area[t]==0: continue
        win=idbuf[by0:by1,bx0:bx1]
        m=(win==t)
        if not m.any(): continue
        ys,xs_=np.nonzero(m)
        PX=(xs_+bx0).astype(np.float32)+0.5; PY=(ys+by0).astype(np.float32)+0.5
        inv=1.0/area[t]
        w0=((x1[t]-PX)*(y2[t]-PY)-(x2[t]-PX)*(y1[t]-PY))*inv
        w1=((x2[t]-PX)*(y0[t]-PY)-(x0[t]-PX)*(y2[t]-PY))*inv
        w2=1.0-w0-w1
        iw=np.stack([w0/zv[t,0],w1/zv[t,1],w2/zv[t,2]],1)
        iw/=iw.sum(1,keepdims=True)
        tri=F[t]
        Pw=iw@V[tri]; Pl=iw@L[tri]
        N=sc.N[t]
        alb=albedo(int(sc.M[t]),Pl,Pw,pal)
        # тень
        S=Pw@SB.T
        u=(S[:,0]-slo[0])/sspan[0]*(smap.shape[0]-1)
        v=(S[:,1]-slo[1])/sspan[1]*(smap.shape[0]-1)
        d=S[:,2]-slo[2]
        ui=np.clip(u.astype(np.int32),0,smap.shape[0]-1)
        vi=np.clip(v.astype(np.int32),0,smap.shape[0]-1)
        lit=(d<=smap[vi,ui]+0.055).astype(np.float32)
        ndl=max(0.0,(float(N@KEY)+0.32)/1.32)      # мягкая «обёртка» света
        ndf=max(0.0,(float(N@FILL)+0.25)/1.25)
        vd=Pw-eye; vd/= (np.linalg.norm(vd,axis=1,keepdims=True)+1e-9)
        rim=np.clip(1.0+ (vd@N), 0.0,1.0)**2.6
        key_col=np.array([1.00,0.56,0.88],np.float32)*2.35
        fill_col=np.array([0.34,0.70,1.00],np.float32)*0.95
        amb=np.array([0.42,0.34,0.62],np.float32)*0.85
        kterm=(ndl*(0.25+0.75*lit))[:,None]*key_col[None,:]
        shade=alb*(kterm+(fill_col*ndf)[None,:]+amb[None,:])
        # блик
        hvec=KEY-vd; hvec/= (np.linalg.norm(hvec,axis=1,keepdims=True)+1e-9)
        spec=np.clip(hvec@N,0,1)**28.0*SPEC.get(int(sc.M[t]),0.1)
        shade+=spec[:,None]*key_col*(0.25+0.75*lit)[:,None]
        shade+=rim[:,None]*np.array([1.00,0.38,0.82],np.float32)*0.95
        col[ys+by0,xs_+bx0]=shade
    if ssaa>1:
        col=cv2.resize(col,(W,H),interpolation=cv2.INTER_AREA)
        alpha=cv2.resize(alpha,(W,H),interpolation=cv2.INTER_AREA)
    ex,ey,_=project(eyes,R,eye,W,H,fov)
    return col,alpha,list(zip(ex.tolist(),ey.tolist()))

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
    sky[:, :, 0] = 0.060 + 0.110 * grad
    sky[:, :, 1] = 0.028 + 0.042 * grad
    sky[:, :, 2] = 0.135 + 0.235 * grad

    # Туманности: три слоя fbm с разными палитрами.
    layers = [
        (fbm(h, w, rng, 7, 3, 0.55, warp=90.0), np.array([1.00, 0.18, 0.62], np.float32), 0.72),
        (fbm(h, w, rng, 6, 2, 0.58, warp=70.0), np.array([0.45, 0.20, 1.00], np.float32), 0.58),
        (fbm(h, w, rng, 6, 4, 0.50, warp=60.0), np.array([0.15, 0.85, 1.00], np.float32), 0.55),
    ]
    for noise, color, strength in layers:
        cloud = smoothstep(0.46, 0.88, noise) ** 1.7
        falloff = smoothstep(1.25, 0.05, np.hypot(nx - 0.35, (ny - 0.42) * 1.25))
        add_rgb(sky, cloud * falloff * strength, color)

    # Тёмные пылевые прожилки.
    dust = smoothstep(0.55, 0.95, fbm(h, w, rng, 5, 5, 0.55, warp=40.0))
    sky *= (1.0 - 0.38 * dust)[:, :, None]

    # Звёзды: степенное распределение яркости.
    count = int(h * w / 700)
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
    sky += gauss(tint, 0.7) * 2.9
    sky += gauss(stars, 2.4)[:, :, None] * 0.45

    # Крупные звёзды с дифракционными лучами.
    bright = np.zeros((h, w), np.float32)
    for _ in range(40):
        bx, by = int(rng.integers(0, w)), int(rng.integers(0, h))
        power = float(rng.uniform(0.5, 1.0))
        length = int(rng.uniform(16, 52))
        cv2.line(bright, (bx - length, by), (bx + length, by), power * 0.55, 1)
        cv2.line(bright, (bx, by - length), (bx, by + length), power * 0.55, 1)
        cv2.circle(bright, (bx, by), 2, power, -1)
    sky += gauss(bright, 1.1)[:, :, None] * np.array([1.05, 0.98, 1.05], np.float32)

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
        body[:, :, c] = col[c] * tex * (0.20 + 1.15 * lambert)

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
    add_rgb(canvas, ring * 0.48, np.array([1.00, 0.62, 0.92], np.float32))


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
    add_rgb(canvas, total * 0.28, PINK_GLOW)
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


def render_comets(canvas: np.ndarray, rng: np.random.Generator,
                  count: int = 5) -> None:
    """Кометы с горящей головой и растворяющимся хвостом."""
    h, w = canvas.shape[:2]
    head = np.zeros((h, w), np.float32)
    tail = np.zeros((h, w), np.float32)
    for _ in range(count):
        x0 = float(rng.uniform(-0.1, 1.1)) * w
        y0 = float(rng.uniform(-0.05, 0.85)) * h
        ang = float(rng.uniform(np.deg2rad(150), np.deg2rad(210)))
        length = float(rng.uniform(0.10, 0.34)) * w
        x1 = x0 - np.cos(ang) * length
        y1 = y0 - np.sin(ang) * length
        steps = 26
        for i in range(steps):
            t = i / (steps - 1.0)
            px = int(x0 + (x1 - x0) * t)
            py = int(y0 + (y1 - y0) * t)
            cv2.circle(tail, (px, py), max(1, int(3.0 * (1.0 - t))),
                       float((1.0 - t) ** 2.0), -1)
        cv2.circle(head, (int(x0), int(y0)), 3, 1.0, -1)
    add_rgb(canvas, gauss(tail, 1.4) * 0.55, np.array([1.0, 0.72, 0.95], np.float32))
    add_rgb(canvas, gauss(tail, 8.0) * 0.25, PINK_GLOW)
    add_rgb(canvas, gauss(head, 1.2) * 1.30, PINK_CORE)
    add_rgb(canvas, gauss(head, 9.0) * 0.55, PINK_HOT)


def render_rune_ring(canvas: np.ndarray, cx: float, cy: float, rx: float,
                     ry: float, rng: np.random.Generator,
                     ticks: int = 40) -> None:
    """Кольцо рун: штрихи и блоки по эллипсу, как вращающийся круг призыва."""
    h, w = canvas.shape[:2]
    layer = np.zeros((h, w), np.float32)
    phase = float(rng.uniform(0, 2 * np.pi))
    for k in range(ticks):
        a = phase + 2 * np.pi * k / ticks
        long_tick = (k % 5 == 0)
        r0, r1 = (0.93, 1.09) if long_tick else (0.97, 1.04)
        p0 = (int(cx + np.cos(a) * rx * r0), int(cy + np.sin(a) * ry * r0))
        p1 = (int(cx + np.cos(a) * rx * r1), int(cy + np.sin(a) * ry * r1))
        cv2.line(layer, p0, p1, 1.0 if long_tick else 0.55,
                 3 if long_tick else 2, cv2.LINE_AA)
        if long_tick:                       # «глиф» — короткая ступенчатая метка
            gx = int(cx + np.cos(a) * rx * 1.17)
            gy = int(cy + np.sin(a) * ry * 1.17)
            size = max(2, int(w * 0.004))
            cv2.rectangle(layer, (gx - size, gy - size), (gx + size, gy + size),
                          0.8, -1)
    add_rgb(canvas, gauss(layer, 1.0) * 0.85, PINK_CORE)
    add_rgb(canvas, gauss(layer, 6.0) * 0.55, PINK_HOT)
    add_rgb(canvas, gauss(layer, 22.0) * 0.30, PINK_GLOW)


def depth_of_field(canvas: np.ndarray, amount: float = 0.55,
                   sigma: float = 3.4) -> None:
    """Лёгкая расфокусировка дальнего плана — персонаж читается резче."""
    np.copyto(canvas, canvas * (1.0 - amount) + gauss(canvas, sigma) * amount)


def render_echoes(canvas: np.ndarray, layer: np.ndarray, alpha: np.ndarray,
                  direction: tuple[float, float], count: int = 3,
                  step: float = 26.0) -> None:
    """Фантомные копии силуэта позади — след от рывка."""
    h, w = canvas.shape[:2]
    dx, dy = direction
    norm = float(np.hypot(dx, dy)) or 1.0
    dx, dy = dx / norm, dy / norm
    for i in range(count, 0, -1):
        off_x, off_y = int(-dx * step * i), int(-dy * step * i)
        a = np.roll(np.roll(alpha, off_y, axis=0), off_x, axis=1)
        if off_y > 0:
            a[:off_y, :] = 0.0
        elif off_y < 0:
            a[off_y:, :] = 0.0
        if off_x > 0:
            a[:, :off_x] = 0.0
        elif off_x < 0:
            a[:, off_x:] = 0.0
        fade = (1.0 - i / (count + 1.0)) ** 2.0 * 0.55
        add_rgb(canvas, gauss(a, 6.0) * fade, PINK_HOT)
        add_rgb(canvas, gauss(a, 24.0) * fade * 0.6, PINK_GLOW)


def render_god_rays(canvas: np.ndarray, cx: float, cy: float,
                    occluder: np.ndarray | None = None,
                    samples: int = 16, spread: float = 0.34,
                    weight: float = 0.30) -> None:
    """Объёмные лучи: яркие места растягиваются от точки источника."""
    h, w = canvas.shape[:2]
    bright = np.clip(canvas.max(axis=2) - 0.90, 0.0, None)
    if float(bright.max()) <= 0.0:
        return
    acc = np.zeros((h, w), np.float32)
    total = 0.0
    for i in range(1, samples + 1):
        scale = 1.0 + spread * i / samples
        mat = cv2.getRotationMatrix2D((float(cx), float(cy)), 0.0, scale)
        warped = cv2.warpAffine(bright, mat, (w, h), flags=cv2.INTER_LINEAR,
                                borderMode=cv2.BORDER_CONSTANT, borderValue=0.0)
        k = (1.0 - i / (samples + 1.0)) ** 1.6
        acc += warped * k
        total += k
    acc /= max(total, 1e-6)
    acc = gauss(acc, 3.0)
    if occluder is not None:          # персонаж загораживает лучи, а не тонет в них
        acc *= (1.0 - np.clip(occluder, 0.0, 1.0))
    add_rgb(canvas, acc * weight, PINK_HOT)
    add_rgb(canvas, acc * weight * 0.40, PINK_CORE)


def render_anamorphic(canvas: np.ndarray, cx: float, cy: float, power: float,
                      length: float) -> None:
    """Анаморфный блик: длинная горизонтальная полоса плюс призрачные диски."""
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    streak = np.exp(-((xx - cx) ** 2) / (2 * length ** 2)
                    - ((yy - cy) ** 2) / (2 * (h * 0.0030) ** 2))
    add_rgb(canvas, streak * power, np.array([0.45, 0.72, 1.00], np.float32))
    add_rgb(canvas, streak ** 3 * power * 0.7, PINK_CORE)
    mx, my = w * 0.5, h * 0.5
    for t, rad, col in ((0.55, 0.030, (1.00, 0.45, 0.85)),
                        (1.35, 0.018, (0.40, 0.85, 1.00)),
                        (1.95, 0.042, (0.85, 0.50, 1.00))):
        gx = cx + (mx - cx) * t
        gy = cy + (my - cy) * t
        d = np.hypot(xx - gx, yy - gy) / (w * rad)
        ghost = np.clip(1.0 - np.abs(d - 1.0) / 0.45, 0.0, 1.0) ** 2
        add_rgb(canvas, ghost * power * 0.30, np.array(col, np.float32))


def render_shockwave(canvas: np.ndarray, cx: float, cy: float, radius: float,
                     thickness: float, strength: float) -> None:
    """Ударная волна с рефракцией: кольцо реально изгибает картинку."""
    h, w = canvas.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx, dy = xx - cx, yy - cy
    d = np.maximum(np.hypot(dx, dy), 1e-3)
    prof = np.exp(-((d - radius) ** 2) / (2 * thickness ** 2))
    push = prof * strength
    warped = cv2.remap(canvas, (xx + dx / d * push).astype(np.float32),
                       (yy + dy / d * push).astype(np.float32),
                       cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
    np.copyto(canvas, warped)
    add_rgb(canvas, prof ** 2 * 0.22, PINK_CORE)
    add_rgb(canvas, prof * 0.12, PINK_GLOW)


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

def composite_character(canvas: np.ndarray, layer: np.ndarray,
                       alpha: np.ndarray) -> np.ndarray:
    """Кладёт слой персонажа на фон и добавляет наружное свечение силуэта."""
    a = np.clip(alpha, 0.0, 1.0)
    glow = gauss(a, 22.0) * 0.13 + gauss(a, 64.0) * 0.06
    add_rgb(canvas, glow * (1.0 - a), PINK_GLOW)
    a3 = a[:, :, None]
    np.multiply(canvas, 1.0 - a3, out=canvas)
    canvas += layer * a3
    return a


def photo_layers(rgb: np.ndarray, alpha: np.ndarray, scale: float,
                 offset: tuple[int, int], height: int,
                 width: int) -> tuple[np.ndarray, np.ndarray]:
    """Режим --mode photo: масштабирует вырезку и красит её под сцену."""
    sh, sw = alpha.shape
    nw, nh = int(round(sw * scale)), int(round(sh * scale))
    rgb_s = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LANCZOS4)
    a_s = cv2.resize(alpha, (nw, nh), interpolation=cv2.INTER_LINEAR)
    rgb_s = np.clip(rgb_s * 1.32 - gauss(rgb_s, 2.2) * 0.32, 0.0, 1.4)

    ox, oy = offset
    full_rgb = np.zeros((height, width, 3), np.float32)
    full_a = np.zeros((height, width), np.float32)
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(width, ox + nw), min(height, oy + nh)
    if x0 >= x1 or y0 >= y1:
        raise SystemExit("[fx] персонаж вне холста — проверьте масштаб")
    full_rgb[y0:y1, x0:x1] = rgb_s[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    full_a[y0:y1, x0:x1] = a_s[y0 - oy:y1 - oy, x0 - ox:x1 - ox]

    graded = full_rgb.copy()
    luma = graded @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    graded = np.clip((graded - 0.48) * 1.20 + 0.46, 0.0, 1.6)
    graded[:, :, 0] += (1.0 - luma) * 0.08
    graded[:, :, 2] += (1.0 - luma) * 0.14
    graded[:, :, 1] *= 0.93
    luma2 = (graded @ np.array([0.2126, 0.7152, 0.0722], np.float32))[:, :, None]
    graded = np.clip(luma2 + (graded - luma2) * 1.12, 0.0, 1.6)

    gx = cv2.Sobel(full_a, cv2.CV_32F, 1, 0, ksize=5)
    gy = cv2.Sobel(full_a, cv2.CV_32F, 0, 1, ksize=5)
    edge_band = gauss(np.clip(np.hypot(gx, gy), 0.0, 1.0), 2.0)
    for (lx, ly), color, power in (((-0.80, 0.60), PINK_HOT, 0.55),
                                   ((0.78, -0.62), CYAN_RIM, 0.40)):
        facing = np.clip(-(gx * lx + gy * ly), 0.0, None)
        facing = facing / (facing.max() + 1e-6)
        add_rgb(graded, facing * edge_band * power * 1.35, color)
    return graded, np.clip(full_a, 0.0, 1.0)


def photo_character(src_bgr: np.ndarray, width: int, height: int, chins: int,
                    rng: np.random.Generator):
    """Старый путь: персонаж вырезается с фотографии, а не генерируется."""
    rgb, alpha_raw = extract_character(src_bgr)
    rgb, alpha_raw = render_chin_stack(rgb, alpha_raw, chins, rng)
    ramp = border_ramp(alpha_raw.shape, 34)
    alpha = alpha_raw * ramp
    cut_src = ((ramp < 0.55) & (alpha_raw > 0.30)).astype(np.float32)
    ys, xs = np.nonzero(alpha > 0.35)
    if ys.size == 0:
        raise SystemExit("[fx] персонаж не найден на исходнике")
    sy0, sy1 = int(ys.min()), int(ys.max()) + 1
    sx0, sx1 = int(xs.min()), int(xs.max()) + 1

    scale = min((height * 0.760) / (sy1 - sy0), (width * 0.880) / (sx1 - sx0))
    off_x = int(round((width - (sx1 - sx0) * scale) / 2.0))
    off_y = int(round(height * 0.100))
    eyes = [(off_x + (p[0] - sx0) * scale, off_y + (p[1] - sy0) * scale)
            for p in EYE_SRC]

    layer, a = photo_layers(rgb[sy0:sy1, sx0:sx1], alpha[sy0:sy1, sx0:sx1],
                            scale, (off_x, off_y), height, width)
    cut_band = paste_scaled(cut_src[sy0:sy1, sx0:sx1], scale, (off_x, off_y),
                            (height, width))
    return layer, a, eyes, cut_band


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
    out *= (1.0 - 0.24 * smoothstep(0.60, 1.55, r))[:, :, None]

    # Тон-маппинг (фильмик) + насыщенность.
    out = np.clip(out, 0.0, None) * 0.98        # экспозиция
    out = out / (1.0 + out) * 1.14
    luma = (out @ np.array([0.2126, 0.7152, 0.0722], np.float32))[:, :, None]
    out = np.clip(luma + (out - luma) * 1.26, 0.0, 1.0)
    out = np.clip((out - 0.46) * 1.19 + 0.48, 0.0, 1.0)     # контраст, но светлее

    # Развёртка ЭЛТ и полосы VHS.
    scan = 1.0 - 0.032 * (0.5 + 0.5 * np.sin(np.arange(h, dtype=np.float32) * np.pi))
    out *= scan[:, None, None]
    for _ in range(2):
        y = int(rng.integers(0, h - 30))
        hh = int(rng.integers(6, 20))
        out[y:y + hh] = np.clip(out[y:y + hh] * float(rng.uniform(1.03, 1.09)),
                                0.0, 1.0)

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
    size = int(w * 0.098)
    while size > 10:
        font = ImageFont.truetype(font_path, size)
        bbox = draw.textbbox((0, 0), text, font=font, stroke_width=max(2, size // 16))
        if bbox[2] - bbox[0] <= w * 0.92:
            break
        size -= 2
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    x = (w - tw) / 2 - bbox[0]
    y = h * 0.022 - bbox[1]
    sw = max(2, size // 16)
    for dx, dy, col in ((-11, 4, (255, 30, 165)), (11, -4, (70, 225, 255))):
        draw.text((x + dx, y + dy), text, font=font, fill=col)
    draw.text((x, y), text, font=font, fill=(255, 255, 255),
              stroke_width=sw, stroke_fill=(70, 0, 48))
    return np.asarray(pil).astype(np.float32) / 255.0


# ----------------------------------------------------------------------------
# 6. Сцена целиком
# ----------------------------------------------------------------------------

def render_chin_stack(rgb: np.ndarray, alpha: np.ndarray, count: int,
                      rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray]:
    """Каскад двойных подбородков под челюстью.

    Каждый валик — суперэллипс |u|^k + v^2 < 1 (плоские бока, скруглённые
    концы), залитый кожей из-под челюсти, с тенью складки по верхней кромке
    и бликом по низу. Валики рисуются сверху вниз, поэтому нижний край
    каждого перекрывает следующий — ровно так, как ложатся настоящие складки.
    """
    if count <= 0:
        return rgb, alpha
    rgb, alpha = rgb.copy(), alpha.copy()
    h, w = alpha.shape
    x0, y0, x1, y1 = CHIN_SKIN_RECT
    skin = rgb[max(0, y0):min(h, y1), max(0, x0):min(w, x1)].copy()
    if skin.size == 0:
        return rgb, alpha
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx0, cy0 = CHIN_ORIGIN
    hx, hy = CHIN_HALF

    for i in range(1, count + 1):
        jitter_w = 1.0 + float(rng.uniform(-0.04, 0.04))
        wx = hx * (1.0 + CHIN_GROW * i) * jitter_w
        wy = hy * (1.0 + CHIN_GROW * 0.35 * i)
        cx = cx0 + CHIN_DRIFT_X * i + float(rng.uniform(-3.0, 3.0))
        cy = cy0 + CHIN_STEP * i

        u = (xx - cx) / wx
        v = (yy - cy) / wy
        shape = np.abs(u) ** 3.2 + v * v
        mask = smoothstep(1.0, 0.80, shape)
        if float(mask.max()) <= 0.0:
            continue

        bw, bh = max(2, int(2 * wx)), max(2, int(2 * wy))
        tile = cv2.resize(skin, (bw, bh), interpolation=cv2.INTER_LANCZOS4)
        if bool(rng.integers(0, 2)):                 # чтобы кожа не тайлилась
            tile = tile[:, ::-1]
        patch = np.zeros((h, w, 3), np.float32)
        px, py = int(cx - wx), int(cy - wy)
        ax0, ay0 = max(0, px), max(0, py)
        ax1, ay1 = min(w, px + bw), min(h, py + bh)
        if ax0 >= ax1 or ay0 >= ay1:
            continue
        patch[ay0:ay1, ax0:ax1] = tile[ay0 - py:ay1 - py, ax0 - px:ax1 - px]

        shade = (1.0 - 0.58 * smoothstep(-0.15, -0.95, v)
                 + 0.22 * np.exp(-((v - 0.30) ** 2) / 0.10))
        patch *= np.clip(shade, 0.0, 2.0)[:, :, None]

        m3 = mask[:, :, None]
        rgb = rgb * (1.0 - m3) + patch * m3
        alpha = np.maximum(alpha, mask)

    return np.clip(rgb, 0.0, 1.6), np.clip(alpha, 0.0, 1.0)


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
                    rng: np.random.Generator, count: int = 1100) -> None:
    """Срезы кадра исходника рассыпаются искрами — «дезинтеграция»,
    вместо прямой отрубленной грани."""
    h, w = canvas.shape[:2]
    patchy = fbm(h, w, rng, 5, 9, 0.55) ** 1.8      # плотность искр рваная
    weight = (band_mask * (0.15 + 1.85 * patchy)).astype(np.float64).ravel()
    total = weight.sum()
    if total <= 0:
        return
    idx = rng.choice(weight.size, size=min(count, weight.size),
                     replace=False, p=weight / total)
    ys, xs = np.unravel_index(idx, (h, w))
    layer = np.zeros((h, w), np.float32)
    jitter = rng.normal(0.0, 13.0, (2, ys.size))
    for x, y, jx, jy, mag in zip(xs, ys, jitter[0], jitter[1],
                                 rng.random(ys.size) ** 2.2):
        px, py = int(x + jx), int(y + jy)
        if 0 <= px < w and 0 <= py < h:
            cv2.circle(layer, (px, py), 1 + int(mag * 2.4), float(0.35 + mag), -1)
    add_rgb(canvas, gauss(layer, 0.8) * 0.85, PINK_CORE)
    add_rgb(canvas, gauss(layer, 3.5) * 0.55, PINK_HOT)
    add_rgb(canvas, gauss(layer, 14.0) * 0.30, PINK_GLOW)


def compose(src_bgr, width: int, height: int, seed: int,
            caption: str | None, chins: int = 10,
            mode: str = "generate", cap_text: str = "OLIVER") -> np.ndarray:
    rng = np.random.default_rng(seed)
    cut_band = None

    if mode == "generate":
        pal = PALETTE_DEFAULT if src_bgr is None else sample_palette(src_bgr)
        tx, ty, tz = CAM_TARGET
        dx, dy, dz = CAM_DIR
        eye = (tx + dx * CAM_DIST, ty + dy * CAM_DIST, tz + dz * CAM_DIST)
        char_rgb, char_a, eyes = render_character(
            width, height, eye, CAM_TARGET, CAM_FOV, chins, RENDER_SSAA, pal,
            cap_text)
        char_rgb = np.clip(char_rgb, 0.0, 8.0)
    else:
        char_rgb, char_a, eyes, cut_band = photo_character(
            src_bgr, width, height, chins, rng)

    target = (-width * 0.08, height * 0.470)

    # --- дальний план ---
    canvas = render_space(height, width, rng)
    render_planet(canvas, width * 1.06, height * 0.055, height * 0.235, rng,
                  base=(0.48, 0.16, 0.58), rings=True, light=(-0.75, 0.30))
    render_planet(canvas, width * 0.115, height * 0.905, height * 0.036, rng,
                  base=(0.42, 0.40, 0.55), rings=False, light=(-0.6, -0.5))
    render_warp_streaks(canvas, eyes[0][0], eyes[0][1], rng)
    render_portal_ring(canvas, width * 0.52, height * 0.60,
                       width * 0.52, height * 0.19, rng)
    render_rune_ring(canvas, width * 0.52, height * 0.60,
                     width * 0.40, height * 0.145, rng)
    render_comets(canvas, rng)
    render_asteroid_field(canvas, rng)
    depth_of_field(canvas, 0.40, height * 0.0014)

    # --- персонаж: сначала фантомный след, потом он сам ---
    render_echoes(canvas, char_rgb, char_a,
                  (target[0] - eyes[0][0], target[1] - eyes[0][1]))
    char_alpha = composite_character(canvas, char_rgb, char_a)

    # --- эффекты поверх ---
    if cut_band is not None:
        render_dissolve(canvas, cut_band, rng)
    render_silhouette_flames(canvas, char_alpha, rng)
    render_arcs(canvas, char_alpha, rng)
    render_embers(canvas, rng)

    render_beam_rocks(canvas, eyes[0], target, rng)
    mid = ((eyes[0][0] + eyes[1][0]) / 2.0, (eyes[0][1] + eyes[1][1]) / 2.0)
    for eye_pt in eyes:
        aim = (target[0] + (eye_pt[0] - mid[0]) * 1.35,
               target[1] + (eye_pt[1] - mid[1]) * 1.35)
        render_laser(canvas, eye_pt, aim, rng, width=width * 0.0062)

    render_god_rays(canvas, eyes[0][0], eyes[0][1], occluder=char_alpha)
    render_anamorphic(canvas, eyes[0][0], eyes[0][1],
                      power=0.22, length=width * 0.20)
    render_plasma_fire(canvas, rng)
    render_shockwave(canvas, width * 0.52, height * 0.52,
                     radius=width * 0.46, thickness=width * 0.011,
                     strength=width * 0.008)

    spill = np.zeros((height, width), np.float32)
    cv2.line(spill, (int(eyes[0][0]), int(eyes[0][1])),
             (int(target[0]), int(target[1])), 1.0, int(width * 0.05), cv2.LINE_AA)
    spill = gauss(spill, width * 0.04) * char_alpha
    add_rgb(canvas, spill * 0.10, PINK_HOT)

    face = np.zeros((height, width), np.float32)
    cv2.circle(face, (int(eyes[0][0]), int(eyes[0][1])),
               int(width * 0.030), 1.0, -1)
    face = gauss(face, width * 0.028) * char_alpha
    face /= max(float(face.max()), 1e-6)
    add_rgb(canvas, face * 0.11, PINK_HOT)
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
    ap.add_argument("--mode", choices=("auto", "generate", "photo"),
                    default="auto",
                    help="auto — photo, если задан --src, иначе generate; "
                         "generate — персонаж строится геометрией с нуля "
                         "(с --src с фото снимается только палитра); "
                         "photo — персонаж вырезается с фотографии")
    ap.add_argument("--src", default=None,
                    help="фото персонажа. В режиме generate с него снимается "
                         "палитра (сама фигура всё равно строится геометрией); "
                         "в режиме photo персонаж с него вырезается")
    ap.add_argument("--out", required=True, help="куда записать PNG")
    ap.add_argument("--width", type=int, default=1400)
    ap.add_argument("--height", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=2077)
    ap.add_argument("--chins", type=int, default=0,
                    help="сколько двойных подбородков нарастить (0 — выключить)")
    ap.add_argument("--cap-text", default="OLIVER",
                    help="надпись на кепке блочным шрифтом (пусто — без неё)")
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
    chins = _clamp(args.chins, 0, 40, "--chins")

    out_path = _safe_path(args.out, must_exist=False)

    src = None
    if args.src:
        src = load_image(_safe_path(args.src, must_exist=True))
    if args.mode == "auto":
        args.mode = "photo" if src is not None else "generate"
    if args.mode == "photo":
        if src is None:
            raise SystemExit("[fx] --mode photo требует --src")
        print(f"[fx] {SIGNATURE}: персонаж вырезается с фото "
              f"{src.shape[1]}x{src.shape[0]}")
    elif src is None:
        print(f"[fx] {SIGNATURE}: персонаж генерируется, палитра по умолчанию")
    else:
        print(f"[fx] {SIGNATURE}: персонаж генерируется, палитра снимается "
              f"с фото {src.shape[1]}x{src.shape[0]}")

    if args.cutout:
        cut_path = _safe_path(args.cutout, must_exist=False)
        if cut_path == out_path:
            raise SystemExit("[fx] --cutout и --out не могут совпадать")
        rng_cut = np.random.default_rng(seed)
        if args.mode == "generate":
            tx, ty, tz = CAM_TARGET
            dx, dy, dz = CAM_DIR
            eye = (tx + dx * CAM_DIST, ty + dy * CAM_DIST, tz + dz * CAM_DIST)
            pal = PALETTE_DEFAULT if src is None else sample_palette(src)
            col, alpha, _ = render_character(width, height, eye, CAM_TARGET,
                                             CAM_FOV, chins, RENDER_SSAA, pal,
                                             args.cap_text)
            rgb = np.clip(col / (1.0 + col) * 1.25, 0.0, 1.0)
        else:
            rgb, alpha = extract_character(src)
            rgb, alpha = render_chin_stack(rgb, alpha, chins, rng_cut)
        # RGB за силуэтом обнуляем: просмотрщики без поддержки альфы иначе
        # покажут мусор (в режиме photo — исходный зигзаг).
        visible = (alpha > 0.004)[:, :, None]
        rgba = np.dstack([
            (np.clip(rgb * visible, 0, 1) * 255).astype(np.uint8)[:, :, ::-1],
            (np.clip(alpha, 0, 1) * 255).astype(np.uint8),
        ])
        save_image_atomic(cut_path, rgba)
        print(f"[fx] вырезка: {cut_path}")

    out = compose(src, width, height, seed, args.caption, chins, args.mode,
                  args.cap_text)
    bgr = (np.clip(out, 0, 1) * 255.0 + 0.5).astype(np.uint8)[:, :, ::-1]
    save_image_atomic(out_path, bgr)
    print(f"[fx] готово: {out_path} ({width}x{height})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
