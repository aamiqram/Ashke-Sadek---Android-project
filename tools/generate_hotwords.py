#!/usr/bin/env python3
"""Generate sherpa-onnx hotwords from the bundled Bengali song catalogue.

This is the machine side of the voice-search bridge: the offline streaming
decoder is taught the songbook's own vocabulary, so sung or spoken fragments
snap to the book's words instead of generic Bengali. The file feeds
OnlineRecognizerConfig.setHotwordsFile(); decoding must be
"modified_beam_search" for the runtime to honour it.

Rules baked in here, verified against sherpa-onnx v2.0.1:
- The bundled model is a sentencepiece subword transducer (tokens.txt holds
  '▁'-prefixed word-start pieces plus single characters). A phrase is only
  emitted if every word can be segmented by the model's real tokenizer, so the
  Android runtime can never fail to load the file.
- Text is NFC-normalized (decomposed য়/ড়/ঢ় collapse into the precomposed
  codepoints the tokenizer knows), Assamese ৰ/ৱ map to their Bengali
  counterparts, and soundless marks (digits, danda, ﷺ, ZWNJ) are stripped.
- The set stays lean: song titles, two distinctive first-line windows per
  song, and words rare across the book. Common words are excluded - boosting
  them wastes the bias budget on words every song shares.

Regenerate after any catalogue change:
    python tools/generate_hotwords.py
"""
import gzip
import io
import json
import re
import unicodedata
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "songs.json.gz"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "hotwords.txt"
LOCAL_TOKENS = (ROOT / "app" / "build" / "generated" / "offline-voice-assets"
                / "sherpa-onnx-bn-vosk-int8-19d0fc5" / "tokens.txt")
TOKENS_URL = ("https://huggingface.co/bbbipul/vosk-small-streaming-bn-int8"
              "/resolve/19d0fc5/tokens.txt")

# Letters of the printed book that the speech tokenizer lacks.
CHAR_MAP = {"ৰ": "র", "ৱ": "ও"}
# The book's text often carries য়/ড়/ঢ় in decomposed form (letter + nukta).
# The tokenizer only knows the precomposed codepoints, and Unicode NFC does not
# compose them when a vowel sign follows - so compose explicitly.
DECOMPOSED_NUKTA = {
    "\u09af\u09bc": "\u09df",  # য়
    "\u09a1\u09bc": "\u09dc",  # ড়
    "\u09a2\u09bc": "\u09dd",  # ঢ়
}
# Marks with no spoken content for the recognizer.
STRIP_RE = re.compile("[\u09e6-\u09ef0-9\u0964\u0965ﷺ\u200c\u200d"
                      "\u0964.,!?;:'\"()\\[\\]\\-—–…]")

STOPWORDS = {
    "আমার", "তোমার", "তাহার", "তার", "আমি", "তুমি", "সে", "তুই", "আমরা",
    "তোমরা", "তারা", "কেন", "কি", "কী", "না", "নাই", "আর", "ও", "এই", "সেই",
    "ওই", "যে", "যা", "হয়", "হইয়া", "হবে", "হইবে", "হলো", "হইল", "করে",
    "করি", "করিবে", "করিয়া", "থাকি", "থাকে", "যায়", "আসে", "দিয়ে",
    "লইয়া", "মনে", "মধ্যে", "সব", "সবাই", "কত", "এমন", "সেয়ি", "তো",
    "ই", "হে", "ওহে", "আহা", "নিজে", "নিজের", "ঐ",
}

RARE_WORD_MAX_SONGS = 15   # a word in more songs than this is book-common
RARE_WORD_MIN_CHARS = 3
RARE_WORD_LIMIT = 2200
FIRST_LINE_WINDOWS_PER_SONG = 2


def load_tokens():
    if not LOCAL_TOKENS.exists():
        LOCAL_TOKENS.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(TOKENS_URL, LOCAL_TOKENS)
    pieces = []
    for line in io.open(LOCAL_TOKENS, encoding="utf-8").read().splitlines():
        if not line.strip():
            continue
        token = line.rsplit(" ", 1)[0]
        if token.startswith("<") or token.startswith("__"):
            continue
        pieces.append(token)
    return pieces


def build_encodable(pieces):
    """Mirrors sentencepiece encoding of space-separated text: a word starts
    with a '▁' piece (or the bare '▁' token) and continues with plain pieces."""
    by_length = {}
    for piece in pieces:
        by_length.setdefault(len(piece), set()).add(piece)
    lengths = sorted(by_length, reverse=True)

    def encodable(word):
        text = "▁" + word
        index = 0
        while index < len(text):
            for size in lengths:
                if size > len(text) - index:
                    continue
                if text[index:index + size] in by_length[size]:
                    index += size
                    break
            else:
                return False
        return True

    return encodable


def clean(text):
    text = unicodedata.normalize("NFC", text)
    for decomposed, precomposed in DECOMPOSED_NUKTA.items():
        text = text.replace(decomposed, precomposed)
    for source, target in CHAR_MAP.items():
        text = text.replace(source, target)
    text = STRIP_RE.sub(" ", text)
    text = text.replace("\u09bc", "")  # any stray nukta: no spoken content
    return re.sub(r"\s+", " ", text).strip()


def main():
    pieces = load_tokens()
    encodable = build_encodable(pieces)
    print("model tokenizer pieces: %d" % len(pieces))

    with gzip.open(CATALOG, "rb") as stream:
        songs = json.loads(stream.read().decode("utf-8"))["songs"]
    print("songs: %d" % len(songs))

    word_lists = []
    songs_with_word = Counter()
    for song in songs:
        words = []
        for text in (song.get("title", ""), song.get("lyrics", "")):
            words += clean(text).split()
        word_lists.append(words)
        songs_with_word.update(set(words))

    rare = {
        word for word, count in songs_with_word.items()
        if count <= RARE_WORD_MAX_SONGS and len(word) >= RARE_WORD_MIN_CHARS
        and word not in STOPWORDS
    }
    rare_ranked = sorted(rare, key=lambda w: (songs_with_word[w], -len(w)))
    rare_ranked = [w for w in rare_ranked if encodable(w)][:RARE_WORD_LIMIT]

    titles, windows = set(), set()
    skipped_words = Counter()
    for song, words in zip(songs, word_lists):
        for word in words:
            if word not in rare and not encodable(word):
                skipped_words[word] += 1

        title_words = clean(song.get("title", "")).split()
        if title_words and all(encodable(w) for w in title_words):
            titles.add(" ".join(title_words))

        lines = [clean(line) for line in song.get("lyrics", "").splitlines()
                 if clean(line)]
        added = 0
        for line in lines[:2]:
            parts = line.split()
            if len(parts) < 3:
                continue
            for start in range(len(parts) - 2):
                window = parts[start:start + 3]
                if (any(w in rare for w in window)
                        and all(encodable(w) for w in window)):
                    windows.add(" ".join(window))
                    added += 1
                if added >= FIRST_LINE_WINDOWS_PER_SONG:
                    break
            if added >= FIRST_LINE_WINDOWS_PER_SONG:
                break

    lines_out = sorted(titles | set(rare_ranked) | windows)
    skipped = sum(skipped_words.values())
    OUTPUT.write_text("\n".join(lines_out) + "\n", encoding="utf-8")
    size_kb = OUTPUT.stat().st_size / 1024.0
    print("hotwords: %d titles, %d first-line windows, %d rare words = %d lines (%.0f KB)"
          % (len(titles), len(windows), len(rare_ranked), len(lines_out), size_kb))
    if skipped:
        print("dropped unencodable tokens after normalization: %d occurrences of %d words"
              % (skipped, len(skipped_words)))
        print("  sample:", sorted(skipped_words)[:12])
    print("written: %s" % OUTPUT)


if __name__ == "__main__":
    main()
