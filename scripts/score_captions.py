"""Scores a recognitionReplay log as a viewer would judge it, so a change is judged on everything at once.

    python scripts/score_captions.py --clip kennedy_b --reference docs/evaluation/kennedy.txt log.jsonl

keeping up   how far the English and the Chinese on screen are behind the voice, sampled while someone is speaking.
             Chinese wording differs from the English, so a share of a sentence's final Chinese stands for the same share
             of its English. A sentence with no Chinese yet counts as covering nothing of itself.
steady       Chinese taken back off the screen within a sentence, per 100 characters of final Chinese, how many times a sentence's
             text was rewritten rather than added to, and numbers that showed in a draft but are not in the final text.
right        hard facts from docs/evaluation/caption-checks.tsv: numbers, negations and names in the final Chinese.
complete     final sentences with no Chinese or too little of it, and the model's own instructions on screen.
sentences    final sentences that really end where the speech text does, both ways, and lines left hanging on a small word.
"""
import argparse
import difflib
import json
import re
from collections import defaultdict
from pathlib import Path

from score_transcript import words

STEP_MS = 200
NUMBER = re.compile(r"[0-9,]*[0-9]|[一二两三四五六七八九十百千万亿]{2,}")
LEAK = re.compile(r"只应?输出|翻译结果|添加任何|任何(解释|注释)")
FEWEST_CHINESE_PER_LETTER = 0.2
HANGING = {"the", "a", "an", "of", "to", "and", "or", "in", "on", "at", "by", "for", "with", "from", "that", "which", "who", "as", "if", "than", "not", "is", "are",
           "was", "were", "be", "before", "after", "our", "my", "your", "their", "this", "these", "those", "its", "his", "her", "but", "so", "because", "will", "would"}


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * share))] if ordered else 0


class Sentence:
    def __init__(self):
        self.heard = []  # (ms, characters of English so far)
        self.shown = []  # (ms, text)
        self.start = None
        self.final_english = ""
        self.final_chinese = ""


def load(path):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf8").splitlines() if line.strip()]
    sentences = defaultdict(Sentence)
    for row in rows:
        if row["kind"] != "translation":
            continue
        sentence = sentences[row["id"]]
        sentence.start = min(row["startMs"], sentence.start or row["startMs"])
        sentence.heard.append((row["endMs"], len(row["sentenceEnglish"])))
        sentence.shown.append((row["atMs"], row["sentenceChinese"]))
        sentence.final_english = row["sentenceEnglish"]
        sentence.final_chinese = row["sentenceChinese"]
    return rows, [sentences[key] for key in sorted(sentences)]


def time_of(sentence, characters):
    """When the voice had reached this far into the sentence."""
    before = (sentence.start, 0)
    for ms, length in sentence.heard:
        if length >= characters:
            span = max(1, length - before[1])
            return before[0] + (ms - before[0]) * (characters - before[1]) / span
        before = (ms, length)
    return before[0]


def chinese_lag(sentences):
    """Samples of how far behind the voice the Chinese is."""
    lags = []
    for index, sentence in enumerate(sentences):
        end = sentence.heard[-1][0]
        before_end = sentences[index - 1].heard[-1][0] if index else sentence.start
        whole = max(1, len(sentence.final_chinese))
        for now in range(int(sentence.start), int(end), STEP_MS):
            text = ""
            for ms, shown in sentence.shown:
                if ms <= now:
                    text = shown
            reached = time_of(sentence, len(sentence.final_english) * min(1, len(text) / whole)) if text else before_end
            lags.append(max(0, now - reached))
    return lags


def english_lag(rows):
    """The screen's English runs up to the newest word shown, and the voice is a little ahead of it."""
    shown = [(r["atMs"], r["endMs"]) for r in rows if r["kind"] == "display"]
    lags = []
    for (at, end), (next_at, _) in zip(shown, shown[1:]):
        lags += [now - end for now in range(int(at), int(next_at), STEP_MS) if now - end < 10_000]
    return [max(0, lag) for lag in lags]


def steadiness(sentences):
    erased, rewrites, characters, flashes = 0, 0, 0, 0
    for sentence in sentences:
        previous = ""
        for _, text in sentence.shown:
            kept = len(commonprefix(previous, text))
            erased += len(previous) - kept
            rewrites += kept < len(previous)
            previous = text
        characters += len(sentence.final_chinese)
        final_numbers = set(NUMBER.findall(sentence.final_chinese))
        flashes += len({n for _, text in sentence.shown for n in NUMBER.findall(text)} - final_numbers)
    return 100 * erased / max(1, characters), rewrites / max(1, len(sentences)), flashes


def commonprefix(first, second):
    count = 0
    while count < min(len(first), len(second)) and first[count] == second[count]:
        count += 1
    return first[:count]


def facts(clip, chinese, checks):
    passed, failed = 0, []
    for line in Path(checks).read_text(encoding="utf8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, what, must, must_not = (line.split("\t") + ["", ""])[:4]
        if name != clip:
            continue
        good = re.search(must, chinese) is not None and not (must_not and re.search(must_not, chinese))
        passed += good
        if not good:
            failed.append(what)
    return passed, passed + len(failed), failed


def sentence_ends(reference_text, finals):
    """Compare where final sentences end with where the speech text ends them."""
    reference, truth = [], set()
    for part in re.split(r"(?<=[.?!:])\s+", reference_text):
        reference += words(part)
        truth.add(len(reference))
    heard, said = [], set()
    for text in finals:
        heard += words(text)
        if text.rstrip()[-1:] in ".?!":
            said.add(len(heard))
    matcher = difflib.SequenceMatcher(None, reference, heard, autojunk=False)
    blocks = matcher.get_matching_blocks()
    anchor = max(blocks, key=lambda block: block.size)
    if anchor.size < 3:
        return 0, 0
    # Only the part of the speech this clip covers: stray short matches elsewhere in the text say nothing.
    first, last = anchor.a - anchor.b, anchor.a - anchor.b + len(heard)
    to_reference = {}
    for block in blocks:
        for offset in range(1, block.size + 1):
            to_reference[block.b + offset] = block.a + offset
    mapped = {to_reference[end] for end in said if end in to_reference}
    expected = {end for end in truth if first < end < last}
    hits = len(mapped & expected)
    return hits / max(1, len(mapped)), hits / max(1, len(expected))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--clip", required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--checks", type=Path, default=Path("docs/evaluation/caption-checks.tsv"))
    parser.add_argument("--show", action="store_true", help="also list the final sentences and failed checks")
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    rows, sentences = load(args.log)
    finals = [s.final_english for s in sentences]
    chinese_lags = chinese_lag(sentences)
    english = english_lag(rows)
    erased, rewrites, flashes = steadiness(sentences)
    chinese = "".join(s.final_chinese for s in sentences)
    passed, total, failed = facts(args.clip, chinese, args.checks)
    leaks = sum(1 for r in rows if r["kind"] == "translation" and LEAK.search(r["sentenceChinese"]))
    missing = sum(1 for s in sentences if len(s.final_chinese) < len(re.sub(r"\W", "", s.final_english)) * FEWEST_CHINESE_PER_LETTER)
    precision, recall = sentence_ends(args.reference.read_text(encoding="utf8"), finals)
    lines = list({r["id"]: r["english"] for r in rows if r["kind"] == "display"}.values())
    hanging = sum(1 for line in lines if re.sub(r"\W", "", line.rsplit(" ", 1)[-1].lower()) in HANGING)
    print(f"{args.clip}: english p50 {percentile(english, .5) / 1000:.1f}s p90 {percentile(english, .9) / 1000:.1f}s"
          f" | chinese p50 {percentile(chinese_lags, .5) / 1000:.1f}s p90 {percentile(chinese_lags, .9) / 1000:.1f}s"
          f" | erased {erased:.0f}/100, {rewrites:.1f} rewrites a sentence, {flashes} wrong numbers shown"
          f" | facts {passed}/{total} | missing {missing}/{len(sentences)} leaks {leaks}"
          f" | sentence ends right {precision:.0%} found {recall:.0%}, hanging lines {hanging}/{len(lines)}")
    if args.show:
        for name in failed:
            print("  failed:", name)
        for sentence in sentences:
            print(f"  {sentence.final_english}\n    {sentence.final_chinese}")


if __name__ == "__main__":
    main()
