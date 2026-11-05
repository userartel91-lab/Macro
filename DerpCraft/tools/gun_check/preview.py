"""Draws the poses dumped by GunPoseDump to PNG frames (flat shaded, painter's order) and a contact sheet.
Usage: python3 preview.py <dump.txt> <texture.png> <out dir>   (see preview.sh)"""
import math, os, sys
from PIL import Image, ImageDraw

W, H = 640, 360
dump, tex_path, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out_dir, exist_ok=True)
tex = Image.open(tex_path).convert('RGBA')
TW, TH = tex.size
SKIN, SHIRT = (196, 140, 106), (0, 160, 160)
LIGHT = (0.3, 0.8, 0.5)

def norm(v):
    l = math.sqrt(sum(c * c for c in v)) or 1
    return [c / l for c in v]

def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]

def sub(a, b): return [a[i] - b[i] for i in range(3)]

def shade(col, p):
    n = norm(cross(sub(p[1], p[0]), sub(p[2], p[0])))
    k = 0.45 + 0.55 * abs(sum(n[i] * LIGHT[i] for i in range(3)))
    return tuple(int(c * k) for c in col[:3])

def limb(a, b, bend, px, upper, SKIN=SKIN):
    y = norm(sub(b, a))
    d = sum(bend[i] * y[i] for i in range(3))
    z = [bend[i] - d * y[i] for i in range(3)]
    if math.sqrt(sum(c * c for c in z)) < 1e-6: z = [0, 0, 1]
    z = norm(z); x = cross(y, z)
    L = math.sqrt(sum(c * c for c in sub(b, a)))
    hw, hd = 2 * px, 2 * px
    c = []
    for i in range(8):
        sx = hw if i & 1 else -hw; sz = hd if i & 2 else -hd; t = L if i & 4 else 0
        c.append([a[k] + x[k] * sx + z[k] * sz + y[k] * t for k in range(3)])
    quads = [(0, 2, 6, 4), (2, 3, 7, 6), (3, 1, 5, 7), (1, 0, 4, 5), (0, 1, 3, 2), (4, 5, 7, 6)]
    out = []
    for q in quads:
        p = [c[i] for i in q]
        col = SKIN
        if upper:   # the short sleeve: the shoulder half of the upper arm
            out.append(([p[0], p[1], [(p[1][k] + p[2][k]) / 2 for k in range(3)], [(p[0][k] + p[3][k]) / 2 for k in range(3)]], SHIRT))
            out.append(([[(p[0][k] + p[3][k]) / 2 for k in range(3)], [(p[1][k] + p[2][k]) / 2 for k in range(3)], p[2], p[3]], SKIN))
        else:
            out.append((p, col))
    return out

frames, cur, arm_no = [], None, 0
for line in open(dump):
    s = line.split()
    if not s: continue
    if s[0] == 'FRAME':
        cur = {'name': s[1], 'aim': float(s[2]), 'polys': []}; frames.append(cur)
    elif s[0] in ('TRI', 'TRIM', 'TRIS'):
        f = list(map(float, s[1:]))
        pts = [f[0:3], f[5:8], f[10:13]]
        u = (f[3] + f[8] + f[13]) / 3; v = (f[4] + f[9] + f[14]) / 3
        col = tex.getpixel((min(TW - 1, int(u * TW)), min(TH - 1, int(v * TH))))
        if s[0] == 'TRIM': col = (220, 40, 40)
        if s[0] == 'TRIS': col = (240, 220, 40)
        cur['polys'].append((pts, col))
    elif s[0] == 'BOX':
        col = tuple(int(v) for v in s[1:4])
        f = list(map(float, s[4:]))
        c = [f[i * 3:i * 3 + 3] for i in range(8)]
        for q in [(0, 2, 6, 4), (2, 3, 7, 6), (3, 1, 5, 7), (1, 0, 4, 5), (0, 1, 3, 2), (4, 5, 7, 6)]:
            cur['polys'].append(([c[i] for i in q], col))
    elif s[0] == 'ARMBOX':
        f = list(map(float, s[1:]))
        c = [f[i * 3:i * 3 + 3] for i in range(8)]
        col = (196, 140, 106) if arm_no % 2 == 0 else (170, 112, 84)
        arm_no += 1
        for q in [(0, 2, 6, 4), (2, 3, 7, 6), (3, 1, 5, 7), (1, 0, 4, 5), (0, 1, 3, 2), (4, 5, 7, 6)]:
            p = [c[i] for i in q]
            # the shirt sleeve: the shoulder third of the arm
            m1 = [[p[0][k] + (p[3][k] - p[0][k]) * 0.33 for k in range(3)], [p[1][k] + (p[2][k] - p[1][k]) * 0.33 for k in range(3)]]
            if q in [(0, 1, 3, 2), (4, 5, 7, 6)]: cur['polys'].append((p, SHIRT if q == (0, 1, 3, 2) else col))
            else:
                cur['polys'].append(([p[0], p[1], m1[1], m1[0]], SHIRT))
                cur['polys'].append(([m1[0], m1[1], p[2], p[3]], col))
    elif s[0] == 'ARM':
        f = list(map(float, s[1:]))
        SKIN = (196, 140, 106) if arm_no % 2 == 0 else (170, 112, 84)   # the left arm a little darker
        arm_no += 1
        sh, el, hd, px = f[0:3], f[3:6], f[6:9], f[9]
        bend = [el[i] - (sh[i] + hd[i]) / 2 for i in range(3)]
        fo = sub(hd, el); fl = math.sqrt(sum(c * c for c in fo)) or 1
        end = [hd[i] + fo[i] / fl * 1.6 * px for i in range(3)]
        cur['polys'] += limb(sh, el, bend, px, True, SKIN) + limb(el, end, bend, px, False, SKIN)

thumbs = []
for fr in frames:
    img = Image.new('RGB', (W, H), (120, 165, 220))
    dr = ImageDraw.Draw(img)
    dr.rectangle([0, H * 0.62, W, H], fill=(96, 128, 64))
    fov = math.radians(70 - 14 * fr['aim'])
    f = (H / 2) / math.tan(fov / 2)
    polys = []
    for pts, col in fr['polys']:
        if any(p[2] > -0.02 for p in pts): continue
        depth = sum(p[2] for p in pts) / len(pts)
        scr = [(W / 2 + p[0] / -p[2] * f, H / 2 - p[1] / -p[2] * f) for p in pts]
        polys.append((depth, scr, shade(col, pts)))
    polys.sort(key=lambda q: q[0])
    for _, scr, col in polys: dr.polygon(scr, fill=col)
    dr.line([W / 2 - 5, H / 2, W / 2 + 5, H / 2], fill=(255, 255, 255)); dr.line([W / 2, H / 2 - 5, W / 2, H / 2 + 5], fill=(255, 255, 255))
    dr.text((6, 6), fr['name'], fill=(255, 255, 255))
    img.save(os.path.join(out_dir, fr['name'] + '.png'))
    thumbs.append(img)

cols = 4
rows = (len(thumbs) + cols - 1) // cols
sheet = Image.new('RGB', (cols * W // 2, rows * H // 2), (0, 0, 0))
for i, im in enumerate(thumbs):
    sheet.paste(im.resize((W // 2, H // 2)), ((i % cols) * W // 2, (i // cols) * H // 2))
sheet.save(os.path.join(out_dir, 'sheet.png'))
print('frames:', len(frames), '->', out_dir)
