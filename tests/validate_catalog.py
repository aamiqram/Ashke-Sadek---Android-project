#!/usr/bin/env python3
"""Offline catalogue integrity checks for the packaged Android app."""
from pathlib import Path
import gzip
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/songs.json.gz"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

payload = json.loads(gzip.decompress(CATALOG.read_bytes()).decode("utf-8"))
songs = payload["songs"]
expected = {1: 82, 2: 289, 3: 244}
assert len(songs) == 615, f"Expected 615 songs, got {len(songs)}"
assert len({song["id"] for song in songs}) == 615, "Song IDs are not unique"

for volume, count in expected.items():
    rows = [song for song in songs if song["volume"] == volume]
    assert len(rows) == count, f"Volume {volume}: expected {count}, got {len(rows)}"
    assert [song["number"] for song in rows] == list(range(1, count + 1)), f"Volume {volume}: numbering gap"
    for song in rows:
        assert song["id"] == f"{volume}-{song['number']}"
        assert song["title"].strip(), f"Missing title: {song['id']}"
        assert song["lyrics"].strip(), f"Missing lyrics: {song['id']}"
        assert song["firstLine"] == song["lyrics"].split("\n", 1)[0].strip()
        blocks = song.get("lyricBlocks", [])
        assert blocks, f"Missing Word paragraph formatting: {song['id']}"
        assert "\n".join(block["text"] for block in blocks) == song["lyrics"], f"Block/text mismatch: {song['id']}"
        assert all(block["alignment"] in (0, 1, 2, 3) for block in blocks)

root = ET.parse(MANIFEST).getroot()
android = "{http://schemas.android.com/apk/res/android}"
permissions = {node.attrib.get(android + "name") for node in root.findall("uses-permission")}
assert "android.permission.INTERNET" not in permissions, "Unexpected internet permission"
assert "android.permission.RECORD_AUDIO" in permissions, "Voice permission declaration missing"
application = root.find("application")
assert application is not None
assert application.attrib.get(android + "allowBackup") == "false"

for xml_path in [ROOT / "app/src/main/res/values/styles.xml", ROOT / "app/src/main/res/drawable/ic_book.xml"]:
    ET.parse(xml_path)

print("PASS: 615 songs, all volume sequences, titles, lyrics, Android manifest, and XML resources.")
print(f"Catalogue: {CATALOG.stat().st_size:,} compressed bytes; {sum(len(s['lyricBlocks']) for s in songs):,} Word paragraphs")
print(f"Original Word SHA-256 recorded in catalogue: {payload['sourceSha256']}")
print(f"Index-copy SHA-256 recorded in catalogue: {payload['indexSha256']}")
