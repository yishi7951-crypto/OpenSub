"""Check the built APK, including components lost by a bad manifest merge."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def sha256_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(apk, aapt2, release=False):
    def dump(*args):
        return subprocess.run([str(aapt2), "dump", *args, str(apk)], check=True,
                              capture_output=True, encoding="utf-8").stdout

    def require(condition, message):
        if not condition:
            raise SystemExit("APK check failed: " + message)

    badging = dump("badging")
    manifest = dump("xmltree", "--file", "AndroidManifest.xml")
    require("name='com.opensub.app'" in badging, "wrong application ID")
    require("minSdkVersion:'29'" in badging, "minSdk must be 29")
    require("targetSdkVersion:'36'" in badging, "targetSdk must be 36")
    if release:
        require("application-debuggable" not in badging, "the release build must not be debuggable")
    else:
        require("application-debuggable" in badging, "expected the debug build")
    permissions = re.findall(r"uses-permission: name='([^']+)'", badging)
    required_permissions = {
        "android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.POST_NOTIFICATIONS", "android.permission.SYSTEM_ALERT_WINDOW",
    }
    require(required_permissions.issubset(permissions), "missing capture/overlay permission")
    require("android.permission.INTERNET" not in permissions, "runtime must stay offline")

    # Read the merged binary manifest, not the source manifests.
    blocks = re.split(r"(?=^\s*E: )", manifest, flags=re.MULTILINE)
    required_components = {
        "com.opensub.app.MainActivity": "activity",
        "com.opensub.app.capture.PlaybackCaptureService": "service",
    }
    if not release:
        required_components["com.opensub.app.testing.TestVideoActivity"] = "activity"
    for name, kind in required_components.items():
        block = next((b for b in blocks if b.lstrip().startswith("E: " + kind + " ")
                      and f'="{name}"' in b), None)
        require(block is not None, f"missing {kind}: {name}")
        if name.endswith("TestVideoActivity"):
            require(re.search(r":exported\([^)]*\)=false", block), "test player must be private")
        if kind == "service":
            require(re.search(r":foregroundServiceType\([^)]*\)=0x00000020", block),
                    "capture service must declare mediaProjection")

    model_dir = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8/"
    models = ["silero_vad.onnx"] + [model_dir + name for name in (
        "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")] + ["hy-mt2-1.8b-1.25bit-tq2/Hy-MT2-1.8B-1.25Bit-TQ2.gguf"]
    model_hashes = {}
    root = Path(__file__).resolve().parents[1]
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({n.split("/")[1] for n in archive.namelist() if n.startswith("lib/")})
        require(abis == ["arm64-v8a"], f"unexpected ABIs: {abis}")
        for name in models:
            asset = "assets/models/" + name
            require(asset in archive.namelist(), "missing bundled model asset: " + asset)
            with archive.open(asset) as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            require(actual == sha256_file(root / "models" / name), "stale model asset: " + name)
            model_hashes[name] = actual
        require("assets/qa/0.wav" in archive.namelist(), "missing offline self-test sample")
        for notice in ("LICENSE", "THIRD_PARTY.md", "Apache-2.0.txt", "Hy-MT2-NOTICE.txt", "Parakeet-CC-BY-4.0.txt", "OFL-1.1-NotoSerifSC.txt"):
            require("assets/licenses/" + notice in archive.namelist(), "missing license file: " + notice)
        require("lib/arm64-v8a/libhytranslate.so" in archive.namelist(), "missing the hy-translate program")
        require("lib/arm64-v8a/libhytranslate_base.so" in archive.namelist(), "missing hy-translate for chips without dotprod")
        # The app copies the model out on its first start, which takes seconds only when it is stored uncompressed.
        require("assets/models/hy-mt2-1.8b-1.25bit/Hy-MT2-1.8B-1.25Bit.gguf" not in archive.namelist(), "the tablet needs only the TQ2_0 model")
        info = archive.getinfo("assets/models/hy-mt2-1.8b-1.25bit-tq2/Hy-MT2-1.8B-1.25Bit-TQ2.gguf")
        require(info.compress_type == zipfile.ZIP_STORED, "the translation model must be stored uncompressed")
    require(apk.stat().st_size <= 1_000_000_000, "APK exceeds the 1 GB limit")
    return {"passed": True, "apk": apk.name, "sha256": sha256_file(apk),
            "bytes": apk.stat().st_size, "minSdk": 29, "targetSdk": 36,
            "abis": abis, "permissions": permissions, "components": required_components,
            "modelSha256": model_hashes}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    result = verify(args.apk, args.aapt2, args.release)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"APK checks passed: {result['bytes']:,} bytes; manifest, models, ABI and offline permissions OK")
