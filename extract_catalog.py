#!/usr/bin/env python3
"""Build the bundled offline song catalogue from the original and corrected Word copies.

The original .docx is read only for song text. The corrected copy is read only for index
names and ordering. Neither input file is modified.
"""
from argparse import ArgumentParser
from pathlib import Path
import gzip
import hashlib
import json
import re
import unicodedata
from difflib import SequenceMatcher

from docx import Document

ROOT = Path(__file__).resolve().parent

# Duplicate removal (strictly verified): drop the later copy, keep the first
# occurrence in book order, preserving printed song numbers with gaps so the
# app stays cross-referenceable with the print edition. Ground truth: the
# book's own duplicate list (28 rows) plus the strict deep scan that found
# further essentially-identical twins the list missed. Every removed song is
# re-verified as a near-identical twin of its kept partner on every build
# (same line count, folded per-line similarity); a later edit to either Word
# document that breaks a twin fails the build instead of silently shipping a
# broken removal.
# Deliberately kept: 3-121 (the book's report held it back for human review)
# and 3-47, 3-96, 3-122, 3-138, 3-153, 3-224, 2-103 (below the strict bar).
EXPECTED_TOTAL = 585
EXPECTED_KEPT = {1: 82, 2: 285, 3: 218}
BOOK_TOTALS = {1: 82, 2: 289, 3: 244}
DUPLICATE_REMOVALS = {
    2: {180: (2, 5), 181: (2, 6), 191: (2, 7), 196: (2, 8)},
    3: {8: (2, 43), 43: (2, 190), 44: (2, 198), 45: (2, 200), 46: (2, 201),
        48: (2, 204), 50: (2, 212), 51: (2, 220), 52: (2, 224), 53: (2, 225),
        59: (2, 102), 61: (2, 114), 89: (2, 44), 106: (2, 54), 107: (3, 15),
        116: (3, 16), 141: (3, 31), 146: (3, 30), 148: (3, 32), 167: (3, 37),
        180: (3, 38), 181: (2, 82), 183: (3, 39), 184: (3, 40), 185: (2, 83),
        222: (3, 41)},
}

# Copyist spelling variants seen across the book; used only to verify that a
# removed song is still the same song as its kept twin.
_VARIANT_FOLD = {
    "ী": "ি", "ূ": "ু", "ৈ": "ই", "ৌ": "ো",
    "ণ": "ন", "ঞ": "ন", "শ": "স", "ষ": "স",
    "ড়": "ড", "ঢ়": "ঢ", "য়": "য", "ৰ": "র", "ৱ": "ব",
    "।": "", "॥": "", "،": "", "?": "", "!": "", ",": "", "-": "", " ": "",
}


def _fold(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    for source, target in _VARIANT_FOLD.items():
        text = text.replace(source, target)
    return re.sub(r"[^\u0980-\u09FF0-9]", "", text)


def verify_removed_twins(removed_lyrics: dict, songs_by_id: dict) -> None:
    """Every removed song must remain a near-identical twin of its kept
    partner: same line count, per-line folded similarity min >= 0.85 and
    average >= 0.95 (the strict bar the removal decision was made against)."""
    for volume, drops in DUPLICATE_REMOVALS.items():
        for number, (keep_volume, keep_number) in sorted(drops.items()):
            dropped_text = removed_lyrics.get((volume, number))
            kept = songs_by_id.get(f"{keep_volume}-{keep_number}")
            if not dropped_text or kept is None:
                raise RuntimeError(
                    f"Twin missing from extraction: {volume}-{number} or "
                    f"{keep_volume}-{keep_number}")
            dropped_lines = dropped_text.splitlines()
            kept_lines = kept["lyrics"].splitlines()
            if len(dropped_lines) != len(kept_lines):
                raise RuntimeError(
                    f"Twin line count changed: {volume}-{number} "
                    f"({len(dropped_lines)}) vs {keep_volume}-{keep_number} "
                    f"({len(kept_lines)})")
            ratios = [SequenceMatcher(None, _fold(a), _fold(b), autojunk=False).ratio()
                      for a, b in zip(dropped_lines, kept_lines)]
            if min(ratios) < 0.85 or sum(ratios) / len(ratios) < 0.95:
                raise RuntimeError(
                    f"Twin no longer near-identical: {volume}-{number} vs "
                    f"{keep_volume}-{keep_number} (min={min(ratios):.3f} "
                    f"avg={sum(ratios) / len(ratios):.3f})")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bn_num(value: str) -> int:
    return int(value.translate(str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")))


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--original", required=True, type=Path,
                        help="Original Word document containing the song text")
    parser.add_argument("--index", required=True, type=Path,
                        help="Corrected Word copy used only for index titles/order")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "app/src/main/assets/songs.json.gz")
    parser.add_argument("--emphasis-output", type=Path,
                        default=ROOT / "app/src/main/assets/lyrics_emphasis.json.gz",
                        help="Compact run-level bold ranges, in flattened lyric-block order")
    args = parser.parse_args()

    original_path = args.original.resolve()
    index_path = args.index.resolve()
    output_path = args.output.resolve()
    emphasis_output_path = args.emphasis_output.resolve()
    original_hash_before = sha256(original_path)
    index_hash_before = sha256(index_path)

    original = Document(str(original_path))
    index_doc = Document(str(index_path))
    paragraphs = original.paragraphs
    index_paragraphs = index_doc.paragraphs

    # Index paragraph ranges from the audited three-volume Word document.
    volume_index_ranges = [(1, 15, 98), (2, 98, 388), (3, 388, 643)]
    # Song-body ranges are read exclusively from the original document.
    volume_body_ranges = [(1, 643, 2378), (2, 2378, 5493), (3, 5493, len(paragraphs))]
    book_counts = BOOK_TOTALS
    titles = {}

    for volume, start, end in volume_index_ranges:
        for paragraph in index_paragraphs[start:end]:
            content = " ".join(paragraph.text.split()).strip()
            match = re.match(r"^([০-৯0-9]+)[।.)]\s*(.*)$", content)
            if not match:
                continue
            number = bn_num(match.group(1))
            title = re.sub(r"\s+", " ", match.group(2).strip())
            if title:
                titles[(volume, number)] = title

    songs = []
    emphasis_ranges = []
    removed_lyrics = {}
    for volume, start, end in volume_body_ranges:
        markers = []
        for paragraph_index in range(start, end):
            paragraph = paragraphs[paragraph_index]
            content = " ".join(paragraph.text.split()).strip()
            if not content or paragraph.alignment != 1 or not re.search(r"[০-৯0-9]", content):
                continue
            numbers = re.findall(r"[০-৯0-9]+", content.translate(str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")))
            if not numbers:
                continue
            number = int(numbers[-1])
            if 1 <= number <= book_counts[volume] and len(content) < 100:
                markers.append((paragraph_index, number, content))

        # Validate the FULL printed sequence (excluded duplicates included)
        # before anything is skipped, so a miscount cannot hide behind a
        # removal.
        actual_numbers = [number for _, number, _ in markers]
        expected_numbers = list(range(1, book_counts[volume] + 1))
        if actual_numbers != expected_numbers:
            raise RuntimeError(
                f"Volume {volume} marker sequence mismatch: {len(actual_numbers)} records; "
                f"expected {book_counts[volume]}. First observed numbers: {actual_numbers[:20]}"
            )

        for position, (paragraph_index, number, heading) in enumerate(markers):
            # Stop at the next marker in the FULL sequence so the kept song
            # still ends exactly where the print edition ends it; a removed
            # twin's centered heading inside the kept span is skipped by the
            # centered-paragraph rule below.
            stop = markers[position + 1][0] if position + 1 < len(markers) else end
            # Removed twins are still fully parsed (their text feeds the twin
            # verification rail) but never emitted to the payload.
            is_removed = number in DUPLICATE_REMOVALS.get(volume, {})
            lyric_blocks = []
            category = ""
            for line_index in range(paragraph_index + 1, stop):
                paragraph = paragraphs[line_index]
                line = paragraph.text.replace("\r", "").strip()
                if not line or paragraph.alignment == 1 or line in {"সমাপ্ত", "সমাপ্ত।"}:
                    continue

                # A short, right-aligned descriptor before the first lyric is a song/rhythm label
                # (e.g. -হালকা, বিচ্ছেদ, -তাল দাদরা), not a lyric line. Keep it as visible metadata.
                if not lyric_blocks and not category and paragraph.alignment == 2 and len(line) <= 50:
                    category = line
                    continue

                # Preserve the original Word emphasis exactly as character ranges.
                # This compact sidecar avoids duplicating the complete song catalogue.
                bold_spans = []
                run_offset = 0
                run_text = []
                for run in paragraph.runs:
                    if not run.text:
                        continue
                    run_text.append(run.text)
                    run_end = run_offset + len(run.text)
                    if run.bold is True:
                        bold_spans.append([run_offset, run_end])
                    run_offset = run_end
                if "".join(run_text) != line:
                    # Do not apply offsets if non-run XML content changed the text stream.
                    bold_spans = []
                if not is_removed:
                    emphasis_ranges.append(bold_spans)

                paragraph_format = paragraph.paragraph_format
                def points(value):
                    return 0.0 if value is None else round(float(value.pt), 2)
                lyric_blocks.append({
                    "text": line,
                    "alignment": 0 if paragraph.alignment is None else int(paragraph.alignment),
                    "leftIndentPt": points(paragraph_format.left_indent),
                    "firstLineIndentPt": points(paragraph_format.first_line_indent),
                    "rightIndentPt": points(paragraph_format.right_indent),
                    "spaceBeforePt": points(paragraph_format.space_before),
                    "spaceAfterPt": points(paragraph_format.space_after),
                })
            lyrics = "\n".join(block["text"] for block in lyric_blocks).strip()
            if is_removed:
                removed_lyrics[(volume, number)] = lyrics
                continue
            title = titles.get((volume, number))
            if not title:
                raise RuntimeError(f"Missing corrected index title for volume {volume}, song {number}")
            if not lyrics:
                raise RuntimeError(f"Empty song text for volume {volume}, song {number}")
            songs.append({
                "id": f"{volume}-{number}",
                "volume": volume,
                "number": number,
                "title": title,
                "heading": heading,
                "category": category,
                "lyrics": lyrics,
                "firstLine": lyrics.split("\n", 1)[0].strip(),
                "lyricBlocks": lyric_blocks,
            })

    if len(songs) != EXPECTED_TOTAL:
        raise RuntimeError(f"Expected {EXPECTED_TOTAL} songs; found {len(songs)}")
    if len({song["id"] for song in songs}) != len(songs):
        raise RuntimeError("Duplicate catalogue IDs found")
    for volume, count in EXPECTED_KEPT.items():
        found = sum(1 for song in songs if song["volume"] == volume)
        if found != count:
            raise RuntimeError(f"Volume {volume}: expected {count} songs; found {found}")
    verify_removed_twins(removed_lyrics, {song["id"]: song for song in songs})

    payload = {
        "schemaVersion": 1,
        "bookTitle": "এশকে ছাদেক",
        "subtitle": "ভান্ডারী গানের বই",
        "author": "শাহছুফি ছৈয়দ হযরত মৌলানা আহমদ উল্লাহ আল মাইজভাণ্ডারী আল পোমরাবী (কঃ)",
        "source": "Song text: original Word document. Index titles/order: corrected Word copy.",
        "sourceSha256": original_hash_before,
        "indexSha256": index_hash_before,
        "duplicateExclusions": {
            "policy": "later copy removed, first occurrence kept; printed numbering preserved",
            "removed": {str(volume): sorted(drops)
                        for volume, drops in DUPLICATE_REMOVALS.items()},
        },
        "volumes": [
            {"id": 1, "name": "প্রথম খণ্ড", "count": EXPECTED_KEPT[1]},
            {"id": 2, "name": "দ্বিতীয় খণ্ড", "count": EXPECTED_KEPT[2]},
            {"id": 3, "name": "তৃতীয় খণ্ড", "count": EXPECTED_KEPT[3]},
        ],
        "songs": songs,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    encoded_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    output_path.write_bytes(gzip.compress(encoded_json, compresslevel=9, mtime=0))

    emphasis_output_path.parent.mkdir(parents=True, exist_ok=True)
    encoded_emphasis = json.dumps(
        emphasis_ranges, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    emphasis_output_path.write_bytes(
        gzip.compress(encoded_emphasis, compresslevel=9, mtime=0)
    )

    # Verify inputs are byte-identical after extraction (read-only guarantee).
    if sha256(original_path) != original_hash_before or sha256(index_path) != index_hash_before:
        raise RuntimeError("Input Word document changed during extraction")

    print(json.dumps({
        "songs": len(songs),
        "counts": EXPECTED_KEPT,
        "source_sha256": original_hash_before,
        "index_sha256": index_hash_before,
        "catalogue_gzip_bytes": output_path.stat().st_size,
        "catalogue_json_bytes": len(encoded_json),
        "output": str(output_path),
        "lyric_blocks_with_bold_ranges": sum(bool(ranges) for ranges in emphasis_ranges),
        "emphasis_gzip_bytes": emphasis_output_path.stat().st_size,
        "emphasis_output": str(emphasis_output_path),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
