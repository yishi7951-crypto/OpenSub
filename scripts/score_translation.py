"""Scores hy-translate on docs/evaluation/translation-cases.tsv, with and without the sentence said before.

Each case lists what its Chinese must and must not contain: a check for hard mistakes such as the wrong subject or
a wrong word sense, not a measure of style. Vocatives are also tried at the front of the sentence.
"""
import argparse
import re
import subprocess
import time
from pathlib import Path

VOCATIVE = re.compile(r", ((?:Mr\.?|Mister|mister|Mrs\.?|Madam) [A-Z]\w+|Sir|[Ll]adies and gentlemen|[Mm]y friends|[Mm]y fellow \w+),")


def front_vocative(english):
    """The same rule as vocativeFirst in SentenceTranslator.kt."""
    found = VOCATIVE.search(english)
    if not found:
        return english
    return f"{found[1][0].upper()}{found[1][1:]}, {english[:found.start()]}{english[found.end():]}"


def ask(process, english, before):
    process.stdin.write(f"{english}\t\t{before}\n".encode())
    process.stdin.flush()
    return process.stdout.readline().decode("utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--cases", type=Path, default=Path("docs/evaluation/translation-cases.tsv"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = [line.split("\t") for line in args.cases.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")]
    process = subprocess.Popen([args.exe, args.model, "8"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    modes = {"plain": (False, False), "before": (True, False), "vocative": (False, True), "both": (True, True)}
    lines = []
    for name, (use_before, move) in modes.items():
        passed, seconds = 0, 0.0
        for fields in cases:
            before, english, must, must_not = (fields + [""])[:4]
            mark = time.perf_counter()
            chinese = ask(process, front_vocative(english) if move else english, before if use_before else "")
            seconds += time.perf_counter() - mark
            good = re.search(must, chinese) is not None and not (must_not and re.search(must_not, chinese))
            passed += good
            lines.append(f"{name}\t{'ok' if good else 'BAD'}\t{english[:50]}\t{chinese}")
        lines.append(f"== {name}: {passed}/{len(cases)} passed, {seconds / len(cases):.2f}s each")
    process.stdin.close()
    process.wait(timeout=10)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(line for line in lines if line.startswith("==")))


if __name__ == "__main__":
    main()
