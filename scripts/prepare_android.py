"""Download the pinned official Android sherpa-onnx runtime for local development."""
from pathlib import Path
import json
from prepare_windows import fetch, ROOT

ASSET = {
    "name": "sherpa-onnx-1.12.17.aar",
    "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.17/sherpa-onnx-1.12.17.aar",
    "sha256": "96853e39b5b93f90c7d161cace8eb0898339c7dd6017c9f92975cb330e9d66fd",
}

if __name__ == "__main__":
    fetch(ASSET["url"], ROOT / ".runtime" / "android" / ASSET["name"], ASSET["sha256"])
    (ROOT / "android-dependencies.lock.json").write_text(json.dumps({"sherpa": "1.12.17", "assets": [ASSET]}, indent=2) + "\n", encoding="utf-8")
    print("Android runtime ready. Models are reused from the existing models/ directory.")
