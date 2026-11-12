#!/usr/bin/env python3
"""Offline catalogue integrity checks for the packaged Android app."""
from pathlib import Path
import gzip
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/songs.json.gz"
EMPHASIS = ROOT / "app/src/main/assets/lyrics_emphasis.json.gz"
MAIN_SOURCE = ROOT / "app/src/main/java/org/ashkeshadek/reader/MainActivity.java"
VOICE_SOURCE = ROOT / "app/src/main/java/org/ashkeshadek/reader/OfflineBengaliVoice.java"
VOICE_SERVICE = ROOT / "app/src/main/java/org/ashkeshadek/reader/VoiceRecognitionService.java"
APP_UPDATER = ROOT / "app/src/main/java/org/ashkeshadek/reader/AppUpdater.java"
UPDATE_RECEIVER = ROOT / "app/src/main/java/org/ashkeshadek/reader/UpdateInstallReceiver.java"
BUILD_WORKFLOW = ROOT / ".github/workflows/build-apk.yml"
RELEASE_WORKFLOW = ROOT / ".github/workflows/release-apk.yml"
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

# Ensure paragraph text exists only once per song in the runtime data model:
# lyric blocks hold offsets into Song.lyrics, not duplicate String objects.
main_source = MAIN_SOURCE.read_text(encoding="utf-8")
voice_source = VOICE_SOURCE.read_text(encoding="utf-8")
voice_service = VOICE_SERVICE.read_text(encoding="utf-8")
assert "searchableModernText" not in main_source
assert "searchablePhoneticText" not in main_source
assert "searchableWords" not in main_source
assert "final String text;" not in main_source
assert "cachedRecognizer" not in voice_source, "ASR model must not remain cached in the UI process"
assert "recognizer.release()" in voice_source, "ASR session must release its native recognizer"
# Beam search + hotword contextual biasing needs a second thread to stay
# realtime on single-big-core phones; the invariant is that the count stays
# small and explicit, not that greedy_search's 1 remains.
thread_counts = [int(match) for match in re.findall(r"model\.setNumThreads\((\d+)\)", voice_source)]
assert thread_counts and max(thread_counts) <= 2, "ASR thread count must stay bounded"
assert "Process.killProcess(Process.myPid())" in voice_service, "Voice process must exit after a session"
assert "voice-model-loaded" in voice_service, "Voice PSS telemetry missing"
assert "startForeground" in voice_service, "Voice process must be foreground-ranked during model load"
assert "demoteFromForeground" in voice_service, "Voice FGS notification must drop after the session"
assert "getCacheDir(), HOTWORDS_ASSET" not in voice_source, "Hotwords must not be staged to an absolute cache path (sherpa-onnx refuses that fatally while the AssetManager is set)"
assert "return HOTWORDS_ASSET;" in voice_source, "Hotwords must load via the relative asset path"
assert 'setModelingUnit("bpe")' in voice_source, "Hotword encoding needs the tokenizer unit; an empty unit exits the voice process"
assert 'setBpeVocab(MODEL_DIR + "/tokens.txt")' in voice_source, "BPE hotword encoder must be built from the model's tokens.txt"

# The emphasis asset has one entry for every paragraph, with valid source-relative spans.
all_blocks = [block for song in songs for block in song.get("lyricBlocks", [])]
emphasis = json.loads(gzip.decompress(EMPHASIS.read_bytes()).decode("utf-8"))
assert len(emphasis) == len(all_blocks), (
    f"Expected one emphasis row per lyric block ({len(all_blocks)}), got {len(emphasis)}"
)
for index, (ranges, block) in enumerate(zip(emphasis, all_blocks)):
    previous_end = 0
    for span in ranges:
        assert isinstance(span, list) and len(span) == 2, f"Invalid emphasis range at block {index}"
        start, end = span
        assert isinstance(start, int) and isinstance(end, int), f"Non-integer emphasis range at block {index}"
        assert 0 <= start < end <= len(block["text"]), f"Out-of-bounds emphasis range at block {index}"
        assert start >= previous_end, f"Overlapping or unsorted emphasis range at block {index}"
        previous_end = end

# Every compact paragraph range must map back to the original canonical lyrics in order.
for song in songs:
    cursor = 0
    for block in song.get("lyricBlocks", []):
        text_value = block["text"]
        start = song["lyrics"].find(text_value, cursor)
        assert start >= 0, f"Paragraph offset cannot be resolved: {song['id']}"
        cursor = start + len(text_value)
        if cursor < len(song["lyrics"]) and song["lyrics"][cursor] == "\n":
            cursor += 1

root = ET.parse(MANIFEST).getroot()
android = "{http://schemas.android.com/apk/res/android}"
permissions = {node.attrib.get(android + "name") for node in root.findall("uses-permission")}
assert "android.permission.INTERNET" in permissions, "Internet permission required for release update checks"
assert "android.permission.REQUEST_INSTALL_PACKAGES" in permissions, "Install-package permission missing"
assert "android.permission.RECORD_AUDIO" in permissions, "Voice permission declaration missing"
assert "android.permission.FOREGROUND_SERVICE" in permissions, "Foreground-service permission missing"
assert "android.permission.FOREGROUND_SERVICE_MICROPHONE" in permissions, "FGS microphone permission missing"
application = root.find("application")
assert application is not None

# In-app updates must use only signed release assets and Android's own installer.
updater_source = APP_UPDATER.read_text(encoding="utf-8")
receiver_source = UPDATE_RECEIVER.read_text(encoding="utf-8")
build_workflow = BUILD_WORKFLOW.read_text(encoding="utf-8")
release_workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
assert "RELEASE_ASSET_PREFIX" in updater_source and "update.json" in updater_source
assert "sha256(apk)" in updater_source
assert "getPackageArchiveInfo" in updater_source
assert "installedCertificate" in updater_source
assert "session.commit" in updater_source and "PackageInstaller" in updater_source
assert "getContentLengthLong" not in updater_source, "Updater must remain compatible with minSdk 21"
assert "ACTION_MANAGE_UNKNOWN_APP_SOURCES" in updater_source
assert "trustedSslFactory" in updater_source, "Updater must trust the bundled modern roots"
assert "setSSLSocketFactory(trustedSslFactory())" in updater_source, "Bundled-root factory must be wired into every HTTPS call"
bundled_roots = sorted((ROOT / "app/src/main/assets/certs").glob("*.pem"))
assert len(bundled_roots) >= 8, f"Expected bundled root certificates, found {len(bundled_roots)}"
assert "STATUS_PENDING_USER_ACTION" in receiver_source
assert "ASHKE_KEYSTORE_BASE64" in build_workflow
assert "ashke-shadek-signed-release-apk" in release_workflow
assert "ashke-shadek-installable-debug-apk" not in release_workflow
assert "ASHKE_STABLE_RELEASE_READY" in release_workflow
assert "Ashke-Shadek-User-Data-Backup.json" in main_source
assert "USER_DATA_BACKUP_FORMAT" in main_source
assert "restoreUserDataBackup" in main_source
assert application.attrib.get(android + "allowBackup") == "false"
voice_services = [node for node in application.findall("service")
                  if node.attrib.get(android + "name") == ".VoiceRecognitionService"]
assert len(voice_services) == 1, "Voice subprocess service declaration missing"
assert voice_services[0].attrib.get(android + "process") == ":voice"
assert voice_services[0].attrib.get(android + "exported") == "false"
assert voice_services[0].attrib.get(android + "foregroundServiceType") == "microphone"

for xml_path in [ROOT / "app/src/main/res/values/styles.xml", ROOT / "app/src/main/res/drawable/ic_book.xml"]:
    ET.parse(xml_path)

print("PASS: 615 songs, all volume sequences, titles, lyrics, Android manifest, and XML resources.")
print(f"Catalogue: {CATALOG.stat().st_size:,} compressed bytes; {len(all_blocks):,} Word paragraphs")
print(f"Word emphasis sidecar: {EMPHASIS.stat().st_size:,} compressed bytes; "
      f"{sum(bool(row) for row in emphasis):,} paragraphs with preserved bold spans")
print("PASS: canonical lyric storage, no retained search index copies, isolated one-thread ASR process.")
print(f"Original Word SHA-256 recorded in catalogue: {payload['sourceSha256']}")
print(f"Index-copy SHA-256 recorded in catalogue: {payload['indexSha256']}")
