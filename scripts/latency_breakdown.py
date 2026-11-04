"""Where the time goes between a voice and its subtitle, from one recognitionReplay log.

Usage: python scripts/latency_breakdown.py recognition-replay.jsonl
"""
import json
import re
import statistics
import sys

WORD_SECONDS = 0.35


def plain(word):
    return re.sub(r"[^a-z0-9']", "", word.lower())


def describe(name, values):
    if not values:
        print(f"{name:34} no data")
        return
    values = sorted(values)
    print(f"{name:34} p50 {statistics.median(values):5.2f}  p90 {values[int(len(values) * .9)]:5.2f}  max {values[-1]:5.2f}  n={len(values)}")


rows = [json.loads(line) for line in open(sys.argv[1], encoding="utf8") if line.strip()]
reads = [r for r in rows if r["kind"] == "read"]
parsed = []
for read in reads:
    words = []
    for item in read["words"].split(" "):
        if "@" in item:
            text, at = item.rsplit("@", 1)
            words.append((plain(text), float(at)))
    parsed.append((read["atMs"] / 1000, words))


def first_heard(word, start):
    for at, words in parsed:
        if any(text == word and abs(heard - start) < 0.4 for text, heard in words):
            return at
    return None


# Each word the first time it appears in the open sentence, in the order spoken.
spoken = []
seen = {}
for row in rows:
    if row["kind"] != "recognition" or row["final"]:
        continue
    words = row["english"].split(" ")
    before = seen.get(row["id"], 0)
    for index in range(before, len(words)):
        is_last = index == len(words) - 1
        spoken.append({"text": words[index], "shown": row["atMs"] / 1000, "start": (row["endMs"] / 1000 - WORD_SECONDS) if is_last else None})
    seen[row["id"]] = max(before, len(words))

to_read, to_line = [], []
for word in spoken:
    if word["start"] is None:
        continue
    heard = first_heard(plain(word["text"]), word["start"])
    if heard is None:
        continue
    to_read.append(heard - (word["start"] + WORD_SECONDS))
    to_line.append(word["shown"] - heard)

to_close, translate, after_end, after_start, lengths = [], [], [], [], []
position = 0
for row in rows:
    if row["kind"] != "unit":
        continue
    count = len(row["english"].split(" "))
    covered = spoken[position:position + count]
    position += count
    lengths.append(count)
    translate.append(row["translateMs"] / 1000)
    if not covered:
        continue
    closed = (row["atMs"] - row["translateMs"]) / 1000
    to_close.append(closed - covered[-1]["shown"])
    known = [word for word in covered if word["start"] is not None]
    if known:
        after_end.append(row["atMs"] / 1000 - (known[-1]["start"] + WORD_SECONDS))
        after_start.append(row["atMs"] / 1000 - known[0]["start"])

print("seconds:")
describe("word ended -> first read holds it", to_read)
describe("first read -> settled, on screen", to_line)
describe("on screen -> subtitle closes", to_close)
describe("translating", translate)
describe("last word ended -> Chinese", after_end)
describe("first word began -> Chinese", after_start)
describe("reading takes", [r["tookMs"] / 1000 for r in reads])
print("words per subtitle:", statistics.median(lengths) if lengths else None, " subtitles:", len(lengths), " words left unmatched:", len(spoken) - position)
