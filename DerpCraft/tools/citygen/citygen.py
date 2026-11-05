#!/usr/bin/env python3
"""
citygen.py - procedural city generator for DerpCraft worlds.

    python citygen.py --world "game/saves/My City" --size 256 --seed 7 --map city.png

Close DerpCraft first (or at least go back to the main menu). The world must have been opened in the game once.
Every run backs up the region files it rewrites (<world>/region_backup/<time>/); --undo puts the last backup back.

What it builds (all parameters are seeded, the same seed gives the same city):
  * a street grid with avenues: asphalt, lane markings, crosswalks, raised sidewalks, street lamps, trees,
    street name signs at the crossings
  * downtown: glass skyscrapers with tiers, lit floors (sea lanterns), lobbies with doors, rooftop gear and antennas
  * midtown: brick / stone / terracotta apartment and office blocks, shops with awnings, water towers
  * outskirts: houses with gable roofs, fenced gardens, paths, trees
  * parks with ponds, a central plaza with a fountain, parking lots
Use --dry-run with --map to look at the plan (top-down PNG) without touching the world.

For your own buildings import this module: Frame gives lot-local coordinates (u along the street, v into the lot,
'front' = the street side) so doors and stairs face the right way; see the README.
"""
from __future__ import annotations

import argparse
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from derpcraft_world import World, META, OPPOSITE, DXZ, stairs  # noqa: E402

# zones by distance from the centre (0 = centre, 1 = edge of the area): skyscrapers / mid-rise / houses
DOWNTOWN, MIDTOWN = 0.5, 0.8
RIGHT = {"north": "east", "east": "south", "south": "west", "west": "north"}


# ============================================================================================== lot frame
class Frame:
    """
    Lot-local coordinates. The viewer stands in the street looking at the lot:
      u = 0..W-1 left to right along the street, v = 0..D-1 from the street edge into the lot, y = world y.
    front = world direction of the street as seen from the lot ("north", ...).
    Local directions for metas: "front", "back", "left", "right".
    """

    def __init__(self, w: World, x0, z0, x1, z1, front: str):
        self.w = w
        self.front = front
        self.vdir = OPPOSITE[front]
        self.udir = RIGHT[self.vdir]
        ux, uz = DXZ[self.udir]
        vx, vz = DXZ[self.vdir]
        self.ux, self.uz, self.vx, self.vz = ux, uz, vx, vz
        self.ox = x0 if (ux > 0 or vx > 0) else x1
        self.oz = z0 if (uz > 0 or vz > 0) else z1
        sx, sz = x1 - x0 + 1, z1 - z0 + 1
        self.W = sx if ux != 0 else sz
        self.D = sz if vz != 0 else sx

    def xz(self, u, v):
        return self.ox + u * self.ux + v * self.vx, self.oz + u * self.uz + v * self.vz

    def dir(self, local: str) -> str:
        return {"front": self.front, "back": self.vdir, "right": self.udir, "left": OPPOSITE[self.udir]}[local]

    def set(self, u, y, v, block, meta=0):
        x, z = self.xz(u, v)
        self.w.set(x, y, z, block, meta)

    def get(self, u, y, v):
        x, z = self.xz(u, v)
        return self.w.get(x, y, z)

    def fill(self, u0, y0, v0, u1, y1, v1, block, meta=0, only_air=False):
        xa, za = self.xz(u0, v0)
        xb, zb = self.xz(u1, v1)
        self.w.fill(xa, y0, za, xb, y1, zb, block, meta, only_air)

    def walls(self, u0, y0, v0, u1, y1, v1, block, meta=0):
        self.fill(u0, y0, v0, u1, y1, v0, block, meta)
        self.fill(u0, y0, v1, u1, y1, v1, block, meta)
        self.fill(u0, y0, v0, u0, y1, v1, block, meta)
        self.fill(u1, y0, v0, u1, y1, v1, block, meta)

    def stairs(self, u, y, v, block, high: str, upside=False):
        """Stairs whose high side is the local direction 'high' (front/back/left/right)."""
        self.set(u, y, v, block, stairs(self.dir(high), upside))

    def door(self, u, y, v, door="oak_door", hinge_right=False):
        """Door in the front wall line, panel on the street side."""
        x, z = self.xz(u, v)
        self.w.set_door(x, y, z, door, self.front, hinge_right)

    def sub(self, u0, v0, u1, v1) -> "Frame":
        xa, za = self.xz(u0, v0)
        xb, zb = self.xz(u1, v1)
        return Frame(self.w, min(xa, xb), min(za, zb), max(xa, xb), max(za, zb), self.front)


def ring(w_or_f, u0, y, v0, u1, v1, block, meta=0):
    w_or_f.walls(u0, y, v0, u1, y, v1, block, meta)


# ============================================================================================== small things
def tree(w: World, x, y, z, rng: random.Random, kind=None):
    """A tree standing on (x, y-1, z)."""
    kind = kind or rng.choice(["oak", "oak", "birch", "spruce", "dark_oak"])
    if kind == "spruce":
        h = rng.randint(6, 9)
        w.fill(x, y, z, x, y + h - 1, z, "spruce_log")
        r = 2
        for i, yy in enumerate(range(y + 2, y + h + 1)):
            rr = max(0, r - (i // 2) % 3) if yy < y + h else 0
            rr = 2 if (y + h - yy) > 4 and i % 2 == 0 else (1 if (y + h - yy) > 1 else 0)
            for dx in range(-rr, rr + 1):
                for dz in range(-rr, rr + 1):
                    if abs(dx) + abs(dz) <= rr + (1 if rr == 2 else 0) and w.get(x + dx, yy, z + dz) == 0:
                        w.set(x + dx, yy, z + dz, "spruce_leaves")
        w.set(x, y + h, z, "spruce_leaves")
        return
    log, leaves = {"oak": ("oak_log", "oak_leaves"), "birch": ("birch_log", "birch_leaves"),
                   "dark_oak": ("dark_oak_log", "dark_oak_leaves")}.get(kind, ("oak_log", "oak_leaves"))
    h = rng.randint(4, 6)
    w.fill(x, y, z, x, y + h - 1, z, log)
    cy = y + h - 1
    for dy in range(-2, 2):
        r = 2 if dy < 1 else 1
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if dx * dx + dz * dz <= r * r + (1 if rng.random() < 0.5 else 0) and w.get(x + dx, cy + dy, z + dz) == 0:
                    w.set(x + dx, cy + dy, z + dz, leaves)
    w.set(x, cy + 2, z, leaves)


def lamp(w: World, x, y, z, pole="dark_oak_fence", light="sea_lantern", h=4):
    w.fill(x, y, z, x, y + h - 1, z, pole)
    w.set(x, y + h, z, light)
    w.set(x, y + h + 1, z, "stone_slab", META.SLAB_BOTTOM)


# ============================================================================================== buildings
GLASS = ["light_blue_stained_glass", "cyan_stained_glass", "gray_stained_glass", "glass", "blue_stained_glass", "black_stained_glass"]
TOWER_FRAME = ["white_concrete", "light_gray_concrete", "quartz_block", "black_concrete", "gray_concrete", "polished_andesite", "smooth_sandstone"]
MID_WALL = ["bricks", "stone_bricks", "smooth_sandstone", "white_terracotta", "light_gray_terracotta", "orange_terracotta",
            "brown_terracotta", "red_nether_bricks", "polished_granite", "polished_diorite", "terracotta", "quartz_block"]
MID_TRIM = ["stone_bricks", "quartz_block", "polished_andesite", "smooth_sandstone", "white_concrete", "chiseled_stone_bricks"]
HOUSE_WALL = ["oak_planks", "spruce_planks", "birch_planks", "white_concrete", "bricks", "white_terracotta", "smooth_sandstone", "light_gray_concrete"]
HOUSE_ROOF = ["spruce", "dark_oak", "brick", "stone_brick", "acacia", "nether_brick", "oak"]
AWNING = ["red", "green", "blue", "white", "orange", "yellow", "black", "cyan", "purple"]


def tower(f: Frame, y0: int, H: int, rng: random.Random, stats: dict):
    """Glass skyscraper on the whole frame; y0 = ground level (floor), H = height above it."""
    W, D = f.W, f.D
    frame = rng.choice(TOWER_FRAME)
    glass = rng.choice(GLASS)
    floor_mat = rng.choice(["light_gray_concrete", "polished_andesite", "white_concrete"])
    bay = rng.choice([3, 4, 4, 5])
    fh = 4
    tiers = [(0, 0, W - 1, D - 1, y0, y0 + H)]
    if H > 36 and min(W, D) >= 13 and rng.random() < 0.75:
        cut = y0 + int(H * rng.uniform(0.45, 0.7))
        tiers = [(0, 0, W - 1, D - 1, y0, cut)]
        ins = 2 if min(W, D) < 18 else 3
        tiers.append((ins, ins, W - 1 - ins, D - 1 - ins, cut, y0 + H))
        if H > 70 and min(W, D) - 2 * ins >= 11 and rng.random() < 0.6:
            cut2 = cut + int((y0 + H - cut) * 0.6)
            tiers[-1] = (ins, ins, W - 1 - ins, D - 1 - ins, cut, cut2)
            i2 = ins + 2
            tiers.append((i2, i2, W - 1 - i2, D - 1 - i2, cut2, y0 + H))
    for ti, (u0, v0, u1, v1, yb, yt) in enumerate(tiers):
        f.walls(u0, yb + 1, v0, u1, yt, v1, glass)
        # columns (vertical mullions) and corners
        for u in range(u0, u1 + 1):
            if (u - u0) % bay == 0 or u == u1:
                f.fill(u, yb + 1, v0, u, yt, v0, frame)
                f.fill(u, yb + 1, v1, u, yt, v1, frame)
        for v in range(v0, v1 + 1):
            if (v - v0) % bay == 0 or v == v1:
                f.fill(u0, yb + 1, v, u0, yt, v, frame)
                f.fill(u1, yb + 1, v, u1, yt, v, frame)
        # floors: frame band outside, floor slab inside with ceiling lights
        y = yb
        while y < yt:
            ring(f, u0, y, v0, u1, v1, frame)
            if u1 - u0 >= 2 and v1 - v0 >= 2:
                f.fill(u0 + 1, y, v0 + 1, u1 - 1, y, v1 - 1, floor_mat)
                if y > y0:
                    for u in range(u0 + 2, u1 - 1, 4):
                        for v in range(v0 + 2, v1 - 1, 4):
                            f.set(u, y, v, "sea_lantern")
            y += fh
        # roof of this tier
        f.fill(u0, yt, v0, u1, yt, v1, frame)
        ring(f, u0, yt + 1, v0, u1, v1, frame)
    # lobby: glass ground floor behind the columns, double door in the middle of the front
    u0, v0, u1, v1 = tiers[0][:4]
    mid = (u0 + u1) // 2
    f.fill(mid - 1, y0 + 1, v0, mid + 1, y0 + 3, v0, glass)
    f.door(mid - 1, y0 + 1, v0, "iron_door", hinge_right=False)
    f.door(mid, y0 + 1, v0, "iron_door", hinge_right=True)
    f.set(mid + 1, y0 + 1, v0, glass); f.set(mid + 1, y0 + 2, v0, glass)
    f.fill(mid - 2, y0 + 4, v0 - 1, mid + 2, y0 + 4, v0 - 1, frame)     # canopy over the entrance
    f.fill(mid - 2, y0 + 1, v0 + 1, mid + 2, y0 + 3, v0 + 2, "air")     # hall behind the door
    # rooftop: AC units, a stair house, an antenna with a red light
    u0, v0, u1, v1, _, top = tiers[-1]
    roof = top + 1
    for _ in range(rng.randint(1, 4)):
        if u1 - u0 < 6 or v1 - v0 < 6:
            break
        a = rng.randint(u0 + 2, u1 - 3); b = rng.randint(v0 + 2, v1 - 3)
        f.fill(a, roof, b, a + 1, roof, b + 1, "iron_block")
    if u1 - u0 >= 8 and v1 - v0 >= 8:
        a, b = u0 + 2, v1 - 4
        f.fill(a, roof, b, a + 2, roof + 2, b + 2, frame)
        f.set(a + 1, roof + 3, b + 1, "lit_redstone_lamp")
    if rng.random() < 0.7:
        cu, cv = (u0 + u1) // 2, (v0 + v1) // 2
        ah = rng.randint(5, 14)
        f.fill(cu, roof, cv, cu, min(253, roof + ah), cv, "iron_bars")
        f.set(cu, min(254, roof + ah + 1), cv, "redstone_block")
    stats["towers"] += 1
    stats["tallest"] = max(stats["tallest"], top + 1 - y0)


def midrise(f: Frame, y0: int, floors: int, rng: random.Random, stats: dict, shop: bool):
    """Brick / stone block with window rows; optional shop ground floor with an awning."""
    W, D = f.W, f.D
    wall = rng.choice(MID_WALL)
    trim = rng.choice([t for t in MID_TRIM if t != wall])
    floor_mat = rng.choice(["oak_planks", "spruce_planks", "light_gray_concrete", "birch_planks"])
    fh = 4
    top = y0 + floors * fh
    f.walls(0, y0 + 1, 0, W - 1, top, D - 1, wall)
    # corner pilasters
    for u, v in ((0, 0), (W - 1, 0), (0, D - 1), (W - 1, D - 1)):
        f.fill(u, y0 + 1, v, u, top, v, trim)
    win_w = rng.choice([1, 1, 2])
    period = 3 if win_w == 1 else 4
    lit = rng.random() < 0.6
    for k in range(floors):
        yb = y0 + k * fh
        if k > 0:
            ring(f, 0, yb, 0, W - 1, D - 1, trim)
        f.fill(1, yb, 1, W - 2, yb, D - 2, floor_mat)
        if lit and k > 0:
            for u in range(2, W - 2, 4):
                for v in range(2, D - 2, 4):
                    f.set(u, yb, v, "glowstone")
        if k == 0 and shop:
            continue
        # windows on all four sides
        for side in range(4):
            length = W if side < 2 else D
            for i in range(2, length - 2):
                if (i - 2) % period >= win_w:
                    continue
                if side == 0:
                    f.fill(i, yb + 2, 0, i, yb + 3, 0, "glass_pane")
                elif side == 1:
                    f.fill(i, yb + 2, D - 1, i, yb + 3, D - 1, "glass_pane")
                elif side == 2:
                    f.fill(0, yb + 2, i, 0, yb + 3, i, "glass_pane")
                else:
                    f.fill(W - 1, yb + 2, i, W - 1, yb + 3, i, "glass_pane")
    # cornice and parapet
    f.fill(0, top, 0, W - 1, top, D - 1, trim)
    ring(f, 0, top + 1, 0, W - 1, D - 1, wall)
    mid = W // 2
    if shop:
        # shop front: big windows, door, awning over the sidewalk
        f.fill(1, y0 + 1, 0, W - 2, y0 + 3, 0, "glass_pane")
        for u in range(0, W, 5):
            f.fill(u, y0 + 1, 0, u, y0 + 3, 0, trim)
        f.fill(mid, y0 + 1, 0, mid, y0 + 2, 0, "air")
        f.door(mid, y0 + 1, 0, "oak_door")
        color = rng.choice(AWNING)
        f.fill(0, y0 + 4, -1, W - 1, y0 + 4, -1, f"{color}_wool")
        if rng.random() < 0.6 and D > 6:
            f.fill(1, y0 + 1, 1, W - 2, y0 + 1, 1, "oak_planks")           # counter
        f.fill(1, y0 + 3, 1, W - 2, y0 + 3, D - 2, "air")
        for u in range(2, W - 2, 3):
            f.set(u, y0 + 4, 2, "glowstone")
    else:
        f.fill(mid, y0 + 1, 0, mid, y0 + 2, 0, "air")
        f.door(mid, y0 + 1, 0, rng.choice(["oak_door", "spruce_door", "dark_oak_door", "birch_door"]))
        f.set(mid, y0 + 3, 0, trim)
    # rooftop: water tower or a stair house
    roof = top + 1
    if rng.random() < 0.45 and W >= 9 and D >= 9:
        cu, cv = rng.randint(3, W - 5), rng.randint(3, D - 5)
        for du, dv in ((0, 0), (2, 0), (0, 2), (2, 2)):
            f.fill(cu + du, roof, cv + dv, cu + du, roof + 2, cv + dv, "spruce_fence")
        f.fill(cu, roof + 3, cv, cu + 2, roof + 6, cv + 2, "spruce_planks")
        f.set(cu + 1, roof + 7, cv + 1, "spruce_slab", META.SLAB_BOTTOM)
        for du in range(3):
            for dv in range(3):
                if (du, dv) != (1, 1):
                    f.set(cu + du, roof + 7, cv + dv, "spruce_stairs", stairs(_toward_center(f, du, dv)))
    elif W >= 7 and D >= 7:
        f.fill(W - 5, roof, D - 5, W - 3, roof + 2, D - 3, wall)
    stats["midrise"] += 1


def _toward_center(f: Frame, du, dv):
    """World direction from a 3x3 ring cell to its centre (for little roof stairs)."""
    du, dv = 1 - du, 1 - dv
    if abs(du) >= abs(dv):
        return f.dir("right") if du > 0 else f.dir("left")
    return f.dir("back") if dv > 0 else f.dir("front")


def house(f: Frame, y0: int, rng: random.Random, stats: dict):
    """Detached house with a gable roof, garden fence, path and a tree. Frame = whole lot."""
    W, D = f.W, f.D
    yard_f = 3 if D >= 13 else 2
    hw = min(W - 2, rng.randint(7, 11))
    hd = min(D - yard_f - 2, rng.randint(6, 9))
    if hw < 5 or hd < 5:
        return
    a = (W - hw) // 2
    b = yard_f
    wall = rng.choice(HOUSE_WALL)
    roof = rng.choice(HOUSE_ROOF)
    corner = rng.choice(["oak_log", "spruce_log", "dark_oak_log", "stone_bricks", wall])
    floors = 1 if rng.random() < 0.55 else 2
    top = y0 + floors * 4 - 1
    # garden: grass, fence along the lot border, path to the door
    f.fill(0, y0, 0, W - 1, y0, D - 1, "grass")
    fence = rng.choice(["oak_fence", "spruce_fence", "birch_fence", "dark_oak_fence"])
    f.fill(0, y0 + 1, 0, W - 1, y0 + 1, 0, fence)
    f.fill(0, y0 + 1, 0, 0, y0 + 1, D - 1, fence)
    f.fill(W - 1, y0 + 1, 0, W - 1, y0 + 1, D - 1, fence)
    f.fill(0, y0 + 1, D - 1, W - 1, y0 + 1, D - 1, fence)
    mid = a + hw // 2
    f.fill(mid, y0, 0, mid, y0, b - 1, rng.choice(["gravel", "stone_bricks", "polished_andesite", "coarse_dirt"]))
    f.set(mid, y0 + 1, 0, "air")
    # body
    f.fill(a, y0, b, a + hw - 1, y0, b + hd - 1, "cobblestone" if rng.random() < 0.5 else "stone_bricks")
    f.walls(a, y0 + 1, b, a + hw - 1, top, b + hd - 1, wall)
    for u, v in ((a, b), (a + hw - 1, b), (a, b + hd - 1), (a + hw - 1, b + hd - 1)):
        f.fill(u, y0 + 1, v, u, top, v, corner)
    floor_mat = rng.choice(["oak_planks", "spruce_planks", "birch_planks"])
    f.fill(a + 1, y0, b + 1, a + hw - 2, y0, b + hd - 2, floor_mat)
    if floors == 2:
        f.fill(a + 1, y0 + 4, b + 1, a + hw - 2, y0 + 4, b + hd - 2, floor_mat)
    # windows
    for k in range(floors):
        wy = y0 + 2 + k * 4
        for u in range(a + 2, a + hw - 2, 3):
            if abs(u - mid) > 0 or k > 0:
                f.set(u, wy, b, "glass_pane"); f.set(u, wy, b + hd - 1, "glass_pane")
        for v in range(b + 2, b + hd - 2, 3):
            f.set(a, wy, v, "glass_pane"); f.set(a + hw - 1, wy, v, "glass_pane")
    f.door(mid, y0 + 1, b, rng.choice(["oak_door", "spruce_door", "birch_door", "dark_oak_door"]))
    f.set(mid, y0 + 3, b + 2, "glowstone")                               # ceiling light in the hall
    # gable roof: ridge along u, slopes to the front and the back, 1 block overhang
    ry = top + 1
    lo_u, hi_u = a - 1, a + hw
    v_front, v_back = b - 1, b + hd
    k = 0
    while v_front + k <= v_back - k:
        y = ry + k
        if v_front + k == v_back - k:
            f.fill(lo_u, y, v_front + k, hi_u, y, v_front + k, f"{roof}_slab", META.SLAB_BOTTOM)
            break
        for u in range(lo_u, hi_u + 1):
            f.stairs(u, y, v_front + k, f"{roof}_stairs", "back")
            f.stairs(u, y, v_back - k, f"{roof}_stairs", "front")
        # gable walls under the roof
        if v_front + k + 1 <= v_back - k - 1 and k > 0:
            f.fill(a, y, v_front + k + 1, a, y, v_back - k - 1, wall)
            f.fill(a + hw - 1, y, v_front + k + 1, a + hw - 1, y, v_back - k - 1, wall)
        k += 1
    if rng.random() < 0.4:
        cu, cv = a + 1, b + hd - 2
        f.fill(cu, top + 1, cv, cu, ry + k + 1, cv, "bricks")
    # garden tree and flowers
    if D - (b + hd) >= 3 or a >= 3:
        tu = 1 + rng.randint(0, 1) if a >= 3 else rng.randint(1, W - 2)
        tv = D - 2 if D - (b + hd) >= 3 else b + 1
        x, z = f.xz(tu, tv)
        tree(f.w, x, y0 + 1, z, rng, rng.choice(["oak", "birch"]))
    for _ in range(rng.randint(0, 4)):
        u, v = rng.randint(1, W - 2), rng.randint(1, b - 1) if b > 1 else 1
        if f.get(u, y0 + 1, v) == 0 and u != mid:
            f.set(u, y0 + 1, v, rng.choice(["poppy", "dandelion", "allium", "tulip_red", "oxeye_daisy", "blue_orchid"]))
    stats["houses"] += 1


def park(w: World, x0, z0, x1, z1, y0, rng: random.Random, stats: dict):
    w.fill(x0, y0, z0, x1, y0, z1, "grass")
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    path = rng.choice(["gravel", "polished_andesite", "stone_bricks", "coarse_dirt"])
    w.fill(cx - 1, y0, z0, cx + 1, y0, z1, path)
    w.fill(x0, y0, cz - 1, x1, y0, cz + 1, path)
    # pond in one quarter
    if (x1 - x0) > 16 and (z1 - z0) > 16:
        qx = rng.choice([(x0 + 3, cx - 3), (cx + 3, x1 - 3)])
        qz = rng.choice([(z0 + 3, cz - 3), (cz + 3, z1 - 3)])
        px, pz = (qx[0] + qx[1]) // 2, (qz[0] + qz[1]) // 2
        r = max(2, min(qx[1] - qx[0], qz[1] - qz[0]) // 2 - 1)
        for x in range(px - r - 1, px + r + 2):
            for z in range(pz - r - 1, pz + r + 2):
                d = math.hypot(x - px, z - pz)
                if d <= r:
                    w.set(x, y0, z, "water"); w.set(x, y0 - 1, z, "sand")
                elif d <= r + 1:
                    w.set(x, y0, z, "sand")
    # trees, benches, lamps, flowers
    for _ in range(max(2, (x1 - x0) * (z1 - z0) // 45)):
        x, z = rng.randint(x0 + 1, x1 - 1), rng.randint(z0 + 1, z1 - 1)
        if w.get_name(x, y0, z)[0] == "grass" and abs(x - cx) > 3 and abs(z - cz) > 3 and w.get(x, y0 + 1, z) == 0:
            tree(w, x, y0 + 1, z, rng)
    for i in range(z0 + 3, z1 - 2, 7):
        if abs(i - cz) > 3:
            w.set(cx - 2, y0 + 1, i, "oak_stairs", stairs("west"))
            w.set(cx + 2, y0 + 1, i, "oak_stairs", stairs("east"))
    lamp(w, cx - 2, y0 + 1, cz - 2); lamp(w, cx + 2, y0 + 1, cz + 2)
    for _ in range((x1 - x0) * (z1 - z0) // 20):
        x, z = rng.randint(x0, x1), rng.randint(z0, z1)
        if w.get_name(x, y0, z)[0] == "grass" and w.get(x, y0 + 1, z) == 0:
            w.set(x, y0 + 1, z, rng.choice(["tall_grass", "tall_grass", "poppy", "dandelion", "oxeye_daisy", "houstonia", "fern"]))
    stats["parks"] += 1


def plaza(w: World, x0, z0, x1, z1, y0, rng: random.Random, stats: dict):
    a, b = rng.sample(["polished_andesite", "stone_bricks", "smooth_sandstone", "polished_diorite", "white_concrete", "light_gray_concrete"], 2)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            w.set(x, y0, z, a if ((x // 2) + (z // 2)) % 2 == 0 else b)
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    r = max(3, min(x1 - x0, z1 - z0) // 5)
    for x in range(cx - r - 1, cx + r + 2):
        for z in range(cz - r - 1, cz + r + 2):
            d = math.hypot(x - cx, z - cz)
            if d <= r:
                w.set(x, y0, z, "water")
                w.set(x, y0 - 1, z, "stone_bricks")
            elif d <= r + 1:
                w.set(x, y0 + 1, z, "stone_brick_slab", META.SLAB_BOTTOM)
    stats["spawn"] = (cx, cz + r + 4)                                     # south of the fountain, looking at it
    w.fill(cx, y0, cz, cx, y0 + 4, cz, "quartz_pillar")
    w.set(cx, y0 + 5, cz, "chiseled_quartz_block")
    w.set(cx, y0 + 6, cz, "sea_lantern")
    for x, z in ((x0 + 2, z0 + 2), (x1 - 2, z0 + 2), (x0 + 2, z1 - 2), (x1 - 2, z1 - 2)):
        w.set(x, y0, z, "grass")
        tree(w, x, y0 + 1, z, rng, "oak")
        lamp(w, x + (3 if x < cx else -3), y0 + 1, z)
    stats["plazas"] += 1


def parking(w: World, x0, z0, x1, z1, y0, rng: random.Random, stats: dict):
    w.fill(x0, y0, z0, x1, y0, z1, "gray_concrete")
    for x in range(x0 + 1, x1, 3):
        w.fill(x, y0, z0 + 1, x, y0, z0 + 5, "white_concrete")
        w.fill(x, y0, z1 - 5, x, y0, z1 - 1, "white_concrete")
    lamp(w, (x0 + x1) // 2, y0 + 1, (z0 + z1) // 2, pole="iron_bars", light="sea_lantern", h=5)
    stats["parking"] += 1


# ============================================================================================== street grid
STREET_NAMES = ["Main St", "Oak St", "Maple St", "Pine St", "Elm St", "Cedar St", "Park St", "Lake St", "Hill St", "Market St",
                "Bridge St", "King St", "Queen St", "Church St", "Mill St", "River St", "Union St", "Grand St", "Spring St",
                "Water St", "High St", "Center St", "Walnut St", "Willow St", "Chestnut St", "Harbor St", "Station St",
                "Sunset Blvd", "Garden St", "Liberty St", "Bay St", "Forest St"]


def ordinal(n):
    return f"{n}{'th' if 10 <= n % 100 <= 20 else {1: 'st', 2: 'nd', 3: 'rd'}.get(n % 10, 'th')}"


def bands(lo, hi, rng, center, block_min, block_max, road, avenue, side):
    """Splits [lo, hi] into alternating road / block bands. Returns (roads, blocks); road = (s, e, is_avenue)."""
    roads, blocks = [], []
    pos = lo
    i = 0
    while True:
        avenue_here = i % 3 == 0
        rw = (avenue if avenue_here else road) + 2 * side
        if pos + rw - 1 > hi:
            break
        roads.append((pos, pos + rw - 1, avenue_here))
        pos += rw
        size = rng.randint(block_min, block_max)
        if pos + size + road + 2 * side - 1 > hi:
            # last block: stretch to the end and close with a road if it fits
            rest = hi - pos + 1
            rw2 = road + 2 * side
            if rest >= block_min + rw2:
                blocks.append((pos, hi - rw2))
                roads.append((hi - rw2 + 1, hi, False))
            elif rest > 4:
                blocks.append((pos, hi))
            break
        blocks.append((pos, pos + size - 1))
        pos += size
        i += 1
    return roads, blocks


def build_city(w: World, cx: int, cz: int, size: int, y0: int, seed: int, log=print) -> dict:
    rng = random.Random(seed)
    half = size // 2
    X0, X1, Z0, Z1 = cx - half, cx + half - 1, cz - half, cz + half - 1
    stats = {"towers": 0, "midrise": 0, "houses": 0, "parks": 0, "plazas": 0, "parking": 0, "tallest": 0, "streets": []}

    log(f"clearing {size}x{size} at {cx},{cz}, ground y={y0}")
    w.fill(X0, y0 + 1, Z0, X1, 255, Z1, "air")
    w.fill(X0, max(1, y0 - 8), Z0, X1, y0 - 1, Z1, "stone", only_air=True)
    w.fill(X0, max(1, y0 - 3), Z0, X1, y0 - 1, Z1, "dirt")
    w.fill(X0, y0, Z0, X1, y0, Z1, "grass")

    ROAD, AVE, SIDE = 7, 11, 3
    xr, xb = bands(X0, X1, rng, cx, 24, 40, ROAD, AVE, SIDE)
    zr, zb = bands(Z0, Z1, rng, cz, 24, 40, ROAD, AVE, SIDE)
    names = STREET_NAMES[:]
    rng.shuffle(names)
    ns_names = [f"{ordinal(i + 1)} Ave" if r[2] else names.pop() for i, r in enumerate(xr)]   # N-S roads (constant x)
    ew_names = [names.pop() if names else f"{ordinal(i + 1)} St" for i, r in enumerate(zr)]
    stats["streets"] = ns_names + ew_names

    log(f"streets: {len(xr)} north-south, {len(zr)} east-west; {len(xb) * len(zb)} city blocks")
    # sidewalks (raised by a bottom slab), then asphalt on top so crossings open up
    for s, e, _ in xr:
        w.fill(s, y0, Z0, e, y0, Z1, "polished_andesite")
        w.fill(s, y0 + 1, Z0, e, y0 + 1, Z1, "stone_slab", META.SLAB_BOTTOM)
    for s, e, _ in zr:
        w.fill(X0, y0, s, X1, y0, e, "polished_andesite")
        w.fill(X0, y0 + 1, s, X1, y0 + 1, e, "stone_slab", META.SLAB_BOTTOM)
    for s, e, _ in xr:
        w.fill(s + SIDE, y0, Z0, e - SIDE, y0 + 1, Z1, "air")
        w.fill(s + SIDE, y0, Z0, e - SIDE, y0, Z1, "gray_concrete")
    for s, e, _ in zr:
        w.fill(X0, y0, s + SIDE, X1, y0 + 1, e - SIDE, "air")
        w.fill(X0, y0, s + SIDE, X1, y0, e - SIDE, "gray_concrete")

    def in_bands(v, bl):
        return any(s <= v <= e for s, e, _ in bl)

    # lane markings and crosswalks
    for s, e, ave in xr:
        c = (s + e) // 2
        for z in range(Z0, Z1 + 1):
            if in_bands(z, zr):
                continue
            if ave:
                w.set(c - 1, y0, z, "yellow_concrete"); w.set(c + 1, y0, z, "yellow_concrete")
                w.set(c, y0, z, "gray_concrete")
            elif z % 4 < 2:
                w.set(c, y0, z, "white_concrete")
    for s, e, ave in zr:
        c = (s + e) // 2
        for x in range(X0, X1 + 1):
            if in_bands(x, xr):
                continue
            if ave:
                w.set(x, y0, c - 1, "yellow_concrete"); w.set(x, y0, c + 1, "yellow_concrete")
            elif x % 4 < 2:
                w.set(x, y0, c, "white_concrete")
    for s, e, _ in xr:          # crosswalks on north-south roads where east-west sidewalks cross
        for zs, ze, _ in zr:
            for zz in list(range(zs, zs + SIDE)) + list(range(ze - SIDE + 1, ze + 1)):
                for x in range(s + SIDE, e - SIDE + 1):
                    w.set(x, y0, zz, "white_concrete" if (x - s) % 2 == 0 else "gray_concrete")
    for s, e, _ in zr:
        for xs, xe, _ in xr:
            for xx in list(range(xs, xs + SIDE)) + list(range(xe - SIDE + 1, xe + 1)):
                for z in range(s + SIDE, e - SIDE + 1):
                    w.set(xx, y0, z, "white_concrete" if (z - s) % 2 == 0 else "gray_concrete")

    # street lamps and trees on the sidewalks (curb side), signs at the crossings
    for s, e, ave in xr:
        for x in (s + SIDE - 1, e - SIDE + 1):
            for z in range(Z0 + 2, Z1 - 1, 12):
                if in_bands(z, zr) or in_bands(z - 1, zr) or in_bands(z + 1, zr):
                    continue
                lamp(w, x, y0 + 1, z)
                if ave and in_bands(z + 6, zr) is False and z + 6 < Z1:
                    w.set(x, y0, z + 6, "grass"); w.set(x, y0 + 1, z + 6, "air")
                    tree(w, x, y0 + 1, z + 6, rng, "oak")
    for s, e, ave in zr:
        for z in (s + SIDE - 1, e - SIDE + 1):
            for x in range(X0 + 8, X1 - 1, 12):
                if in_bands(x, xr) or in_bands(x - 1, xr) or in_bands(x + 1, xr):
                    continue
                lamp(w, x, y0 + 1, z)
                if ave and not in_bands(x + 6, xr) and x + 6 < X1:
                    w.set(x + 6, y0, z, "grass"); w.set(x + 6, y0 + 1, z, "air")
                    tree(w, x + 6, y0 + 1, z, rng, "oak")
    for i, (s, e, _) in enumerate(xr):
        for j, (zs, ze, _) in enumerate(zr):
            x, z = e - 1, ze - 1       # south-east corner of the crossing
            w.set(x, y0 + 1, z, "air")
            w.set_standing_sign(x, y0 + 1, z, 2, [ns_names[i][:15], "", ew_names[j][:15], ""])

    # city blocks
    rmax = half * math.sqrt(2)
    center_block = None
    best = 1e9
    for bx in xb:
        for bz in zb:
            d = math.hypot((bx[0] + bx[1]) / 2 - cx, (bz[0] + bz[1]) / 2 - cz)
            if d < best:
                best, center_block = d, (bx, bz)
    for bx in xb:
        for bz in zb:
            x0, x1 = bx
            z0, z1 = bz
            if x1 - x0 < 5 or z1 - z0 < 5:
                w.fill(x0, y0, z0, x1, y0, z1, "grass")
                continue
            r = math.hypot((x0 + x1) / 2 - cx, (z0 + z1) / 2 - cz) / half
            if (bx, bz) == center_block:
                plaza(w, x0, z0, x1, z1, y0, rng, stats)
                continue
            roll = rng.random()
            if roll < (0.05 if r < DOWNTOWN else 0.12):
                park(w, x0, z0, x1, z1, y0, rng, stats)
                continue
            if roll < (0.08 if r < DOWNTOWN else 0.16) and r > 0.3:
                parking(w, x0, z0, x1, z1, y0, rng, stats)
                continue
            fill_block(w, x0, z0, x1, z1, y0, r, rng, stats)
    stats["area"] = (X0, Z0, X1, Z1)
    stats["ground"] = y0
    return stats


def fill_block(w: World, x0, z0, x1, z1, y0, r, rng, stats):
    """Splits a city block into lots facing the streets and builds on them by zone (r = 0 centre .. 1 edge)."""
    bw, bd = x1 - x0 + 1, z1 - z0 + 1
    long_x = bw >= bd
    short = bd if long_x else bw
    zone = "downtown" if r < DOWNTOWN else ("mid" if r < MIDTOWN else "res")
    w.fill(x0, y0, z0, x1, y0, z1, "polished_andesite" if zone == "downtown" else ("stone_bricks" if zone == "mid" else "grass"))
    # rows: two back-to-back rows when deep enough, else one
    if short >= 22:
        half_ = short // 2
        rows = [("north" if long_x else "west", 0, half_ - 1), ("south" if long_x else "east", half_, short - 1)]
    else:
        rows = [(rng.choice(["north", "south"]) if long_x else rng.choice(["west", "east"]), 0, short - 1)]
    length = bw if long_x else bd
    for front, a, b in rows:
        if zone == "downtown":
            widths = [length] if length <= 26 or rng.random() < 0.35 else _split(length, 12, 22, rng)
        elif zone == "mid":
            widths = _split(length, 9, 16, rng)
        else:
            widths = _split(length, 9, 13, rng)
        pos = 0
        for wd in widths:
            if long_x:
                lx0, lx1, lz0, lz1 = x0 + pos, x0 + pos + wd - 1, z0 + a, z0 + b
            else:
                lx0, lx1, lz0, lz1 = x0 + a, x0 + b, z0 + pos, z0 + pos + wd - 1
            pos += wd
            lot = Frame(w, lx0, lz0, lx1, lz1, front)
            build_lot(lot, y0, zone, r, rng, stats)


def _split(length, lo, hi, rng):
    out, left = [], length
    while left > 0:
        if left <= hi:
            out.append(left)
            break
        n = rng.randint(lo, hi)
        if left - n < lo:
            n = left - lo
        out.append(n)
        left -= n
    return out


def build_lot(lot: Frame, y0, zone, r, rng, stats):
    W, D = lot.W, lot.D
    if W < 6 or D < 6:
        return
    if zone == "downtown":
        sb = 1 if D > 12 else 0
        g = 1 if W > 12 else 0
        f = lot.sub(g, sb, W - 1 - g, D - 1 - sb)
        if f.W < 7 or f.D < 7:
            return
        top_room = 250 - y0
        t = max(0.0, 1 - r / DOWNTOWN)
        H = int(min(top_room - 20, 30 + t ** 0.9 * rng.uniform(40, 170)))
        H = max(20, H - H % 4)
        if rng.random() < 0.2:
            midrise(f, y0, rng.randint(6, 12), rng, stats, shop=True)
        else:
            tower(f, y0, H, rng, stats)
    elif zone == "mid":
        f = lot.sub(0, 0, W - 1, D - 1 - (1 if D > 12 else 0))
        floors = rng.randint(4, 10) if r < (DOWNTOWN + MIDTOWN) / 2 else rng.randint(2, 6)
        floors = min(floors, (240 - y0) // 4)
        midrise(f, y0, floors, rng, stats, shop=rng.random() < 0.55)
    else:
        house(lot, y0, rng, stats)


# ============================================================================================== cli
def main(argv=None):
    ap = argparse.ArgumentParser(description="Generate a city in a DerpCraft world (close the game first).")
    ap.add_argument("--world", required=True, help="world folder: game/saves/<name>")
    ap.add_argument("--center", nargs=2, type=int, metavar=("X", "Z"), help="city centre (default: the saved player position)")
    ap.add_argument("--size", type=int, default=192, help="side of the square city area in blocks (64..1024, default 192)")
    ap.add_argument("--seed", type=int, default=None, help="random seed (default: random, printed)")
    ap.add_argument("--ground", type=int, default=None, help="street level y (default: flat worlds = their top layer, else the terrain height around the centre)")
    ap.add_argument("--map", default=None, help="write a top-down PNG of the city (needs Pillow)")
    ap.add_argument("--dry-run", action="store_true", help="build in memory only (with --map to preview); the world is not changed")
    ap.add_argument("--no-player", action="store_true", help="do not move the saved player to the city centre")
    ap.add_argument("--no-backup", action="store_true", help="do not back up region files (not recommended)")
    ap.add_argument("--undo", action="store_true", help="restore the latest backup of this world and exit")
    a = ap.parse_args(argv)

    if a.undo:
        w = World(a.world, backup=False)
        print("restored", w.restore_backup())
        return 0
    probe = World(a.world, backup=False)
    if a.center:
        cx, cz = a.center
    else:
        cx, cz = int(float(probe.info.get("px", 0))), int(float(probe.info.get("pz", 0)))
    size = max(64, min(1024, a.size))
    ground = a.ground
    if ground is None:
        ground = probe.flat_ground()
    if ground is None:
        hs = []
        for dx in range(-48, 49, 16):
            for dz in range(-48, 49, 16):
                if probe.chunk_exists((cx + dx) >> 4, (cz + dz) >> 4):
                    hs.append(probe.top_y(cx + dx, cz + dz))
        hs = sorted(h for h in hs if h > 0)
        ground = hs[len(hs) // 2] if hs else 64
        print(f"terrain world: street level y={ground} (override with --ground)")
    ground = max(2, min(200, ground))
    seed = a.seed if a.seed is not None else random.randrange(1 << 30)
    w = World(a.world, ground=ground, backup=not a.no_backup)
    stats = build_city(w, cx, cz, size, ground, seed)
    X0, Z0, X1, Z1 = stats["area"]
    if a.map:
        w.render_map(a.map, X0, Z0, X1, Z1, scale=2 if size <= 512 else 1)
        print("map:", a.map)
    if a.dry_run:
        print("dry run: the world was not changed")
    else:
        n = w.save()
        print(f"saved {n} chunks" + (f", backup in {w.backup_dir}" if w.backup_dir else ""))
        if not a.no_player:
            sx, sz = stats.get("spawn", (cx, cz))
            w.set_player(sx + 0.5, ground + 1, sz + 0.5, 180.0)
    print(f"seed {seed}: {stats['towers']} towers (tallest {stats['tallest']} blocks), {stats['midrise']} mid-rise, "
          f"{stats['houses']} houses, {stats['parks']} parks, {stats['plazas']} plaza, {stats['parking']} parking lots")
    print("streets:", ", ".join(stats["streets"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
