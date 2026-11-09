#!/usr/bin/env python3
"""Calibration harness for the voice fuzzy n-gram fallback.

The Android matcher (MainActivity.scoreVoiceFuzzyMatch) gave up on exact word
matching for sung audio: speech transcribers garble words that are being sung,
so the strict ordered-word path rejects the real song. This script implements
the same character n-gram sketch + verification algorithm over the real
catalogue and measures, under simulated garbling:

  1. Recall          — how often the true song passes the fuzzy gate+verify.
  2. Rank quality    — the true song position among all 615 fuzzy candidates.
  3. False positives — how often unrelated "conversation" blobs pass.

Thresholds live next to the Java constants; this script does not change app
behaviour, it only prints evidence. Run: python3 tests/calibrate_voice_fuzzy.py
"""
from pathlib import Path
import gzip
import json
import math
import random
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/songs.json.gz"

# Mirror of MainActivity voice fuzzy constants (keep in sync).
VOICE_GRAM_SIZE = 3
VOICE_GRAM_SKETCH_LIMIT = 256
VOICE_FUZZY_SKETCH_GATE = 0.40
VOICE_FUZZY_VERIFY_RATIO = 0.45
VOICE_FUZZY_QUERY_LIMIT = 260

# Mirror of normalize(): NFC, lowercase, fold regional forms, drop separators.

MODERN_ALIASES = [
    ("হইয়াছে", "হয়েছে"), ("করিয়াছি", "করেছি"), ("করিয়াছে", "করেছে"),
    ("করিয়াছেন", "করেছেন"), ("হইয়া", "হয়ে"), ("করিয়া", "করে"),
    ("করিতেছে", "করছে"), ("যাইতেছে", "যাচ্ছে"), ("করিতে", "করতে"),
    ("তাহাকে", "তাকে"),
]

def is_separator(ch):
    return (unicodedata.category(ch).startswith(("P", "Z"))
            or ch in ("\u200c", "\u200d") or ch.isspace())

def normalize(value):
    s = unicodedata.normalize("NFC", value).lower().strip()
    for old, new in (("য়", "য"), ("ড়", "ড"), ("ঢ়", "ড"), ("ৎ", "ত")):
        s = s.replace(old, new)
    return "".join(ch for ch in s if not is_separator(ch))

def modernize(text):
    tokens = text.replace("\n", " \n ").split(" ")
    for old, new in MODERN_ALIASES:
        tokens = [new if tok == old else tok for tok in tokens]
    return "".join(tokens)

def gram_hash(text, start, end):
    h = 0xcbf29ce484222325
    for ch in text[start:end]:
        h ^= ord(ch)
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h

def build_sketch(normalized):
    count = max(0, len(normalized) - VOICE_GRAM_SIZE + 1)
    if count <= 0:
        return [], 0
    keep = min(count, VOICE_GRAM_SKETCH_LIMIT)
    sketch = sorted(
        gram_hash(normalized, (i * count) // keep, (i * count) // keep + VOICE_GRAM_SIZE)
        for i in range(keep)
    )
    return sketch, count

def gram_set(normalized):
    return {normalized[i:i + VOICE_GRAM_SIZE]
            for i in range(max(0, len(normalized) - VOICE_GRAM_SIZE + 1))}

def count_grams_in_text(text, gram_lookup):
    count = len(text) - VOICE_GRAM_SIZE + 1
    return sum(1 for i in range(max(0, count))
               if text[i:i + VOICE_GRAM_SIZE] in gram_lookup)

def eval_query(doc, query):
    """Returns (sketch_pass, gate, sketch_hits, true_ratio) using exact mirrors."""
    window = query[-VOICE_FUZZY_QUERY_LIMIT:]
    grams = len(window) - VOICE_GRAM_SIZE + 1
    if not doc["sketch"] or grams < 10:
        return None
    sample_fraction = len(doc["sketch"]) / doc["gramCount"]
    gate = max(2, math.ceil(sample_fraction * grams * VOICE_FUZZY_SKETCH_GATE))
    hits = sum(1 for i in range(grams)
               if gram_hash(window, i, i + VOICE_GRAM_SIZE) in doc["sketch_set"])
    if hits < gate:
        return None
    verified = max(count_grams_in_text(window, doc["norm_grams"]),
                   count_grams_in_text(window, doc["modern_grams"]))
    ratio = verified / grams
    return {"gate": gate, "hits": hits, "ratio": ratio}

# ---- garbling model --------------------------------------------------------

def char_pool(text):
    pool = {}
    for ch in text:
        pool[ord(ch)] = pool.get(ord(ch), 0) + 1
    return [ch for ch, _ in pool.items()], [n for _, n in pool.items()]

def garble(text, rng, substitution=0.08, deletion=0.05, insertion=0.05,
           word_drop=0.15, pool=None):
    pool = pool or sorted(set(text))
    out = []
    for word in text.split("\n"):
        if word == "":
            continue
        if rng.random() < word_drop:
            continue
        chars = list(word)
        result = []
        for ch in chars:
            if rng.random() < substitution:
                result.append(rng.choice(pool))
            elif rng.random() < deletion:
                continue
            else:
                result.append(ch)
            if rng.random() < insertion:
                result.append(rng.choice(pool))
        if result:
            out.append("".join(result))
    return "\n".join(out)

def lyric_segment(lyrics, target_chars, rng):
    lines = [l for l in lyrics.split("\n") if l.strip()]
    if not lines:
        return lyrics
    start = rng.randint(0, max(0, len(lines) - 1))
    seg = []
    total = 0
    for i in range(start, len(lines)):
        seg.append(lines[i])
        total += len(lines[i])
        if total >= target_chars:
            break
    return "\n".join(seg)

def main():
    payload = json.loads(gzip.decompress(CATALOG.read_bytes()).decode("utf-8"))
    songs = payload["songs"]
    docs = []
    for song in songs:
        corpus = song["title"] + " " + song["lyrics"]
        normalized = normalize(corpus)
        modern = normalize(modernize(corpus))
        sketch, gram_count = build_sketch(normalized)
        docs.append({
            "id": song["id"], "normalized": normalized, "modern": modern,
            "sketch": sketch, "sketch_set": set(sketch), "gramCount": gram_count,
            "norm_grams": gram_set(normalized), "modern_grams": gram_set(modern),
        })

    rng = random.Random(20261109)
    windows = (60, 120, 240)
    garble_levels = (0.0, 0.08, 0.20)
    true_ratios = {(w, g): [] for w in windows for g in garble_levels}
    true_sketch_pass = {(w, g): 0 for w in windows for g in garble_levels}
    true_totals = {(w, g): 0 for w in windows for g in garble_levels}
    correct_top3 = {(w, g): 0 for w in windows for g in garble_levels}
    fp_records = []  # (blob, [(id, ratio), ...]) for sketch-passing docs

    for doc in docs:
        raw = next(s["lyrics"] for s in songs if s["id"] == doc["id"])
        for window_chars in windows:
            seg = lyric_segment(raw, window_chars, rng)
            plain = normalize(seg)
            for garble_test in garble_levels:
                query_text = plain
                if garble_test > 0:
                    query_text = normalize(garble(seg, rng,
                                                  substitution=garble_test))
                if len(query_text) < 30:
                    continue
                key = (window_chars, garble_test)
                true_totals[key] += 1
                evaluated = eval_query(doc, query_text)
                if evaluated:
                    true_ratios[key].append(evaluated["ratio"])
                    true_sketch_pass[key] += 1
                best = ("", -1)
                rank_seen = True
                for other in docs:
                    match = eval_query(other, query_text)
                    if match and match["ratio"] >= VOICE_FUZZY_VERIFY_RATIO:
                        score = 420 + match["ratio"] * 500
                        if score > best[1]:
                            best = (other["id"], score)
                if best[0] in (doc["id"],) or (best[0] == "") and evaluated is None:
                    pass
                gscores = []
                for other in docs:
                    match = eval_query(other, query_text)
                    if match and match["ratio"] >= VOICE_FUZZY_VERIFY_RATIO:
                        gscores.append((other["id"], 420 + match["ratio"] * 500))
                gscores.sort(key=lambda x: -x[1])
                rank = next((i for i, g in enumerate(gscores)
                             if g[0] == doc["id"]), None)
                if evaluated and rank is not None and rank < 3:
                    correct_top3[key] += 1

    # Conversation-like false positives: blobs of words from unrelated songs.
    fp_blob_records = []
    for _ in range(120):
        blob_words = []
        for _doc in rng.sample(songs, 3):
            words = " ".join(lyric_segment(_doc["lyrics"], 60, rng).split("\n"))
            blob_words.extend(words.split())
        rng.shuffle(blob_words)
        blob = normalize(" ".join(blob_words))[:130]
        passing = []
        for doc in docs:
            match = eval_query(doc, blob)
            if match:
                passing.append((doc["id"], match["ratio"]))
        fp_blob_records.append(passing)

    thresholds = (0.30, 0.35, 0.40, 0.45, 0.50)
    print("Voice-fuzzy calibration — 615 songs, seeded run")
    print(f"sketchGate={VOICE_FUZZY_SKETCH_GATE} "
          f"window={VOICE_FUZZY_QUERY_LIMIT}")
    print(f"{'':>14}" + "".join(f"{t:>8.2f}" for t in thresholds)
          + "   sketch-pass   present")
    for garble_test in garble_levels:
        for window_chars in windows:
            key = (window_chars, garble_test)
            ratios = true_ratios[key]
            total = true_totals[key] or 1
            recalls = [sum(1 for r in ratios if r >= t) for t in thresholds]
            print(f"g={garble_test:.2f} w={window_chars:>3}   "
                  + "".join(f"{100 * n / total:>7.0f}%" for n in recalls)
                  + f"   {100 * true_sketch_pass[key] / total:>8.0f}% "
                  + f"  q={total}")
    print("\nfuzzy top-3 rate under current thresholds "
          f"(verify={VOICE_FUZZY_VERIFY_RATIO}):")
    for garble_test in garble_levels:
        for window_chars in windows:
            key = (window_chars, garble_test)
            total = true_totals[key] or 1
            print(f"  g={garble_test:.2f} w={window_chars:>3}   "
                  f"{100 * correct_top3[key] / total:.0f}%")
    worst = max((len(p) for p in fp_blob_records), default=0)
    fp_above = sum(1 for p in fp_blob_records
                   for _, ratio in p if ratio >= VOICE_FUZZY_VERIFY_RATIO)
    print(f"\nconversation blobs ({len(fp_blob_records)}): "
          f"sketch-level candidates per blob max={worst} "
          f"avg={sum(len(p) for p in fp_blob_records) / len(fp_blob_records):.2f}")
    print(f"verify-level candidates per blob total={fp_above} "
          f"({fp_above / len(fp_blob_records):.2f} avg, out of 615 candidates)")
    print("\nfalse-positive sweep over verify thresholds:")
    for t in thresholds:
        n = sum(1 for p in fp_blob_records for _, ratio in p if ratio >= t)
        print(f"  verify>={t:.2f}: total FP across {len(fp_blob_records)} "
              f"blobs = {n}")

    # Regression gate for CI: mirror the Java constants and fail loudly if the
    # tuned behaviour erodes (e.g. after changing normalisation or sketches).
    fp_avg = fp_above / len(fp_blob_records)
    mild_recall = {w: None for w in windows}
    for garble_test in garble_levels:
        for window_chars in windows:
            key = (window_chars, garble_test)
            ratios = true_ratios[key]
            total = true_totals[key] or 1
            pass_rate = sum(1 for r in ratios
                            if r >= VOICE_FUZZY_VERIFY_RATIO) / total
            if garble_test == 0.08:
                mild_recall[window_chars] = pass_rate
    if fp_avg > 0.5:
        print("FAIL: conversation candidates exceed 0.5 per blob")
        sys.exit(1)
    if min(mild_recall.values()) < 0.70:
        print("FAIL: mild-garble recall dropped below 70%")
        sys.exit(1)
    print("PASS: consistent with the shipped voice-fuzzy thresholds")

if __name__ == "__main__":
    main()
