# -*- coding: utf-8 -*-
"""Render every frame and mux with the original audio."""
import cv2, os, sys, json, subprocess, numpy as np
import composite as C
import hoodie
import imageio_ffmpeg

OUT = 'frames_out'
os.makedirs(OUT, exist_ok=True)
n = 529
SOL = C.SOL
for f in range(n):
    src = cv2.imread(f'frames/f{f+1:04d}.jpg')
    v = SOL.get(str(f))
    if v and v['seg'] in hoodie.SEGMENTS:
        dx, dy, ms = C.TWEAK.get(v['seg'], (0.0, 0.0, 1.0))
        Hw = v['w'] * ms
        src = hoodie.apply(src, v['cx'] + dx * Hw, v['cy'] + dy * Hw, Hw)
    im = C.build(f, src)
    cv2.imwrite(f'{OUT}/g{f+1:04d}.png', im)
    if (f + 1) % 60 == 0:
        print('  ', f + 1, '/', n, flush=True)

FF = imageio_ffmpeg.get_ffmpeg_exe()
cmd = [FF, '-y', '-framerate', '30', '-i', f'{OUT}/g%04d.png',
       '-i', 'src.mp4', '-map', '0:v:0', '-map', '1:a:0',
       '-c:v', 'libx264', '-preset', 'slow', '-crf', '17',
       '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-level', '4.0',
       '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart',
       '-shortest', 'gorilla_tag_out.mp4']
print(' '.join(cmd), flush=True)
subprocess.run(cmd, check=True)
print('done')
