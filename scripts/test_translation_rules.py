"""Translates each candidate rule's example with and without the rule as a reference, to keep only rules that fix something.

Usage: python scripts/test_translation_rules.py candidates.txt report.txt
Each candidate line is written like SubtitleRules.kt: english = 中文 # example. Close OpenSub first: both use the translator.
"""
import os, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NL = chr(10)
process = subprocess.Popen([os.path.join(ROOT, ".runtime", "hy-mt", "hy-translate.exe"),
                            os.path.join(ROOT, "models", "hy-mt2-1.8b-1.25bit", "Hy-MT2-1.8B-1.25Bit.gguf"), "8"],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

def ask(first):
    # A request is one line: a whole prompt after \x01 writes its line breaks as backslash n.
    process.stdin.write((first.replace(NL, chr(92) + "n") + "\t\t" + NL).encode("utf8"))
    process.stdin.flush()
    return process.stdout.readline().decode("utf8").rstrip("\r\n")

DEFAULT = "Translate the following text into Chinese. Note that you should only output the translated result without any additional explanation:" + NL + NL
ask("Hello.")
out = open(sys.argv[2], "w", encoding="utf8")
plain_seconds = hinted_seconds = 0.0
count = 0
for line in open(sys.argv[1], encoding="utf8"):
    line = line.strip()
    if not line or line.startswith("//"):
        continue
    english, rest = [part.strip() for part in line.split("=", 1)]
    chinese, example = [part.strip() for part in rest.split("#", 1)]
    phrase = english.replace("{", "").replace("}", "")
    start = time.perf_counter()
    plain = ask(example)
    middle = time.perf_counter()
    hinted = ask(chr(1) + "Reference the following translations:" + NL + f"{phrase} translates to {chinese}" + NL + NL + DEFAULT + example)
    plain_seconds += middle - start
    hinted_seconds += time.perf_counter() - middle
    count += 1
    out.write(f"{phrase} = {chinese}\n  {example}\n  无规则 {plain}\n  有规则 {hinted}\n\n")
    out.flush()
summary = f"{count} rules; average plain {plain_seconds / count * 1000:.0f}ms, with the rule {hinted_seconds / count * 1000:.0f}ms"
out.write(summary + "\n")
print(summary)
