"""Rebuilds composeApp/src/sharedFonts, which stays in Git so normal builds never run this.

Cuts the bundled Song-style font down to regular weight and common Simplified Chinese. Needs fonttools."""
import hashlib
from pathlib import Path
import sys
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

SOURCE = Path(r"C:\Windows\Fonts\NotoSerifSC-VF.ttf")
SOURCE_SHA256 = "a4aed9985a5916fbf6690456f8732a9fccd517938e353165d4142b4f11a39280"
TARGET = Path(__file__).resolve().parents[1] / "composeApp" / "src" / "sharedFonts" / "font" / "noto_serif_sc.ttf"


def characters():
    # Characters outside this set fall back to the system font, which suits a rare name in a subtitle.
    chars = {chr(c) for c in range(0x20, 0x7F)} | {chr(c) for c in range(0x2010, 0x2027)}
    chars |= {chr(c) for c in range(0x3000, 0x3040)} | {chr(c) for c in range(0xFF01, 0xFF5F)}
    for high in range(0xA1, 0xF8):
        for low in range(0xA1, 0xFF):
            try:
                chars.add(bytes([high, low]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    return "".join(sorted(chars))


if __name__ == "__main__":
    actual = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    if actual != SOURCE_SHA256:
        sys.exit(f"Unexpected source font: {actual}")
    font = instancer.instantiateVariableFont(TTFont(SOURCE), {"wght": 400})
    options = subset.Options(hinting=False, layout_features=["kern", "liga", "locl", "ccmp"], name_IDs=["*"], notdef_outline=True)
    subsetter = subset.Subsetter(options)
    subsetter.populate(text=characters())
    subsetter.subset(font)
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    font.save(TARGET)
    names = font["name"]
    for name_id in (0, 1, 13, 14):
        record = names.getDebugName(name_id)
        print(name_id, (record or "")[:150])
    print(actual)
    print(f"{TARGET.name}: {TARGET.stat().st_size:,} bytes, {len(font.getBestCmap()):,} characters")
