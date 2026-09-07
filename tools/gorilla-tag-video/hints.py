# -*- coding: utf-8 -*-
"""Per-segment head anchors, measured at 1:1 on the source frames.

(anchor_frame, cx, cy, head_width, style)
style: toon = flat-cartoon Morty | dark = graded solid figure
       sil  = flat black silhouette | line = line-art outline figure
"""
HINTS = {
  0 : (  0, 242, 197, 175, 'toon'),
  1 : ( 20, 270, 265, 500, 'toon'),
  2 : ( 36, 337, 230, 205, 'toon'),
  3 : ( 52, 284, 312,  35, 'toon'),
  4 : ( 69, 280, 206, 360, 'toon'),
  5 : ( 85, 112, 200, 175, 'toon'),
  6 : (103, 287, 237, 255, 'toon'),
  7 : (118, 322, 224, 255, 'toon'),
  8 : (142, 262, 202, 225, 'toon'),
  9 : (152, 292, 247, 535, 'toon'),
  10: (168, 235, 265, 530, 'toon'),
  11: (183, 270, 172,  95, 'toon'),
  12: (195, 178, 185,  92, 'toon'),
  13: (208, 271, 210, 235, 'toon'),
  14: (218, 133, 215, 205, 'toon'),
  15: (236, 242, 265, 545, 'toon'),
  16: (244, 215, 250, 500, 'toon'),
  20: (298, 302, 175, 415, 'dark'),
  21: (314, 297, 214, 185, 'sil'),
  22: (326, 284, 219, 260, 'sil'),
  23: (338, 290, 200, 200, 'line'),
  24: (347, 226, 145, 325, 'dark'),
  25: (362, 308,  87, 180, 'dark'),
  29: (429, 400, 300, 520, 'toon'),
  30: (444, 282, 158, 435, 'dark'),
  31: (461, 324, 228, 230, 'sil'),
  32: (484, 302, 164, 195, 'line'),
  33: (493, 349, 274, 450, 'toon'),
  34: (510, 228, 155, 325, 'dark'),
}
# frames inside a segment where the character is absent / unusable
RANGE_OVERRIDE = {
  8 : (138, 151),   # emerging from the portal
  13: (204, 217),
  16: (244, 246),   # 247 is a white flash
  23: (336, 346),
  32: (479, 492),
}
SKIP = {17, 18, 19, 26, 27, 28, 35}
