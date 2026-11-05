# Usage: python3 tools/blood_textures.py <textures/blocks folder> [preview.png]
# 16x16 Minecraft-style blood splats: blood_0..2 small (drops), blood_3..5 big (pools)
import random, sys
from PIL import Image
out = sys.argv[1]
PAL = [(128, 8, 8), (158, 12, 12), (184, 18, 16), (208, 28, 24), (230, 52, 44)]
def blob(seed, r, satellites, drips):
    rnd = random.Random(seed)
    m = [[0]*16 for _ in range(16)]
    cx, cy = 7.5 + rnd.uniform(-0.6, 0.6), 7.5 + rnd.uniform(-0.6, 0.6)
    # main blob: a noisy disc
    for y in range(16):
        for x in range(16):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < r + rnd.uniform(-0.9, 0.9): m[y][x] = 1
    # grow a few lumps on the edge
    for _ in range(int(r * 3)):
        ys = [(x, y) for y in range(16) for x in range(16) if m[y][x]]
        x, y = rnd.choice(ys)
        dx, dy = rnd.choice([(1,0),(-1,0),(0,1),(0,-1)])
        if 0 <= x+dx < 16 and 0 <= y+dy < 16: m[y+dy][x+dx] = 1
    # satellite drops
    for _ in range(satellites):
        a = rnd.uniform(0, 6.283); d = r + rnd.uniform(1.5, 3.5)
        x, y = int(cx + d * __import__('math').cos(a)), int(cy + d * __import__('math').sin(a))
        if 0 <= x < 16 and 0 <= y < 16:
            m[y][x] = 1
            if rnd.random() < 0.4 and x + 1 < 16: m[y][x+1] = 1
    # drips: short streaks out of the blob
    for _ in range(drips):
        x = int(cx + rnd.uniform(-r * 0.6, r * 0.6)); y = int(cy + r - 1)
        for k in range(rnd.randint(2, 4)):
            if 0 <= y + k < 16: m[y + k][x] = 1
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            if not m[y][x]: continue
            # darker at the rim, lighter (wet) in the middle, a few shiny pixels
            n = sum(m[y+dy][x+dx] for dy in (-1,0,1) for dx in (-1,0,1) if 0 <= x+dx < 16 and 0 <= y+dy < 16)
            k = 0 if n <= 4 else 1 if n <= 6 else 2 if n <= 8 else 3
            if k == 3 and rnd.random() < 0.12: k = 4
            if k >= 2 and rnd.random() < 0.18: k -= 1
            img.putpixel((x, y), PAL[k] + (255,))
    return img
specs = [(11, 1.6, 3, 0), (23, 2.2, 4, 0), (37, 1.3, 5, 0), (41, 4.6, 5, 1), (57, 5.6, 6, 2), (73, 6.4, 4, 1)]
for i, (seed, r, sat, dr) in enumerate(specs):
    blob(seed, r, sat, dr).save(f"{out}/blood_{i}.png")
# a preview sheet x16
# optional: a preview sheet (x12) as the second argument
if len(sys.argv) > 2:
    sheet = Image.new('RGBA', (16 * 6 * 12 + 50, 16 * 12 + 20), (120, 160, 90, 255))
    for i in range(6):
        im = Image.open(f"{out}/blood_{i}.png").resize((192, 192), Image.NEAREST)
        sheet.alpha_composite(im, (10 + i * 202, 10))
    sheet.save(sys.argv[2])
