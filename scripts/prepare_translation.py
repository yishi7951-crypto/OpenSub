"""Download the pinned English-to-Chinese models for local development: Hy-MT2 for Windows, OPUS-MT for Android, Firefox's for comparison."""
import hashlib
import json
import struct
from pathlib import Path
from prepare_windows import fetch, ROOT

REVISION = "046f55aec303cdee3e0318604406d4df20f1e8ea"
BASE = f"https://huggingface.co/Xenova/opus-mt-en-zh/resolve/{REVISION}/"
TARGET = ROOT / "models" / "opus-mt-en-zh-int8"
ASSETS = {
    "onnx/encoder_model_quantized.onnx": "d3b7912bf6a9bd27e4c074c2df91d4ff3d5b4bc5f7f6c8d7cc9c805c98fbafee",
    "onnx/decoder_model_merged_quantized.onnx": "023be4f841f4c47cd65fffcbaa81c0d99d7f7e0138f7ba0e03fa220a4e688aff",
    "source.spm": None,
    "vocab.json": None,
}
FIREFOX_REVISION = "551f0f6385f77d7d73082991c3b4c9af2dd7eed7"
FIREFOX_BASE = f"https://huggingface.co/marcosremar2/firefox-translations-ucast-onnx/resolve/{FIREFOX_REVISION}/"
FIREFOX_TARGET = ROOT / "models" / "firefox-en-zh-int8"
FIREFOX_ASSETS = {
    "en-zh/encoder_model.int8.onnx": "c17126cff9aef1d316b780f0beb5f8d45ac79aced0e4ce99e112e286619b2a0d",
    "en-zh/decoder_model_merged.int8.onnx": "2ef23c23e52739d009334af2678225b0908332619345f2ceeed75728e86abbf8",
    "en-zh/source.spm": "bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5",
    "en-zh/vocab.json": None,
    "en-zh/target_vocab.json": None,
    "LICENSE": None,
}
HY_REVISION = "9df5c824a00a744fb0512a29c640466f4d97dfb0"
HY_BASE = f"https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/{HY_REVISION}/"
HY_NAME = "Hy-MT2-1.8B-1.25Bit.gguf"
HY_SOURCE = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"
HY_READY = "f1e0adf9183536e42589650348305176c570be9e588fe6955bcdef82d7f66a01"
HY_TARGET = ROOT / "models" / "hy-mt2-1.8b-1.25bit"
GGUF_FIXED_SIZES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
GGUF_STRING = 8
GGUF_ARRAY = 9
STQ_PUBLISHED_TYPE = 42
STQ_LLAMA_TYPE = 43


def renumber_stq(source: Path, target: Path) -> None:
    """The model names its STQ1_0 tensors with type 42, but llama.cpp PR #22836 settled on 43. Only that number differs."""
    data = bytearray(source.read_bytes())
    tensors, entries = struct.unpack_from("<QQ", data, 8)
    position = 24

    def skip_string(at: int) -> int:
        return at + 8 + struct.unpack_from("<Q", data, at)[0]

    def skip_value(kind: int, at: int) -> int:
        if kind == GGUF_STRING:
            return skip_string(at)
        if kind == GGUF_ARRAY:
            inner, count = struct.unpack_from("<IQ", data, at)
            at += 12
            if inner != GGUF_STRING:
                return at + GGUF_FIXED_SIZES[inner] * count
            for _ in range(count):
                at = skip_string(at)
            return at
        return at + GGUF_FIXED_SIZES[kind]

    for _ in range(entries):
        position = skip_string(position)
        kind = struct.unpack_from("<I", data, position)[0]
        position = skip_value(kind, position + 4)
    for _ in range(tensors):
        position = skip_string(position)
        dimensions = struct.unpack_from("<I", data, position)[0]
        position += 4 + 8 * dimensions
        if struct.unpack_from("<I", data, position)[0] == STQ_PUBLISHED_TYPE:
            struct.pack_into("<I", data, position, STQ_LLAMA_TYPE)
        position += 12
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def prepare_hy() -> list[dict]:
    source = fetch(HY_BASE + HY_NAME, ROOT / ".tools" / "downloads" / HY_NAME, HY_SOURCE)
    license_file = fetch(HY_BASE + "LICENSE.txt", HY_TARGET / "LICENSE.txt")
    ready = HY_TARGET / HY_NAME
    if not ready.exists() or hashlib.sha256(ready.read_bytes()).hexdigest() != HY_READY:
        renumber_stq(source, ready)
    if hashlib.sha256(ready.read_bytes()).hexdigest() != HY_READY:
        raise RuntimeError(f"SHA-256 mismatch: {ready.name}")
    return [{"path": HY_NAME, "url": HY_BASE + HY_NAME, "sha256": HY_SOURCE},
            {"path": "LICENSE.txt", "url": HY_BASE + "LICENSE.txt", "sha256": hashlib.sha256(license_file.read_bytes()).hexdigest()}]


if __name__ == "__main__":
    lock_path = ROOT / "translation-dependencies.lock.json"
    pinned = {a["path"]: a["sha256"] for a in json.loads(lock_path.read_text(encoding="utf-8"))["assets"]} if lock_path.exists() else {}
    assets = []
    for base, target, checksums in ((BASE, TARGET, ASSETS), (FIREFOX_BASE, FIREFOX_TARGET, FIREFOX_ASSETS)):
        for path, checksum in checksums.items():
            dest = fetch(base + path, target / path.split("/")[-1], checksum or pinned.get(path))
            assets.append({"path": path, "url": base + path, "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()})
    assets += prepare_hy()
    lock = {"revision": REVISION, "firefoxRevision": FIREFOX_REVISION, "hyRevision": HY_REVISION, "assets": assets}
    lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print("Translation models ready. No network is needed by the app.")
