"""Compare recognitionReplay JSONL logs against a transcript of the exact audio excerpt."""
import argparse
import json
import re
from pathlib import Path


def words(text):
    return re.findall(r"[a-z]+(?:'[a-z]+)?", text.lower())


def error_rate(reference, actual):
    expected, heard = words(reference), words(actual)
    if not expected:
        raise ValueError("The reference transcript is empty")
    previous = list(range(len(heard) + 1))
    for i, word in enumerate(expected, 1):
        current = [i]
        for j, candidate in enumerate(heard, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (word != candidate)))
        previous = current
    return {"errors": previous[-1], "referenceWords": len(expected), "wer": previous[-1] / len(expected)}


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return None
    return {"p50": ordered[len(ordered) // 2], "p90": ordered[min(len(ordered) - 1, len(ordered) * 9 // 10)], "max": ordered[-1]}


def evaluate(path, reference):
    events = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    finals = [row for row in events if row["kind"] == "recognition" and row["final"]]
    displays = [row for row in events if row["kind"] == "display"]
    translations = [row for row in events if row["kind"] == "translation" and row["final"]]
    pages = {row["id"]: row["english"] for row in displays}
    final_text = " ".join(row["english"] for row in finals)
    displayed_text = " ".join(pages.values())
    history_text = " ".join(row["english"] for row in events if row["kind"] == "history")
    return {
        "log": path.name,
        "firstDisplayMs": displays[0]["atMs"] if displays else None,
        "finalSegments": len(finals),
        "displayPages": len(pages),
        "finalEnglish": error_rate(reference, final_text),
        "displayedEnglish": error_rate(reference, displayed_text),
        "historyEnglish": error_rate(reference, history_text),
        "englishFinalLagMs": distribution([max(0, row["atMs"] - row["endMs"]) for row in finals]),
        "chineseFinalLagMs": distribution([max(0, row["atMs"] - row["endMs"]) for row in translations]),
        "finalText": final_text,
        "displayedText": displayed_text,
        "historyText": history_text,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    reference = args.reference.read_text(encoding="utf-8").strip()
    report = {
        "method": "Case and punctuation ignored; edit distance includes substitutions, insertions and deletions. Display WER uses the last version of each page, not a word-by-word viewing-time score.",
        "before": evaluate(args.before, reference),
        "after": evaluate(args.after, reference),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name in ("before", "after"):
        row = report[name]
        print(f"{name}: firstDisplay={row['firstDisplayMs']}ms finalWER={row['finalEnglish']['wer']:.2%} displayWER={row['displayedEnglish']['wer']:.2%} pages={row['displayPages']} chineseLagMs={row['chineseFinalLagMs']}")


if __name__ == "__main__":
    main()
