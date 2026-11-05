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

from docx import Document

ROOT = Path(__file__).resolve().parent


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
    args = parser.parse_args()

    original_path = args.original.resolve()
    index_path = args.index.resolve()
    output_path = args.output.resolve()
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
    expected_counts = {1: 82, 2: 289, 3: 244}
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
            if 1 <= number <= expected_counts[volume] and len(content) < 100:
                markers.append((paragraph_index, number, content))

        actual_numbers = [number for _, number, _ in markers]
        expected_numbers = list(range(1, expected_counts[volume] + 1))
        if actual_numbers != expected_numbers:
            raise RuntimeError(
                f"Volume {volume} marker sequence mismatch: {len(actual_numbers)} records; "
                f"expected {expected_counts[volume]}. First observed numbers: {actual_numbers[:20]}"
            )

        for position, (paragraph_index, number, heading) in enumerate(markers):
            stop = markers[position + 1][0] if position + 1 < len(markers) else end
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

    if len(songs) != sum(expected_counts.values()):
        raise RuntimeError(f"Expected 615 songs; found {len(songs)}")
    if len({song["id"] for song in songs}) != len(songs):
        raise RuntimeError("Duplicate catalogue IDs found")

    payload = {
        "schemaVersion": 1,
        "bookTitle": "এশকে ছাদেক",
        "subtitle": "ভান্ডারী গানের বই",
        "author": "শাহছুফি ছৈয়দ হযরত মৌলানা আহমদ উল্লাহ আল মাইজভাণ্ডারী আল পোমরাবী (কঃ)",
        "source": "Song text: original Word document. Index titles/order: corrected Word copy.",
        "sourceSha256": original_hash_before,
        "indexSha256": index_hash_before,
        "volumes": [
            {"id": 1, "name": "প্রথম খণ্ড", "count": 82},
            {"id": 2, "name": "দ্বিতীয় খণ্ড", "count": 289},
            {"id": 3, "name": "তৃতীয় খণ্ড", "count": 244},
        ],
        "songs": songs,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    encoded_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    output_path.write_bytes(gzip.compress(encoded_json, compresslevel=9, mtime=0))

    # Verify inputs are byte-identical after extraction (read-only guarantee).
    if sha256(original_path) != original_hash_before or sha256(index_path) != index_hash_before:
        raise RuntimeError("Input Word document changed during extraction")

    print(json.dumps({
        "songs": len(songs),
        "counts": expected_counts,
        "source_sha256": original_hash_before,
        "index_sha256": index_hash_before,
        "catalogue_gzip_bytes": output_path.stat().st_size,
        "catalogue_json_bytes": len(encoded_json),
        "output": str(output_path),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
