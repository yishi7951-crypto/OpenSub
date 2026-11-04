"""Scores sentences from the transcriptScore task against a full speech text.

A recording may be an excerpt, so each sentence is matched to its best place in the reference. Sentences that match
nowhere are reported apart instead of counted as errors. Case, punctuation and hyphens are ignored.
"""
import argparse
import difflib
import re
from collections import Counter
from pathlib import Path

NO_MATCH_SHARE = 0.6


SAME_WORDS = {"mister": "mr", "mr.": "mr", "okay": "ok"}
TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90}
SMALL = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
         "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19}


def spell_numbers(text):
    """The reference writes 20,000 and the model says twenty thousand: both become digits, so neither counts as a mistake."""
    text = re.sub(r"(?<=\d),(?=\d{3})", "", text.lower())
    text = re.sub(r"\b(" + "|".join(TENS) + r")[ -](" + "|".join(k for k, v in SMALL.items() if v < 10) + r")\b",
                  lambda m: str(TENS[m[1]] + SMALL[m[2]]), text)
    text = re.sub(r"\b(" + "|".join(list(TENS) + list(SMALL)) + r")\b", lambda m: str(TENS.get(m[1], SMALL.get(m[1]))), text)
    text = re.sub(r"\b(a|one|1) (hundred|thousand)\b", r"1 \2", text)
    text = re.sub(r"\b(\d+) hundred\b", lambda m: str(int(m[1]) * 100), text)
    text = re.sub(r"\b(\d+) thousand\b", lambda m: str(int(m[1]) * 1000), text)
    return text


def words(text):
    found = re.findall(r"[a-z0-9]+(?:'[a-z]+)?", spell_numbers(text).replace("-", ""))
    return [SAME_WORDS.get(word, word) for word in found]


def best_match(reference, heard):
    """Edit distance from heard to its best-matching stretch of reference, and where that stretch ends."""
    previous = [0] * (len(reference) + 1)
    for i, word in enumerate(heard, 1):
        current = [i]
        for j, candidate in enumerate(reference, 1):
            current.append(min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + (word != candidate)))
        previous = current
    errors = min(previous)
    return errors, previous.index(errors)


def mistakes(reference, heard):
    pairs = []
    matcher = difflib.SequenceMatcher(None, reference, heard, autojunk=False)
    # The stretch is a little too long or short when words were added or lost, so only the middle of it can be trusted.
    for op, a, b, c, d in matcher.get_opcodes():
        if op != "equal":
            pairs.append((" ".join(reference[a:b]), " ".join(heard[c:d])))
    return pairs


def score(reference, sentences):
    total, errors, skipped = 0, 0, 0
    found = Counter()
    for sentence in sentences:
        heard = words(sentence)
        if not heard:
            continue
        missed, end = best_match(reference, heard)
        if missed > len(heard) * NO_MATCH_SHARE:
            skipped += len(heard)
            continue
        total += len(heard)
        errors += missed
        found.update(mistakes(reference[max(0, end - len(heard)):end], heard))
    return total, errors, skipped, found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--show", type=int, default=0, help="how many mistakes to list")
    parser.add_argument("texts", type=Path, nargs="+")
    args = parser.parse_args()
    reference = words(args.reference.read_text(encoding="utf-8"))
    for path in args.texts:
        sentences = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        total, errors, skipped, found = score(reference, sentences)
        print(f"{path.stem}: errors={errors}/{total} ({errors / max(1, total):.2%}) sentences={len(sentences)} unmatchedWords={skipped}")
        for (expected, actual), count in found.most_common(args.show):
            print(f"  {count}x {expected!r} -> {actual!r}")


if __name__ == "__main__":
    main()
