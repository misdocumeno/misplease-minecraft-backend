#!/usr/bin/env python3
"""Trim a Sponge v3 .schem down to its non-air bounds.

LimboAPI's VirtualWorld only has 16 chunk sections (y 0..255) and its schematic
reader calls `setBlock` for *every* cell of the file, air included. A WorldEdit
save of a selection that was expanded upwards therefore throws
`ArrayIndexOutOfBoundsException: Index 16 out of bounds for length 16` while the
proxy boots. Cropping the file to the blocks that actually exist fixes that and
shrinks the decode at startup.

Usage:
    python trim.py in.schem out.schem

It prints the offset for `toWorld(factory, world, x, y, z)` that keeps the build
in its original world position, so the plugin only needs the three constants
plus the spawn point. The file is idempotent: trimming a trimmed file is a no-op.
"""

import gzip
import struct
import sys

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE = 0, 1, 2, 3, 4, 5, 6
TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = 7, 8, 9, 10, 11, 12


class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def i4(self):
        return struct.unpack(">i", self.take(4))[0]

    def i8(self):
        return struct.unpack(">q", self.take(8))[0]

    def f4(self):
        return struct.unpack(">f", self.take(4))[0]

    def f8(self):
        return struct.unpack(">d", self.take(8))[0]

    def string(self):
        return self.take(self.u2()).decode("utf-8")

    def payload(self, tag):
        if tag == TAG_BYTE:
            return self.u1()
        if tag == TAG_SHORT:
            return self.u2()
        if tag == TAG_INT:
            return self.i4()
        if tag == TAG_LONG:
            return self.i8()
        if tag == TAG_FLOAT:
            return self.f4()
        if tag == TAG_DOUBLE:
            return self.f8()
        if tag == TAG_BYTE_ARRAY:
            return self.take(self.i4())
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            elem = self.u1()
            return (elem, [self.payload(elem) for _ in range(self.i4())])
        if tag == TAG_COMPOUND:
            out = []
            while True:
                t = self.u1()
                if t == TAG_END:
                    return out
                out.append((self.string(), t, self.payload(t)))
        if tag == TAG_INT_ARRAY:
            n = self.i4()
            return list(struct.unpack(">%di" % n, self.take(4 * n)))
        if tag == TAG_LONG_ARRAY:
            n = self.i4()
            return list(struct.unpack(">%dq" % n, self.take(8 * n)))
        raise ValueError("unknown tag %d at %d" % (tag, self.i))


class Writer:
    def __init__(self):
        self.out = bytearray()

    def payload(self, tag, v):
        if tag == TAG_BYTE:
            self.out += struct.pack(">b", v)
        elif tag == TAG_SHORT:
            self.out += struct.pack(">h", v)
        elif tag == TAG_INT:
            self.out += struct.pack(">i", v)
        elif tag == TAG_LONG:
            self.out += struct.pack(">q", v)
        elif tag == TAG_FLOAT:
            self.out += struct.pack(">f", v)
        elif tag == TAG_DOUBLE:
            self.out += struct.pack(">d", v)
        elif tag == TAG_BYTE_ARRAY:
            self.out += struct.pack(">i", len(v)) + bytes(v)
        elif tag == TAG_STRING:
            raw = v.encode("utf-8")
            self.out += struct.pack(">H", len(raw)) + raw
        elif tag == TAG_LIST:
            elem, items = v
            self.out += struct.pack(">bi", elem, len(items))
            for item in items:
                self.payload(elem, item)
        elif tag == TAG_COMPOUND:
            for name, t, value in v:
                self.out += struct.pack(">b", t)
                self.payload(TAG_STRING, name)
                self.payload(t, value)
            self.out += b"\x00"
        elif tag == TAG_INT_ARRAY:
            self.out += struct.pack(">i", len(v)) + struct.pack(">%di" % len(v), *v)
        elif tag == TAG_LONG_ARRAY:
            self.out += struct.pack(">i", len(v)) + struct.pack(">%dq" % len(v), *v)
        else:
            raise ValueError("unknown tag %d" % tag)
        return self.out


def load(path):
    with gzip.open(path, "rb") as fh:
        r = Reader(fh.read())
    assert r.u1() == TAG_COMPOUND
    name = r.string()
    return name, r.payload(TAG_COMPOUND)


def save(path, name, root):
    w = Writer()
    w.out += struct.pack(">b", TAG_COMPOUND)
    w.payload(TAG_STRING, name)
    w.payload(TAG_COMPOUND, root)
    with gzip.open(path, "wb", compresslevel=9) as fh:
        fh.write(bytes(w.out))


def get(compound, key):
    for name, t, value in compound:
        if name == key:
            return t, value
    raise KeyError(key)


def set_value(compound, key, value):
    for i, (name, t, _) in enumerate(compound):
        if name == key:
            compound[i] = (name, t, value)
            return
    raise KeyError(key)


def read_varints(buf):
    out, val, shift = [], 0, 0
    for b in buf:
        val |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
        else:
            out.append(val)
            val, shift = 0, 0
    return out


def write_varints(values):
    out = bytearray()
    for v in values:
        while True:
            if v < 0x80:
                out.append(v)
                break
            out.append((v & 0x7F) | 0x80)
            v >>= 7
    return bytes(out)


def main(src, dst):
    name, root = load(src)
    schematic = get(root, "Schematic")[1]
    width, height, length = (get(schematic, k)[1] for k in ("Width", "Height", "Length"))
    blocks = get(schematic, "Blocks")[1]
    palette = get(blocks, "Palette")[1]
    data = read_varints(get(blocks, "Data")[1])

    # Not touched by the trim, needed to report the paste offset afterwards.
    old_offset = get(schematic, "Offset")[1]
    world_edit = get(get(schematic, "Metadata")[1], "WorldEdit")[1]
    old_origin = get(world_edit, "Origin")[1]

    air = {i for i, (key, _, _) in enumerate(palette) if key == "minecraft:air"}
    assert len(data) == width * height * length, (len(data), width * height * length)

    xs, ys, zs = [], [], []
    for i, state in enumerate(data):
        if state in air:
            continue
        y, rem = divmod(i, length * width)
        z, x = divmod(rem, width)
        xs.append(x)
        ys.append(y)
        zs.append(z)
    x0, x1, y0, y1, z0, z1 = min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)
    nw, nh, nl = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    print("volume %dx%dx%d -> %dx%dx%d at (%d,%d,%d)" % (width, height, length, nw, nh, nl, x0, y0, z0))
    if nh > 256:
        sys.exit("still %d blocks tall; LimboAPI only has y 0..255" % nh)

    out = []
    for y in range(y0, y1 + 1):
        for z in range(z0, z1 + 1):
            base = (y * length + z) * width + x0
            out.extend(data[base:base + nw])

    set_value(schematic, "Width", nw)
    set_value(schematic, "Height", nh)
    set_value(schematic, "Length", nl)
    # `Offset` is minus the paste origin measured from the schematic corner, so
    # WorldEdit re-pastes the build in the same place. Metadata.WorldEdit.Origin
    # is absolute and stays put; LimboAPI ignores both.
    local_origin = [-v for v in old_offset]
    set_value(schematic, "Offset", [-(local_origin[0] - x0), -(local_origin[1] - y0), -(local_origin[2] - z0)])
    set_value(blocks, "Data", write_varints(out))

    # Block entities carry their position relative to the schematic corner.
    _, entries = get(blocks, "BlockEntities")
    entries = entries[1]
    for entry in entries:
        pos = get(entry, "Pos")[1]
        set_value(entry, "Pos", [pos[0] - x0, pos[1] - y0, pos[2] - z0])

    save(dst, name, root)

    # Where to paste the trimmed file so it lands exactly where the original did:
    # LimboAPI's `toWorld(factory, world, x, y, z)` adds these to the local coords.
    min_x, min_y, min_z = (old_origin[i] + old_offset[i] for i in range(3))
    print("wrote %s (%.1f KB)" % (dst, __import__("os").path.getsize(dst) / 1024))
    print("paste with toWorld(factory, world, %d, %d, %d)" % (min_x + x0, min_y + y0, min_z + z0))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
