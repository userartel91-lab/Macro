"""
derpcraft_world.py - read and write DerpCraft worlds from Python (no game needed).

DerpCraft save format (game/saves/<world>/):
  level.properties   world metadata (Java Properties): generator=default|flat, flatLayers, px/py/pz (player), ...
  blockids.txt       "<block name>=<world id>" per line; written by the game the first time it opens the world
  region/r.<rx>.<rz>.dcr   32x32 chunks per file:
      header: 1024 x (int offset, int length), big endian, index = (cz & 31) * 32 + (cx & 31)
      blobs: zlib-deflated chunk data, each starting at a multiple of 4096
  chunk blob (version 2):
      int version | byte[256] temperature | byte[256] humidity | ushort section mask
      per set bit i (section y = i*16 .. i*16+15): 4096 x ushort state, index = (y << 8) | (z << 4) | x
          state = (world id << 4) | meta
      int block entity count, per entity:
          int key = (y << 8) | ((z & 15) << 4) | (x & 15) | writeUTF type | short pair count
          per pair: writeUTF key | int byte length + UTF-8 value
  Light is NOT stored: the game relights loaded chunks.

Rules:
  * Close the game (or at least leave the world) before writing, or it will overwrite your edits on its next save.
  * The world must have been opened in the game once (blockids.txt must exist).
  * Every save backs up the region files it rewrites into <world>/region_backup/<timestamp>/ (World.restore_backup()).

Quick start:
    from derpcraft_world import World
    w = World("path/to/game/saves/MyWorld", ground=64)   # ground: level for chunks that were never generated
    w.fill(0, 64, 0, 9, 70, 9, "stone_bricks")
    w.set(5, 71, 5, "oak_stairs", 2)                        # name + meta, or "oak_stairs:2"
    w.set_wall_sign(5, 66, -1, "north", ["Main St"])
    w.save()
    w.render_map("map.png", -32, -32, 48, 48)

Coordinates: x east, z south, y up (0..255). Block metas: see META below and the README.
"""
from __future__ import annotations

import datetime
import os
import shutil
import struct
import zlib
from pathlib import Path

try:
    import numpy as np
except ImportError as e:  # pragma: no cover
    raise SystemExit("derpcraft_world needs numpy: pip install numpy") from e

HEIGHT = 256
CHUNK_VERSION = 2

# ---------------------------------------------------------------------------------------------- metadata helpers
# Horizontal direction names used by the helpers below.
DIRS = ("north", "east", "south", "west")
DXZ = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


class META:
    """Metadata values of DerpCraft blocks (checked against the Java code)."""
    # stairs: the HIGH (back) side of the stair; +4 = upside down
    STAIRS_HIGH = {"south": 0, "west": 1, "north": 2, "east": 3}
    STAIRS_UPSIDE = 4
    # slabs
    SLAB_BOTTOM, SLAB_TOP = 0, 1
    # logs, quartz_pillar, purpur_pillar, hay_block, bone_block
    AXIS_Y, AXIS_X, AXIS_Z = 0, 1, 2
    # furnace, pumpkin, jack_o_lantern, dispenser...: the side the front texture faces
    FACING = {"south": 0, "west": 1, "north": 2, "east": 3}
    # torch / redstone_torch: 0 standing on the floor, else the wall it is attached to
    TORCH = {"floor": 0, "south": 1, "west": 2, "north": 3, "east": 4}
    # wall_sign: the side of the block the board is on (the wall behind it); the text faces the other way
    WALL_SIGN = {"north": 0, "east": 1, "south": 2, "west": 3}
    # doors (lower half): the side of the block the closed door panel is on; +4 = open
    DOOR_SIDE = {"north": 0, "east": 1, "south": 2, "west": 3}
    DOOR_OPEN = 4
    DOOR_UPPER = 8          # upper half: 8 | (1 if hinge on the right else 0)


def stairs(high_side: str, upside_down: bool = False) -> int:
    """Meta for stairs whose high (back) part is on 'high_side'. For a roof sloping down towards the south: high='north'."""
    return META.STAIRS_HIGH[high_side] | (4 if upside_down else 0)


# ---------------------------------------------------------------------------------------------- java strings
def _write_utf(s: str) -> bytes:
    """Java DataOutputStream.writeUTF (modified UTF-8, 2-byte length)."""
    out = bytearray()
    for ch in s:
        cp = ord(ch)
        if cp > 0xFFFF:  # supplementary: two surrogates, each as 3 bytes
            cp -= 0x10000
            for u in (0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)):
                out += bytes((0xE0 | (u >> 12), 0x80 | ((u >> 6) & 0x3F), 0x80 | (u & 0x3F)))
        elif 0 < cp < 0x80:
            out.append(cp)
        elif cp < 0x800:
            out += bytes((0xC0 | (cp >> 6), 0x80 | (cp & 0x3F)))
        else:
            out += bytes((0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)))
    if len(out) > 65535:
        raise ValueError("string too long for writeUTF")
    return struct.pack(">H", len(out)) + bytes(out)


def _read_utf(buf: bytes, pos: int):
    n = struct.unpack_from(">H", buf, pos)[0]
    pos += 2
    data = buf[pos:pos + n]
    units, i = [], 0
    while i < n:
        b = data[i]
        if b < 0x80:
            units.append(b); i += 1
        elif (b & 0xE0) == 0xC0:
            units.append(((b & 0x1F) << 6) | (data[i + 1] & 0x3F)); i += 2
        else:
            units.append(((b & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)); i += 3
    s = b"".join(struct.pack(">H", u) for u in units).decode("utf-16-be", "surrogatepass")
    return s, pos + n


def _write_long(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">i", len(b)) + b


# ---------------------------------------------------------------------------------------------- chunk
class Chunk:
    __slots__ = ("cx", "cz", "blocks", "temperature", "humidity", "entities", "dirty")

    def __init__(self, cx: int, cz: int):
        self.cx, self.cz = cx, cz
        # world states, index [y, z, x] (same order as the file)
        self.blocks = np.zeros((HEIGHT, 16, 16), dtype=np.uint16)
        self.temperature = bytes([204] * 256)   # plains-like (FlatGenerator)
        self.humidity = bytes([102] * 256)
        # key -> (type, {k: v})
        self.entities: dict[int, tuple[str, dict]] = {}
        self.dirty = False

    @staticmethod
    def decode(cx: int, cz: int, blob: bytes) -> "Chunk":
        data = zlib.decompress(blob)
        c = Chunk(cx, cz)
        ver = struct.unpack_from(">i", data, 0)[0]
        if ver not in (1, 2):
            raise ValueError(f"chunk {cx},{cz}: unknown version {ver}")
        pos = 4
        c.temperature = data[pos:pos + 256]; pos += 256
        c.humidity = data[pos:pos + 256]; pos += 256
        mask = struct.unpack_from(">H", data, pos)[0]; pos += 2
        for i in range(16):
            if mask & (1 << i):
                sec = np.frombuffer(data, dtype=">u2", count=4096, offset=pos).reshape(16, 16, 16)
                c.blocks[i * 16:(i + 1) * 16] = sec
                pos += 8192
        if ver >= 2:
            n = struct.unpack_from(">i", data, pos)[0]; pos += 4
            for _ in range(n):
                key = struct.unpack_from(">i", data, pos)[0]; pos += 4
                typ, pos = _read_utf(data, pos)
                pairs = struct.unpack_from(">h", data, pos)[0]; pos += 2
                kv = {}
                for _ in range(pairs):
                    k, pos = _read_utf(data, pos)
                    ln = struct.unpack_from(">i", data, pos)[0]; pos += 4
                    kv[k] = data[pos:pos + ln].decode("utf-8"); pos += ln
                c.entities[key] = (typ, kv)
        return c

    def encode(self) -> bytes:
        out = bytearray(struct.pack(">i", CHUNK_VERSION))
        out += bytes(self.temperature[:256]).ljust(256, b"\0")
        out += bytes(self.humidity[:256]).ljust(256, b"\0")
        secs = self.blocks.reshape(16, 4096)
        nonempty = secs.any(axis=1)
        mask = 0
        for i in range(16):
            if nonempty[i]:
                mask |= 1 << i
        out += struct.pack(">H", mask)
        for i in range(16):
            if mask & (1 << i):
                out += secs[i].astype(">u2").tobytes()
        ents = [(k, v) for k, v in self.entities.items()]
        out += struct.pack(">i", len(ents))
        for key, (typ, kv) in ents:
            out += struct.pack(">i", key)
            out += _write_utf(typ)
            out += struct.pack(">h", len(kv))
            for k, v in kv.items():
                out += _write_utf(k)
                out += _write_long(str(v))
        return zlib.compress(bytes(out), 1)


# ---------------------------------------------------------------------------------------------- region files
class _Region:
    def __init__(self, path: Path):
        self.path = path
        self.table = [(0, 0)] * 1024
        self.raw = None
        if path.exists():
            self.raw = path.read_bytes()
            if len(self.raw) >= 8192:
                self.table = [struct.unpack_from(">ii", self.raw, i * 8) for i in range(1024)]

    def blob(self, idx: int):
        off, ln = self.table[idx]
        if self.raw is None or off == 0 or ln <= 0 or off + ln > len(self.raw):
            return None
        return self.raw[off:off + ln]

    def write(self, replaced: dict[int, bytes]):
        """Writes a compact new file: kept chunks + replaced ones, 4 KiB aligned like the game."""
        blobs = {}
        for i in range(1024):
            b = replaced.get(i)
            if b is None:
                b = self.blob(i)
            if b:
                blobs[i] = b
        header = bytearray(8192)
        body = bytearray()
        off = 8192
        for i in sorted(blobs):
            b = blobs[i]
            struct.pack_into(">ii", header, i * 8, off + len(body), len(b))
            body += b
            pad = (-len(b)) % 4096
            body += b"\0" * pad
        tmp = self.path.with_suffix(".dcr.tmp")
        tmp.write_bytes(bytes(header) + bytes(body))
        os.replace(tmp, self.path)
        self.raw = bytes(header) + bytes(body)
        self.table = [struct.unpack_from(">ii", self.raw, i * 8) for i in range(1024)]


# ---------------------------------------------------------------------------------------------- world
def _read_properties(path: Path) -> dict:
    props = {}
    if not path.exists():
        return props
    for line in path.read_text(encoding="latin-1").splitlines():
        s = line.strip()
        if not s or s[0] in "#!":
            continue
        # Java Properties: key ends at the first unescaped '=' or ':'
        k, v, i, esc = "", "", 0, False
        while i < len(s):
            ch = s[i]
            if esc:
                k += ch; esc = False
            elif ch == "\\":
                esc = True
            elif ch in "=:":
                v = s[i + 1:]
                break
            else:
                k += ch
            i += 1
        props[k.strip()] = v.strip().replace("\\:", ":").replace("\\=", "=").replace("\\\\", "\\")
    return props


class World:
    """
    An open DerpCraft world. Edits stay in memory until save().

    ground: what to put into chunks that were never generated. Flat worlds use their own layers automatically;
            default (terrain) worlds need ground=<y> (bedrock, stone, 3 dirt, grass at y) or such chunks raise.
    """

    def __init__(self, world_dir, ground: int | None = None, backup: bool = True):
        self.dir = Path(world_dir)
        if not (self.dir / "level.properties").exists():
            raise FileNotFoundError(f"{self.dir} is not a DerpCraft world (no level.properties)")
        ids_file = self.dir / "blockids.txt"
        if not ids_file.exists():
            raise FileNotFoundError("blockids.txt missing: open this world in DerpCraft once, then quit, and run again")
        self.ids: dict[str, int] = {}
        for line in ids_file.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                self.ids[k.strip()] = int(v.strip())
        self.names = {v: k for k, v in self.ids.items()}
        self.info = _read_properties(self.dir / "level.properties")
        self.ground = ground
        self.backup = backup
        self._regions: dict[tuple[int, int], _Region] = {}
        self._chunks: dict[tuple[int, int], Chunk] = {}
        self._flat_column = self._parse_flat() if self.info.get("generator") == "flat" else None
        self.backup_dir: Path | None = None

    # ------------------------------------------------------------------ names <-> states
    def state(self, block, meta: int = 0) -> int:
        """'stone', 'oak_stairs:2', ('oak_stairs', 2) or an int state -> world state."""
        if isinstance(block, (int, np.integer)):
            return int(block)
        if isinstance(block, tuple):
            block, meta = block
        if ":" in block:
            block, m = block.rsplit(":", 1)
            meta = int(m)
        if block == "air":
            return 0
        wid = self.ids.get(block)
        if wid is None:
            raise KeyError(f"unknown block '{block}' (see blockids.txt of the world; mod blocks need the mod installed)")
        return (wid << 4) | (meta & 15)

    def has_block(self, name: str) -> bool:
        return name == "air" or name in self.ids

    def name_of(self, state: int):
        """World state -> (name, meta)."""
        return self.names.get(state >> 4, "air" if state == 0 else f"#{state >> 4}"), state & 15

    # ------------------------------------------------------------------ chunks
    def _parse_flat(self):
        col = []
        for part in self.info.get("flatLayers", "bedrock,2*dirt,grass").split(","):
            part = part.strip()
            if not part:
                continue
            n = 1
            if "*" in part:
                a, part = part.split("*", 1)
                n = int(a.strip()); part = part.strip()
            st = (self.ids[part] << 4) if part in self.ids else (self.ids.get("stone", 1) << 4)
            col += [st] * n
        return col[:255] or [self.ids.get("grass", 2) << 4]

    def flat_ground(self):
        """Top layer y of a flat world (None for terrain worlds)."""
        return len(self._flat_column) - 1 if self._flat_column else None

    def _region(self, rx, rz) -> _Region:
        r = self._regions.get((rx, rz))
        if r is None:
            r = _Region(self.dir / "region" / f"r.{rx}.{rz}.dcr")
            self._regions[(rx, rz)] = r
        return r

    def chunk_exists(self, cx: int, cz: int) -> bool:
        return (cx, cz) in self._chunks or self._region(cx >> 5, cz >> 5).blob(((cz & 31) << 5) | (cx & 31)) is not None

    def chunk(self, cx: int, cz: int) -> Chunk:
        c = self._chunks.get((cx, cz))
        if c is not None:
            return c
        blob = self._region(cx >> 5, cz >> 5).blob(((cz & 31) << 5) | (cx & 31))
        if blob is not None:
            c = Chunk.decode(cx, cz, blob)
        else:
            c = Chunk(cx, cz)
            if self._flat_column:
                for y, st in enumerate(self._flat_column):
                    c.blocks[y] = st
            elif self.ground is not None:
                g = self.ground
                c.blocks[0] = self.state("bedrock")
                c.blocks[1:max(1, g - 3)] = self.state("stone")
                c.blocks[max(1, g - 3):g] = self.state("dirt")
                c.blocks[g] = self.state("grass")
            else:
                raise RuntimeError(f"chunk {cx},{cz} was never generated: walk there in the game first, or pass ground=<y>")
            c.dirty = True
        self._chunks[(cx, cz)] = c
        return c

    # ------------------------------------------------------------------ blocks
    def get(self, x: int, y: int, z: int) -> int:
        if not 0 <= y < HEIGHT:
            return 0
        return int(self.chunk(x >> 4, z >> 4).blocks[y, z & 15, x & 15])

    def get_name(self, x, y, z):
        return self.name_of(self.get(x, y, z))

    def set(self, x: int, y: int, z: int, block, meta: int = 0):
        if not 0 <= y < HEIGHT:
            return
        st = self.state(block, meta)
        c = self.chunk(x >> 4, z >> 4)
        c.blocks[y, z & 15, x & 15] = st
        c.dirty = True
        key = (y << 8) | ((z & 15) << 4) | (x & 15)
        if key in c.entities:
            del c.entities[key]

    def fill(self, x0, y0, z0, x1, y1, z1, block, meta: int = 0, only_air: bool = False):
        """Fills the box (inclusive corners, any order). only_air: keep blocks that are already there."""
        st = self.state(block, meta)
        x0, x1 = sorted((x0, x1)); y0, y1 = sorted((y0, y1)); z0, z1 = sorted((z0, z1))
        y0, y1 = max(0, y0), min(HEIGHT - 1, y1)
        if y0 > y1:
            return
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                c = self.chunk(cx, cz)
                ax0, ax1 = max(x0, cx * 16) - cx * 16, min(x1, cx * 16 + 15) - cx * 16
                az0, az1 = max(z0, cz * 16) - cz * 16, min(z1, cz * 16 + 15) - cz * 16
                view = c.blocks[y0:y1 + 1, az0:az1 + 1, ax0:ax1 + 1]
                if only_air:
                    view[view == 0] = st
                else:
                    view[...] = st
                if c.entities:
                    for key in [k for k in c.entities if y0 <= (k >> 8) <= y1 and az0 <= ((k >> 4) & 15) <= az1 and ax0 <= (k & 15) <= ax1]:
                        del c.entities[key]
                c.dirty = True

    def replace(self, x0, y0, z0, x1, y1, z1, src, dst):
        a, b = self.state(src), self.state(dst)
        x0, x1 = sorted((x0, x1)); y0, y1 = sorted((y0, y1)); z0, z1 = sorted((z0, z1))
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                c = self.chunk(cx, cz)
                ax0, ax1 = max(x0, cx * 16) - cx * 16, min(x1, cx * 16 + 15) - cx * 16
                az0, az1 = max(z0, cz * 16) - cz * 16, min(z1, cz * 16 + 15) - cz * 16
                view = c.blocks[y0:y1 + 1, az0:az1 + 1, ax0:ax1 + 1]
                view[view == a] = b
                c.dirty = True

    def walls(self, x0, y0, z0, x1, y1, z1, block, meta: int = 0):
        """The four vertical walls of a box."""
        x0, x1 = sorted((x0, x1)); z0, z1 = sorted((z0, z1))
        self.fill(x0, y0, z0, x1, y1, z0, block, meta)
        self.fill(x0, y0, z1, x1, y1, z1, block, meta)
        self.fill(x0, y0, z0, x0, y1, z1, block, meta)
        self.fill(x1, y0, z0, x1, y1, z1, block, meta)

    def top_y(self, x: int, z: int) -> int:
        """Highest non-air y of a column (-1 if empty)."""
        col = self.chunk(x >> 4, z >> 4).blocks[:, z & 15, x & 15]
        nz = np.nonzero(col)[0]
        return int(nz[-1]) if len(nz) else -1

    # ------------------------------------------------------------------ block entities
    def set_sign_text(self, x, y, z, lines):
        lines = list(lines)[:4] + [""] * (4 - min(4, len(lines)))
        c = self.chunk(x >> 4, z >> 4)
        c.entities[(y << 8) | ((z & 15) << 4) | (x & 15)] = ("sign", {f"line{i}": str(lines[i])[:15] for i in range(4)})
        c.dirty = True

    def set_wall_sign(self, x, y, z, wall_side: str, lines):
        """A sign hanging on the wall on 'wall_side' of (x,y,z); the text faces the opposite direction. Max 15 chars/line."""
        self.set(x, y, z, "wall_sign", META.WALL_SIGN[wall_side])
        self.set_sign_text(x, y, z, lines)

    def set_standing_sign(self, x, y, z, rotation16: int, lines):
        """A sign on a post. rotation16: 0 = text faces south, 4 = west, 8 = north, 12 = east (16 steps)."""
        self.set(x, y, z, "standing_sign", rotation16 & 15)
        self.set_sign_text(x, y, z, lines)

    def set_door(self, x, y, z, door: str = "oak_door", side: str = "north", hinge_right: bool = False, open_: bool = False):
        """Two-block door; 'side' = the side of the block the closed panel is on (put it on the outer wall line)."""
        self.set(x, y, z, door, META.DOOR_SIDE[side] | (META.DOOR_OPEN if open_ else 0))
        self.set(x, y + 1, z, door, META.DOOR_UPPER | (1 if hinge_right else 0))

    # ------------------------------------------------------------------ player
    def set_player(self, x: float, y: float, z: float, yaw: float = 0.0, pitch: float = 0.0, spawn: bool = True):
        """Moves the saved player (and the world spawn) so the world opens there. yaw 0 = looking south."""
        p = self.dir / "level.properties"
        lines = p.read_text(encoding="latin-1").splitlines()
        vals = {"px": repr(float(x)), "py": repr(float(y)), "pz": repr(float(z)), "yaw": repr(float(yaw)), "pitch": repr(float(pitch))}
        if spawn:
            vals.update({"spawnX": str(int(x)), "spawnY": str(int(y)), "spawnZ": str(int(z))})
        seen = set()
        for i, line in enumerate(lines):
            k = line.split("=", 1)[0].strip()
            if k in vals:
                lines[i] = f"{k}={vals[k]}"
                seen.add(k)
        lines += [f"{k}={v}" for k, v in vals.items() if k not in seen]
        if self.backup:
            self._ensure_backup_dir()
            if not (self.backup_dir / "level.properties").exists():
                shutil.copy2(p, self.backup_dir / "level.properties")
        p.write_text("\n".join(lines) + "\n", encoding="latin-1")
        self.info.update(vals)

    # ------------------------------------------------------------------ save
    def _ensure_backup_dir(self):
        if self.backup_dir is None:
            stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            self.backup_dir = self.dir / "region_backup" / stamp
            self.backup_dir.mkdir(parents=True, exist_ok=True)

    def save(self) -> int:
        """Writes all edited chunks. Returns the number of chunks written."""
        (self.dir / "region").mkdir(exist_ok=True)
        by_region: dict[tuple[int, int], dict[int, bytes]] = {}
        n = 0
        for (cx, cz), c in self._chunks.items():
            if not c.dirty:
                continue
            by_region.setdefault((cx >> 5, cz >> 5), {})[((cz & 31) << 5) | (cx & 31)] = c.encode()
            c.dirty = False
            n += 1
        for (rx, rz), rep in by_region.items():
            reg = self._region(rx, rz)
            if self.backup and reg.path.exists():
                self._ensure_backup_dir()
                dst = self.backup_dir / reg.path.name
                if not dst.exists():
                    shutil.copy2(reg.path, dst)
            reg.write(rep)
        return n

    def restore_backup(self, backup_dir=None):
        """Copies the region files (and level.properties) of a backup back. Default: the latest backup."""
        root = self.dir / "region_backup"
        if backup_dir is None:
            dirs = sorted(d for d in root.iterdir() if d.is_dir()) if root.exists() else []
            if not dirs:
                raise FileNotFoundError("no backups")
            backup_dir = dirs[-1]
        backup_dir = Path(backup_dir)
        for f in backup_dir.iterdir():
            if f.name == "level.properties":
                shutil.copy2(f, self.dir / f.name)
            elif f.suffix == ".dcr":
                shutil.copy2(f, self.dir / "region" / f.name)
        self._regions.clear(); self._chunks.clear()
        return backup_dir

    # ------------------------------------------------------------------ preview
    def render_map(self, png_path, x0, z0, x1, z1, scale: int = 2):
        """Top-down picture of the area (needs Pillow): block colours, shaded by height. Lets you check a build without the game."""
        from PIL import Image
        x0, x1 = sorted((x0, x1)); z0, z1 = sorted((z0, z1))
        w, h = x1 - x0 + 1, z1 - z0 + 1
        top = np.zeros((h, w), dtype=np.int32)
        st = np.zeros((h, w), dtype=np.int64)
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                if not self.chunk_exists(cx, cz):
                    continue
                b = self.chunk(cx, cz).blocks
                solid = b != 0
                ys = HEIGHT - 1 - np.argmax(solid[::-1], axis=0)        # [z, x]
                ys[~solid.any(axis=0)] = -1
                for lz in range(16):
                    z = cz * 16 + lz
                    if not z0 <= z <= z1:
                        continue
                    for lx in range(16):
                        x = cx * 16 + lx
                        if not x0 <= x <= x1:
                            continue
                        y = ys[lz, lx]
                        top[z - z0, x - x0] = y
                        st[z - z0, x - x0] = b[y, lz, lx] if y >= 0 else 0
        cache = {}
        img = np.zeros((h, w, 3), dtype=np.float32)
        for s in np.unique(st):
            cache[s] = block_color(self.name_of(int(s))[0]) if s else (0, 0, 0)
            img[st == s] = cache[s]
        # height shading: brighter when higher than the neighbour to the north-west, plus overall height
        shade = np.ones((h, w), dtype=np.float32)
        d = np.zeros((h, w), dtype=np.float32)
        d[1:, 1:] = top[1:, 1:] - top[:-1, :-1]
        shade += np.clip(d, -4, 4) * 0.06
        valid = top >= 0
        if valid.any():
            lo, hi = top[valid].min(), max(top[valid].max(), top[valid].min() + 1)
            shade *= 0.75 + 0.35 * (top - lo) / (hi - lo)
        img = np.clip(img * shade[..., None], 0, 255).astype(np.uint8)
        im = Image.fromarray(img, "RGB")
        if scale != 1:
            im = im.resize((w * scale, h * scale), Image.NEAREST)
        im.save(png_path)
        return png_path


def _render_side(self, png_path, x0, z0, x1, z1, y0=0, y1=HEIGHT - 1, facing="north", scale=2):
    """Side view (orthographic): what you see standing south of the area looking north (facing='north'), or from
    the west looking east (facing='east'). Nearer blocks are brighter. Good for checking heights and roofs."""
    from PIL import Image
    x0, x1 = sorted((x0, x1)); z0, z1 = sorted((z0, z1)); y0, y1 = sorted((y0, y1))
    along = range(x0, x1 + 1) if facing == "north" else range(z0, z1 + 1)
    depth = list(range(z1, z0 - 1, -1)) if facing == "north" else list(range(x0, x1 + 1))
    W, H = len(along), y1 - y0 + 1
    img = np.zeros((H, W, 3), dtype=np.float32)
    img[:] = (135, 180, 230)
    found = np.zeros((H, W), dtype=bool)
    for di, d in enumerate(depth):
        if found.all():
            break
        for ai, a in enumerate(along):
            x, z = (a, d) if facing == "north" else (d, a)
            if not self.chunk_exists(x >> 4, z >> 4):
                continue
            col = self.chunk(x >> 4, z >> 4).blocks[y0:y1 + 1, z & 15, x & 15][::-1]
            m = (col != 0) & ~found[:, ai]
            if not m.any():
                continue
            k = 1.0 - 0.5 * di / max(1, len(depth) - 1)
            for yi in np.nonzero(m)[0]:
                img[yi, ai] = np.array(block_color(self.name_of(int(col[yi]))[0]), dtype=np.float32) * k
            found[:, ai] |= m
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")
    if scale != 1:
        im = im.resize((W * scale, H * scale), Image.NEAREST)
    im.save(png_path)
    return png_path


World.render_side = _render_side


# ---------------------------------------------------------------------------------------------- map colours
_COLORS16 = {"white": (233, 236, 236), "orange": (240, 118, 19), "magenta": (189, 68, 179), "light_blue": (58, 175, 217),
             "yellow": (248, 197, 39), "lime": (112, 185, 25), "pink": (237, 141, 172), "gray": (62, 68, 71),
             "light_gray": (142, 142, 134), "cyan": (21, 137, 145), "purple": (121, 42, 172), "blue": (53, 57, 157),
             "brown": (114, 71, 40), "green": (84, 109, 27), "red": (161, 39, 34), "black": (20, 21, 25)}
_KEYWORDS = [("water", (52, 95, 218)), ("lava", (230, 100, 20)), ("leaves", (58, 118, 38)), ("grass", (95, 159, 53)),
             ("fern", (80, 140, 50)), ("sapling", (80, 140, 50)), ("glass", (175, 213, 230)), ("iron_bars", (160, 160, 160)),
             ("sea_lantern", (215, 235, 230)), ("glowstone", (250, 220, 130)), ("lamp", (240, 200, 120)), ("torch", (250, 200, 80)),
             ("quartz", (236, 230, 223)), ("diorite", (205, 205, 205)), ("andesite", (136, 136, 137)), ("granite", (153, 104, 84)),
             ("stone_brick", (122, 121, 122)), ("cobblestone", (110, 110, 110)), ("brick", (151, 82, 64)), ("nether", (60, 30, 35)),
             ("sandstone", (216, 203, 155)), ("sand", (219, 207, 163)), ("gravel", (131, 127, 126)), ("dirt", (134, 96, 67)),
             ("podzol", (90, 63, 28)), ("snow", (245, 250, 250)), ("ice", (160, 190, 250)), ("clay", (160, 166, 179)),
             ("dark_oak", (66, 43, 20)), ("spruce", (114, 84, 48)), ("birch", (192, 175, 121)), ("jungle", (160, 115, 80)),
             ("acacia", (168, 90, 50)), ("oak", (162, 130, 78)), ("log", (110, 85, 50)), ("bookshelf", (120, 90, 60)),
             ("iron", (220, 220, 220)), ("gold", (246, 208, 61)), ("diamond", (98, 237, 228)), ("emerald", (42, 203, 87)),
             ("lapis", (31, 67, 140)), ("coal", (25, 25, 25)), ("redstone", (175, 24, 5)), ("obsidian", (20, 18, 30)),
             ("prismarine", (99, 156, 151)), ("purpur", (169, 125, 169)), ("end_stone", (220, 222, 158)), ("hay", (166, 136, 38)),
             ("bedrock", (85, 85, 85)), ("stone", (125, 125, 125))]


def block_color(name: str):
    for c, rgb in sorted(_COLORS16.items(), key=lambda kv: -len(kv[0])):
        if name.startswith(c + "_") and not name.endswith("_sapling"):
            base = rgb
            if "terracotta" in name:
                base = tuple(int(v * 0.7 + 50) for v in rgb)
            return base
    if name == "terracotta":
        return (152, 94, 67)
    for k, rgb in _KEYWORDS:
        if k in name:
            return rgb
    return (150, 150, 150)


# ---------------------------------------------------------------------------------------------- cli
def _main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description="Inspect a DerpCraft world: info, block names, top-down map, side view.")
    ap.add_argument("world", help="world folder (game/saves/<name>)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("info", help="generator, player position, regions")
    b = sub.add_parser("blocks", help="all block names this world knows (blockids.txt)")
    b.add_argument("filter", nargs="?", default="", help="only names containing this text")
    m = sub.add_parser("map", help="top-down PNG of an area")
    m.add_argument("x0", type=int); m.add_argument("z0", type=int); m.add_argument("x1", type=int); m.add_argument("z1", type=int)
    m.add_argument("out"); m.add_argument("--scale", type=int, default=2)
    s = sub.add_parser("side", help="side view PNG of an area (from the south looking north, or --facing east)")
    s.add_argument("x0", type=int); s.add_argument("z0", type=int); s.add_argument("x1", type=int); s.add_argument("z1", type=int)
    s.add_argument("out"); s.add_argument("--y0", type=int, default=0); s.add_argument("--y1", type=int, default=255)
    s.add_argument("--facing", choices=["north", "east"], default="north"); s.add_argument("--scale", type=int, default=2)
    g = sub.add_parser("get", help="block at x y z")
    g.add_argument("x", type=int); g.add_argument("y", type=int); g.add_argument("z", type=int)
    a = ap.parse_args(argv)
    w = World(a.world, backup=False)
    if a.cmd == "info":
        regs = sorted(p.name for p in (w.dir / "region").glob("r.*.dcr")) if (w.dir / "region").exists() else []
        print(f"name={w.info.get('name')} generator={w.info.get('generator', 'default')} flatLayers={w.info.get('flatLayers', '')}")
        print(f"player={w.info.get('px')},{w.info.get('py')},{w.info.get('pz')}  flat ground={w.flat_ground()}  blocks known={len(w.ids)}")
        print(f"regions ({len(regs)}): {' '.join(regs[:20])}{' ...' if len(regs) > 20 else ''}")
    elif a.cmd == "blocks":
        print("\n".join(n for n in sorted(w.ids) if a.filter in n))
    elif a.cmd == "map":
        print(w.render_map(a.out, a.x0, a.z0, a.x1, a.z1, a.scale))
    elif a.cmd == "side":
        print(w.render_side(a.out, a.x0, a.z0, a.x1, a.z1, a.y0, a.y1, a.facing, a.scale))
    elif a.cmd == "get":
        print(w.get_name(a.x, a.y, a.z))


if __name__ == "__main__":
    _main()
