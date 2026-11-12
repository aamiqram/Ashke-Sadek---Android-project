# -*- coding: utf-8 -*-
"""Assert the bundled Bengali fonts cover every Bengali codepoint the app uses.

Bengali shaping depends on the font's cmap containing every codepoint the
catalogue and the interface can produce; a missing glyph silently renders as
tofu. This gate parses the bundled TTFs directly (no external deps) and runs
over the same assets the APK bundles: the full catalogue text and every
Bengali-range character that appears in the Java sources.
"""
import gzip
import io
import json
import os
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
JAVA = os.path.join(ROOT, "app", "src", "main", "java")

# Bengali block + Bengali digits (inside block) + danda + ZWJ/ZWNJ + Vedic marks
USED_RANGES = [(0x0980, 0x09FF), (0x0964, 0x0965), (0x0951, 0x0954),
               (0x200C, 0x200D)]


def font_codepoints(path):
    data = io.open(path, "rb").read()
    num_tables = struct.unpack(">H", data[4:6])[0]
    cmap_off = None
    for i in range(num_tables):
        rec = 12 + i * 16
        if data[rec:rec + 4] == b"cmap":
            cmap_off = struct.unpack(">I", data[rec + 8:rec + 12])[0]
    assert cmap_off is not None, "no cmap table in %s" % path
    count = struct.unpack(">H", data[cmap_off + 2:cmap_off + 4])[0]
    found = set()
    for i in range(count):
        rec = cmap_off + 4 + i * 8
        _plat, _enc, off = struct.unpack(">HHI", data[rec:rec + 8])
        sub = cmap_off + off
        fmt = struct.unpack(">H", data[sub:sub + 2])[0]
        if fmt == 12:
            groups = struct.unpack(">I", data[sub + 12:sub + 16])[0]
            for g in range(groups):
                base = sub + 16 + g * 16
                start, end = struct.unpack(">II", data[base:base + 8])
                found.update(range(start, min(end, 0x10FFFF) + 1))
        elif fmt == 4:
            seg2 = struct.unpack(">H", data[sub + 6:sub + 8])[0]
            seg = seg2 // 2
            end_base = sub + 14
            start_base = end_base + seg2 + 2
            delta_base = start_base + seg2
            range_base = delta_base + seg2
            ends = struct.unpack(">%dH" % seg, data[end_base:end_base + seg2])
            starts = struct.unpack(">%dH" % seg,
                                   data[start_base:start_base + seg2])
            deltas = struct.unpack(">%dH" % seg,
                                   data[delta_base:delta_base + seg2])
            ranges = struct.unpack(">%dH" % seg,
                                   data[range_base:range_base + seg2])
            for i in range(seg):
                if starts[i] == 0xFFFF:
                    continue
                for cp in range(starts[i], ends[i] + 1):
                    if ranges[i] == 0:
                        glyph = (cp + deltas[i]) & 0xFFFF
                    else:
                        addr = (range_base + i * 2 + ranges[i]
                                + (cp - starts[i]) * 2)
                        glyph = struct.unpack(">H", data[addr:addr + 2])[0]
                        if glyph != 0:
                            glyph = (glyph + deltas[i]) & 0xFFFF
                    if glyph != 0:
                        found.add(cp)
    return found


def used_codepoints():
    used = set()

    def add_text(text):
        for ch in text:
            o = ord(ch)
            for lo, hi in USED_RANGES:
                if lo <= o <= hi:
                    used.add(o)
                    break

    with gzip.open(os.path.join(ASSETS, "songs.json.gz"),
                   "rt", encoding="utf-8") as handle:
        data = json.load(handle)
    add_text(json.dumps(data, ensure_ascii=False))
    for dirpath, _, files in os.walk(JAVA):
        for name in files:
            if name.endswith(".java"):
                add_text(io.open(os.path.join(dirpath, name), "r",
                                 encoding="utf-8").read())
    return used


def main():
    fonts_dir = os.path.join(ASSETS, "fonts")
    files = sorted(os.listdir(fonts_dir))
    ttfs = [f for f in files if f.endswith(".ttf")]
    assert "OFL.txt" in files, "OFL license file missing from assets/fonts"
    expected = ["NotoSansBengali-Medium.ttf", "NotoSansBengali-Regular.ttf",
                "NotoSerifBengali-Regular.ttf", "NotoSerifBengali-SemiBold.ttf"]
    assert ttfs == expected, "unexpected bundled fonts: %r" % ttfs
    used = used_codepoints()
    for name in expected:
        covered = font_codepoints(os.path.join(fonts_dir, name))
        missing = sorted(used - covered)
        assert not missing, ("%s does not cover: %s"
                             % (name, ["U+%04X" % c for c in missing]))
        print("PASS: %s covers all %d used Bengali codepoints"
              % (name, len(used)))


main()
