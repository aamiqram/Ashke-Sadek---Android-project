#!/usr/bin/env python3
"""Calibration harness for the voice alignment matcher (VoiceMatchEngine).

The Android voice search pipeline is: strict word scorer over all 583 songs ->
character-trigram sketch prefilter -> semi-global alignment of the transcript
window against the lyrics. This script mirrors that pipeline over the real
catalogue and measures, under seeded garbling and ordinary-speech blobs:

  1. Recall / rank   — sung fragments must place the true song in the top 3.
  2. False positives — ordinary speech must produce ~no candidates.
  3. Confusable pairs — known sound-alike songs must not steal the leader.

Everything Java-side (normalisation folds, alias table, thresholds, scoring
constants) is parsed directly out of VoiceMatchEngine.java and MainActivity.java,
so a code change that moves a constant without re-measuring fails here loudly.
The JVM-side behaviour itself is additionally pinned by the self-test
tests/java/org/ashkeshadek/reader/VoiceEngineSelftest.java (CI compiles and
runs the real engine).

Run: python3 tests/calibrate_voice_fuzzy.py   (needs numpy)
"""
from pathlib import Path
import gzip
import io
import json
import math
import random
import re
import sys
import unicodedata

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/songs.json.gz"
ENGINE_JAVA = (ROOT / "app/src/main/java/org/ashkeshadek/reader/"
               "VoiceMatchEngine.java")
MAIN_JAVA = (ROOT / "app/src/main/java/org/ashkeshadek/reader/"
             "MainActivity.java")

# ---------------------------------------------------------------------------
# Constants parsed from the Java sources (single source of truth)
# ---------------------------------------------------------------------------

def java_int(name, text):
    match = re.search(r"\b%s\s*=\s*(-?\d+)" % name, text)
    if not match:
        sys.exit("constant %s not found in Java sources" % name)
    return int(match.group(1))

def java_double(name, text):
    match = re.search(r"\b%s\s*=\s*([0-9.]+)" % name, text)
    if not match:
        sys.exit("constant %s not found in Java sources" % name)
    return float(match.group(1))

ENGINE_TEXT = io.open(ENGINE_JAVA, encoding="utf-8").read()
MAIN_TEXT = io.open(MAIN_JAVA, encoding="utf-8").read()

ALIGN_MATCH = java_int("ALIGN_MATCH", ENGINE_TEXT)
ALIGN_MISMATCH = java_int("ALIGN_MISMATCH", ENGINE_TEXT)
ALIGN_GAP = java_int("ALIGN_GAP", ENGINE_TEXT)
VOICE_GRAM_SIZE = java_int("VOICE_GRAM_SIZE", ENGINE_TEXT)
VOICE_GRAM_SKETCH_LIMIT = java_int("VOICE_GRAM_SKETCH_LIMIT", ENGINE_TEXT)
VOICE_QUERY_WINDOW = java_int("VOICE_QUERY_WINDOW", ENGINE_TEXT)
VOICE_ALIGN_SHORTLIST = java_int("VOICE_ALIGN_SHORTLIST", ENGINE_TEXT)
VOICE_STRICT_DECISIVE = java_int("VOICE_STRICT_DECISIVE", ENGINE_TEXT)
ALIGNER_BASE = java_int("ALIGNER_BASE", ENGINE_TEXT)
ALIGNER_SPAN = java_int("ALIGNER_SPAN", ENGINE_TEXT)
VOICE_ALIGN_THRESHOLD = java_double("VOICE_ALIGN_THRESHOLD", ENGINE_TEXT)
VOICE_SKETCH_GATE = java_double("VOICE_SKETCH_GATE", ENGINE_TEXT)
VOICE_ALIGN_MIN_CHARS = java_int("VOICE_ALIGN_MIN_CHARS", ENGINE_TEXT)
VOICE_PIN_CONFIRM_SCORE = java_int("VOICE_PIN_CONFIRM_SCORE", MAIN_TEXT)
VOICE_AUTO_OPEN_SCORE = java_int("VOICE_AUTO_OPEN_SCORE", MAIN_TEXT)

# Alias table: extracted verbatim so the mirror can never drift.
alias_block = re.search(
    r"OLD_BENGALI_ALIASES = \{(.*?)\n    \};", ENGINE_TEXT, re.S).group(1)
ALIASES = [(a, b) for a, b in
           re.findall(r'\{"([^"]+)",\s*"([^"]+)"\}', alias_block)]
if len(ALIASES) != 30:
    sys.exit("expected 30 aliases in Java sources, found %d" % len(ALIASES))

# Fold classes: foldClass(table, "...") literals in buildFold; everything
# before the `if (!light) {` branch is the light depth, the rest aggressive.
fold_calls = re.findall(
    r'foldClass\(table, "((?:\\u[0-9a-fA-F]{4})+)"\);', ENGINE_TEXT)

def parse_members(literal):
    return "".join(chr(int(code, 16))
                   for code in re.findall(r"\\u([0-9a-fA-F]{4})", literal))

light_end = ENGINE_TEXT.find("if (!light) {")
light_classes, aggressive_classes = [], []
position = 0
for literal in fold_calls:
    start = ENGINE_TEXT.find('foldClass(table, "%s")' % literal, position)
    position = start
    (aggressive_classes if start > light_end else light_classes).append(
        parse_members(literal))

def build_fold(depth):
    table = {}

    def union(members):
        roots = {table.get(ord(ch), ord(ch)) for ch in members}
        canonical = ord(members[0])
        merged = roots | {canonical}
        for ch, root in list(table.items()):
            if root in merged:
                table[ch] = canonical
        for ch in members:
            table[ord(ch)] = canonical

    for members in light_classes:
        union(members)
    if depth == "aggressive":
        for members in aggressive_classes:
            union(members)
    return table

FOLD = build_fold("aggressive")

def fold_classes(text):
    return np.array([FOLD.get(ord(c), ord(c)) for c in text], dtype=np.int32)

# ---------------------------------------------------------------------------
# Java-exact normalisation
# ---------------------------------------------------------------------------

SEPARATOR_RE = re.compile(
    r"[!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~\s\u200c\u200d]+")
# Word-character boundary for alias matching: the Java pattern uses
# [\p{L}\p{M}]; the catalogue only contains Bengali and ASCII, so covering
# the Bengali block plus ASCII word characters mirrors it exactly.
BOUNDARY = r"[\u0980-\u09ffa-zA-Z0-9_]"

def normalize(value):
    s = unicodedata.normalize("NFC", value).lower().strip()
    s = (s.replace("\u09af\u09bc", "\u09df").replace("\u09a1\u09bc", "\u09dc")
          .replace("\u09a2\u09bc", "\u09dc").replace("\u09dd", "\u09dc")
          .replace("\u09f0", "\u09b0").replace("\u09f1", "\u0993")
          .replace("\u09bc", "").replace("\u09ce", "\u09a4"))
    return SEPARATOR_RE.sub("", s)

def modernize(text):
    result = unicodedata.normalize("NFC", text)
    for source, target in ALIASES:
        nfc_source = unicodedata.normalize("NFC", source)
        nfc_target = unicodedata.normalize("NFC", target)
        result = re.sub(r"(?<!%s)%s(?!%s)"
                        % (BOUNDARY, re.escape(nfc_source), BOUNDARY),
                        nfc_target.replace("\\", "\\\\"), result)
    return result

def phonetic_normalize(value):
    result = normalize(value)
    for source, target in (("\u09b6", "\u09b8"), ("\u09b7", "\u09b8"),
                           ("\u09a3", "\u09a8"), ("\u09c0", "\u09bf"),
                           ("\u0988", "\u0987"), ("\u09c2", "\u09c1"),
                           ("\u098a", "\u0989"), ("\u09ce", "\u09a4")):
        result = result.replace(source, target)
    return result

WORD_RE = re.compile(r"[\w\u0980-\u09ff]+", re.UNICODE)

def search_words(value):
    words = set()
    if not value:
        return words
    for token in WORD_RE.findall(value):
        word = normalize(token)
        if len(word) >= 2:
            words.add(word)
    return words

def prepare_query(value):
    original = value or ""
    unique_terms = []
    seen = set()
    for token in WORD_RE.findall(original):
        word = normalize(token)
        if len(word) >= 2 and word not in seen:
            seen.add(word)
            unique_terms.append(word)
    terms = []
    for token in unique_terms:
        terms.append({
            "original": token,
            "modern": normalize(modernize(token)),
            "phonetic": phonetic_normalize(token),
        })
    return {
        "normalized": normalize(original),
        "modern": normalize(modernize(original)),
        "phonetic": phonetic_normalize(original),
        "terms": terms,
    }

# ---------------------------------------------------------------------------
# Sketch (Java-exact FNV-1a over character trigrams)
# ---------------------------------------------------------------------------

def gram_hash(text, start, end):
    h = 0xcbf29ce484222325
    for ch in text[start:end]:
        h ^= ord(ch)
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h

def build_sketch(normalized):
    count = max(0, len(normalized) - VOICE_GRAM_SIZE + 1)
    if count <= 0:
        return set(), 0
    keep = min(count, VOICE_GRAM_SKETCH_LIMIT)
    sketch = set()
    for i in range(keep):
        start = (i * count) // keep
        sketch.add(gram_hash(normalized, start, start + VOICE_GRAM_SIZE))
    return sketch, count

def query_gram_hashes(window):
    count = max(0, len(window) - VOICE_GRAM_SIZE + 1)
    return [gram_hash(window, i, i + VOICE_GRAM_SIZE) for i in range(count)]

# ---------------------------------------------------------------------------
# Alignment (vectorised DP, Java-exact scoring)
# ---------------------------------------------------------------------------

def similarity(qc, tc):
    m, n = len(qc), len(tc)
    if m == 0 or n == 0:
        return 0.0
    if n > 30000:
        return 0.0
    prev = np.zeros(n + 1, dtype=np.int32)
    arange = np.arange(n + 1, dtype=np.int32)
    gap_offsets = ALIGN_GAP * arange
    for i in range(m):
        # diag[j] = prev[j-1] + match/mismatch for text position j-1
        diag = prev[:-1] + np.where(tc == qc[i], ALIGN_MATCH, ALIGN_MISMATCH)
        up = prev[1:] + ALIGN_GAP
        base = np.empty(n + 1, dtype=np.int32)
        base[0] = prev[0] + ALIGN_GAP
        base[1:] = np.maximum(diag, up)
        # cur[j] = max(base[j], cur[j-1] + GAP) unrolls to
        # cur[j] = GAP*j + max_{k<=j} (base[k] - GAP*k) — a prefix max.
        prev = np.maximum.accumulate(base - gap_offsets) + gap_offsets
    best = int(prev.max())
    if best <= 0:
        return 0.0
    return min(1.0, best / (m * ALIGN_MATCH))

def aligner_score(sim):
    if sim < VOICE_ALIGN_THRESHOLD:
        return 0
    return int(ALIGNER_BASE + (sim - VOICE_ALIGN_THRESHOLD)
               * ALIGNER_SPAN / (1.0 - VOICE_ALIGN_THRESHOLD))

# ---------------------------------------------------------------------------
# Strict scorer (Java-exact port of VoiceMatchEngine.scoreSongMatch)
# ---------------------------------------------------------------------------

def count_ordered_matches(text, terms, form):
    cursor = 0
    matched = 0
    for term in terms:
        word = term["modern"] if form == 1 else (
            term["phonetic"] if form == 2 else term["original"])
        position = text.find(word, cursor)
        if position < 0 or position - cursor > 80:
            continue
        matched += 1
        cursor = position + len(word)
    return matched

def score_song_match(song, doc, query, voice_input):
    if len(query["normalized"]) < (3 if voice_input else 1):
        return 0
    phrase_minimum = 5 if voice_input else 3
    if (len(query["normalized"]) >= phrase_minimum
            and (not voice_input or len(query["terms"]) >= 2)):
        if query["normalized"] in doc["title"]:
            return 2400 + len(query["normalized"])
        if query["normalized"] in doc["normalized"]:
            return 1800 + len(query["normalized"])
        if (query["modern"] != query["normalized"]
                and query["modern"] in doc["modern"]):
            return 1550 + len(query["modern"])
        if (len(query["phonetic"]) >= phrase_minimum
                and query["phonetic"] in doc["phonetic"]):
            return 1300 + len(query["phonetic"])
    if not query["terms"]:
        return 0
    if voice_input and len(query["terms"]) < 3:
        return 0

    exact = modern = phonetic = prefix = fuzzy = 0
    for term in query["terms"]:
        if term["original"] in doc["words"]:
            exact += 1
        elif (term["original"] in doc["modern_words"]
              or term["modern"] in doc["words"]):
            modern += 1
        elif term["phonetic"] in doc["phonetic_words"]:
            phonetic += 1
        elif (not voice_input and len(term["original"]) >= 2
              and any(w.startswith(term["original"])
                      for w in doc["words"] | doc["modern_words"])):
            prefix += 1
        elif not voice_input and len(term["original"]) >= 4:
            if any(one_edit_away(term["original"], w)
                   for w in doc["words"]):
                fuzzy += 1

    matched = exact + modern + phonetic + prefix + fuzzy
    if matched == 0:
        return 0
    coverage = matched * 100 // len(query["terms"])
    ordered_matches = 0
    if voice_input:
        required = 2 if len(query["terms"]) == 1 else max(
            2, (len(query["terms"]) * 60 + 99) // 100)
        if matched < required:
            return 0
        ordered_matches = max(
            count_ordered_matches(doc["normalized"], query["terms"], 0),
            count_ordered_matches(doc["modern"], query["terms"], 1),
            count_ordered_matches(doc["phonetic"], query["terms"], 2))
        if ordered_matches < max(2, (len(query["terms"]) + 1) // 2):
            return 0
    elif len(query["terms"]) >= 3 and matched < 2:
        return 0

    score = (exact * 125 + modern * 105 + phonetic * 82
             + prefix * 54 + fuzzy * 38 + coverage)
    if len(query["terms"]) >= 2 and matched == len(query["terms"]):
        score += 100
    if coverage == 100:
        score += 25
    if voice_input:
        score += ordered_matches * 35
    return score

def one_edit_away(first, second):
    i = j = edits = 0
    while i < len(first) and j < len(second):
        if first[i] == second[j]:
            i += 1
            j += 1
            continue
        edits += 1
        if edits > 1:
            return False
        if len(first) > len(second):
            i += 1
        elif len(second) > len(first):
            j += 1
        else:
            i += 1
            j += 1
    if i < len(first) or j < len(second):
        edits += 1
    return edits <= 1

# ---------------------------------------------------------------------------
# Full voice pipeline (Java-exact port of VoiceMatchEngine.compute)
# ---------------------------------------------------------------------------

def compute(catalog, docs, transcript):
    query = prepare_query(transcript)
    window = query["normalized"][-VOICE_QUERY_WINDOW:]
    window_modern = query["modern"][-VOICE_QUERY_WINDOW:]
    grams = len(window) - VOICE_GRAM_SIZE + 1
    if grams < 10:
        return query, []
    hashes = query_gram_hashes(window)
    window_classes = fold_classes(window)
    window_modern_classes = fold_classes(window_modern)

    shortlist = []
    for song in catalog:
        doc = docs[song["id"]]
        strict = score_song_match(song, doc, query, True)
        if strict >= 300:
            hits = 0
        else:
            if not doc["sketch"]:
                continue
            hits = sum(1 for h in hashes if h in doc["sketch"])
            sample_fraction = len(doc["sketch"]) / max(1, doc["gram_count"])
            gate = max(2, math.ceil(sample_fraction * grams * VOICE_SKETCH_GATE))
            if hits < gate:
                continue
        shortlist.append((song, strict, hits))
        if len(shortlist) > 4 * VOICE_ALIGN_SHORTLIST:
            shortlist.sort(key=lambda row: -(row[1] + row[2] * 20))
            del shortlist[VOICE_ALIGN_SHORTLIST * 2:]
    if len(shortlist) > VOICE_ALIGN_SHORTLIST:
        shortlist.sort(key=lambda row: -(row[1] + row[2] * 20))
        del shortlist[VOICE_ALIGN_SHORTLIST:]

    candidates = []
    for song, strict, hits in shortlist:
        doc = docs[song["id"]]
        if strict >= VOICE_STRICT_DECISIVE:
            sim = 1.0
        else:
            sim = similarity(window_classes, doc["folded_normalized"])
            if sim < VOICE_ALIGN_THRESHOLD:
                modern_sim = similarity(window_modern_classes,
                                        doc["folded_modern"])
                if modern_sim > sim:
                    sim = modern_sim
        fuzzy_score = aligner_score(sim)
        final_score = max(strict, fuzzy_score)
        if strict >= 300 or fuzzy_score > 0:
            candidates.append({
                "id": song["id"], "strict": strict, "sim": sim,
                "final": final_score,
                "volume": song["volume"], "number": song["number"],
            })
    candidates.sort(key=lambda c: (-c["final"], c["volume"], c["number"]))
    return query, candidates[:6]

# ---------------------------------------------------------------------------
# Catalogue documents
# ---------------------------------------------------------------------------

def build_docs(songs):
    docs = {}
    for song in songs:
        corpus = song["title"] + " " + song["lyrics"]
        modern = modernize(corpus)
        normalized = normalize(corpus)
        words = search_words(corpus)
        modern_words = search_words(modern)
        phonetic_words = set()
        for word in words | modern_words:
            phonetic_words.add(phoneticize_word(word))
        sketch, gram_count = build_sketch(normalized)
        docs[song["id"]] = {
            "normalized": normalized,
            "modern": normalize(modern),
            "phonetic": phonetic_normalize(corpus),
            "title": normalize(song["title"]),
            "words": words,
            "modern_words": modern_words,
            "phonetic_words": phonetic_words,
            "sketch": sketch,
            "gram_count": gram_count,
            "folded_normalized": fold_classes(normalized),
            "folded_modern": fold_classes(normalize(modern)),
        }
    return docs

def phoneticize_word(value):
    for source, target in (("\u09b6", "\u09b8"), ("\u09b7", "\u09b8"),
                           ("\u09a3", "\u09a8"), ("\u09c0", "\u09bf"),
                           ("\u0988", "\u0987"), ("\u09c2", "\u09c1"),
                           ("\u098a", "\u0989"), ("\u09ce", "\u09a4")):
        value = value.replace(source, target)
    return value

def window_classes_of(query, form):
    text = query["modern"] if form == 1 else query["normalized"]
    window = text[-VOICE_QUERY_WINDOW:]
    return fold_classes(window), window

# ---------------------------------------------------------------------------
# Garbling model (same shape as the shipped fallback calibration)
# ---------------------------------------------------------------------------

def char_pool(text):
    return sorted(set(text))

def garble(text, rng, substitution=0.08, deletion=0.05, insertion=0.05,
           word_drop=0.15, pool=None):
    pool = pool or sorted(set(text))
    out = []
    for word in text.split("\n"):
        if word == "":
            continue
        if rng.random() < word_drop:
            continue
        result = []
        for ch in word:
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
    lines = [line for line in lyrics.split("\n") if line.strip()]
    if not lines:
        return lyrics
    start = rng.randint(0, max(0, len(lines) - 1))
    segment = []
    total = 0
    for line in lines[start:]:
        segment.append(line)
        total += len(line)
        if total >= target_chars:
            break
    return "\n".join(segment)

# ---------------------------------------------------------------------------
# Measurement
# ---------------------------------------------------------------------------

def main():
    payload = json.loads(gzip.decompress(CATALOG.read_bytes()).decode("utf-8"))
    songs = payload["songs"]
    docs = build_docs(songs)
    print("parsed from Java sources:")
    print("  threshold=%s fold-depth=aggressive aliases=%d "
          "window=%d sketchGate=%s shortlist=%d"
          % (VOICE_ALIGN_THRESHOLD, len(ALIASES), VOICE_QUERY_WINDOW,
             VOICE_SKETCH_GATE, VOICE_ALIGN_SHORTLIST))
    print("  aligner mapping: sim %s -> %d, sim 1.0 -> %d"
          % (VOICE_ALIGN_THRESHOLD, ALIGNER_BASE, ALIGNER_BASE + ALIGNER_SPAN))

    rng = random.Random(20261114)
    windows = (60, 120, 240)
    garble_levels = (0.0, 0.08, 0.20)
    sample = rng.sample(songs, 150)

    true_totals = {(w, g): 0 for w in windows for g in garble_levels}
    top1 = {(w, g): 0 for w in windows for g in garble_levels}
    top3 = {(w, g): 0 for w in windows for g in garble_levels}
    admitted = {(w, g): 0 for w in windows for g in garble_levels}

    for song in sample:
        for window_chars in windows:
            segment = lyric_segment(song["lyrics"], window_chars, rng)
            plain = normalize(segment)
            for garble_level in garble_levels:
                text = plain
                if garble_level > 0:
                    text = normalize(garble(segment, rng,
                                            substitution=garble_level))
                if len(text) < 30:
                    continue
                key = (window_chars, garble_level)
                true_totals[key] += 1
                query, candidates = compute(songs, docs, text)
                ids = [c["id"] for c in candidates]
                if candidates:
                    admitted[key] += 1
                if ids[:1] == [song["id"]]:
                    top1[key] += 1
                if song["id"] in ids[:3]:
                    top3[key] += 1

    print("\nrecall / rank over %d sampled songs (seeded):" % len(sample))
    print(f"{'':>22}" + "".join(f"{'g=%.2f' % g:>14}" for g in garble_levels))
    for window_chars in windows:
        row = "w=%-3d top1" % window_chars
        top3_row = "     top3"
        admit_row = "     admit"
        for garble_level in garble_levels:
            key = (window_chars, garble_level)
            total = true_totals[key] or 1
            row += " %11.0f%%" % (100 * top1[key] / total)
            top3_row += " %11.0f%%" % (100 * top3[key] / total)
            admit_row += " %11.0f%%" % (100 * admitted[key] / total)
        print(row)
        print(top3_row)
        print(admit_row)

    # Ordinary-speech blobs: words drawn from unrelated songs.
    blobs_with_candidates = 0
    candidate_total = 0
    blob_count = 120
    for _ in range(blob_count):
        words = []
        for other in rng.sample(songs, 3):
            words.extend(
                lyric_segment(other["lyrics"], 60, rng).replace("\n", " ").split())
        rng.shuffle(words)
        blob = normalize(" ".join(words))[:130]
        if len(blob) < 30:
            continue
        _, candidates = compute(songs, docs, blob)
        if candidates:
            blobs_with_candidates += 1
        candidate_total += len(candidates)
    fp_blob_rate = blobs_with_candidates / blob_count
    fp_avg = candidate_total / blob_count
    print("\nordinary-speech blobs (%d): blobs with candidates %.1f%%, "
          "avg candidates per blob %.2f"
          % (blob_count, 100 * fp_blob_rate, fp_avg))

    # Confusable pairs: sound-alike songs must not steal the leader.
    confusable_ids = [("2-79", "3-153"), ("3-10", "3-96"),
                      ("3-29", "3-138"), ("3-20", "3-122")]
    by_id = {song["id"]: song for song in songs}
    confusion = total_trials = 0
    for id_a, id_b in confusable_ids:
        if id_a not in by_id or id_b not in by_id:
            print("  confusable pair %s/%s absent from catalogue, skipped"
                  % (id_a, id_b))
            continue
        for _ in range(12):
            fragment = lyric_segment(by_id[id_a]["lyrics"], 90, rng)
            text = normalize(garble(fragment, rng, substitution=0.08))
            if len(text) < 30:
                continue
            total_trials += 1
            _, candidates = compute(songs, docs, text)
            if candidates and candidates[0]["id"] == id_b:
                confusion += 1
    confusion_rate = confusion / max(1, total_trials)
    print("confusable pairs: %d trials, twin leads %d (%.0f%%)"
          % (total_trials, confusion, 100 * confusion_rate))

    # ---- CI gates ---------------------------------------------------------
    failures = []
    mild = [(w, 0.08) for w in windows]
    worst_top1 = min(top1[k] / max(1, true_totals[k]) for k in mild)
    worst_top3 = min(top3[k] / max(1, true_totals[k]) for k in mild)
    heavy_top3 = min(top3[(w, 0.20)] / max(1, true_totals[(w, 0.20)])
                     for w in windows)
    if worst_top1 < 0.55:
        failures.append("mild-garble top-1 recall %.0f%% < 55%%"
                        % (100 * worst_top1))
    if worst_top3 < 0.75:
        failures.append("mild-garble top-3 recall %.0f%% < 75%%"
                        % (100 * worst_top3))
    if heavy_top3 < 0.45:
        failures.append("heavy-garble top-3 recall %.0f%% < 45%%"
                        % (100 * heavy_top3))
    if fp_blob_rate > 0.08:
        failures.append("speech blobs with candidates %.1f%% > 8%%"
                        % (100 * fp_blob_rate))
    if fp_avg > 0.15:
        failures.append("avg candidates per speech blob %.2f > 0.15" % fp_avg)
    if confusion_rate > 0.30:
        failures.append("confusable-pair twin leads %.0f%% > 30%%"
                        % (100 * confusion_rate))

    if failures:
        print("\nFAIL:")
        for failure in failures:
            print("  " + failure)
        sys.exit(1)
    print("\nPASS: alignment matcher consistent with the shipped thresholds")

if __name__ == "__main__":
    main()
