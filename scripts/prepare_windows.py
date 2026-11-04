"""Developer-only downloads from official upstreams. The application never calls this script."""
from __future__ import annotations
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SHERPA = "1.12.17"
GRADLE = "8.14.3"
MODEL = "sherpa-onnx-moonshine-tiny-en-int8"
CACHE = ROOT / ".tools" / "downloads"
CACHE.mkdir(parents=True, exist_ok=True)

def fetch(url: str, dest: Path, checksum: str | None = None) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and (not checksum or hashlib.sha256(dest.read_bytes()).hexdigest() == checksum):
        print(f"Ready: {dest.name}", flush=True)
        return dest
    print(f"Downloading: {dest.name}", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "OpenSub-build/0.3"})
    temp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(req, timeout=120) as response, temp.open("wb") as f:
        shutil.copyfileobj(response, f, 1024 * 1024)
    actual = hashlib.sha256(temp.read_bytes()).hexdigest()
    if checksum and actual != checksum:
        temp.unlink()
        raise RuntimeError(f"SHA-256 mismatch: {dest.name}")
    temp.replace(dest)
    print(f"Downloaded: {dest.name} ({dest.stat().st_size:,} bytes)", flush=True)
    return dest

def release(tag: str) -> dict:
    req = urllib.request.Request(f"https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/{tag}", headers={"User-Agent": "OpenSub-build/0.3"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return {a["name"]: a for a in json.load(response)["assets"]}

def safe_extract(archive: Path, target: Path):
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        tar.extractall(target, filter="data")

def main():
    lock_path = ROOT / "dependencies.lock.json"
    if lock_path.exists():
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    else:
        native = release("v" + SHERPA)
        models = release("asr-models")
        lock = {"sherpa": SHERPA, "gradle": GRADLE, "assets": []}
        for asset in [native[f"sherpa-onnx-v{SHERPA}-win-x64-jni.tar.bz2"], models[MODEL + ".tar.bz2"], models["silero_vad.onnx"]]:
            lock["assets"].append({"name": asset["name"], "url": asset["browser_download_url"], "sha256": (asset.get("digest") or "").removeprefix("sha256:")})
        with urllib.request.urlopen(f"https://services.gradle.org/distributions/gradle-{GRADLE}-bin.zip.sha256", timeout=60) as r:
            gradle_sha = r.read().decode().strip()
        lock["assets"].append({"name": f"gradle-{GRADLE}-bin.zip", "url": f"https://services.gradle.org/distributions/gradle-{GRADLE}-bin.zip", "sha256": gradle_sha})
    api_name = f"sherpa-onnx-v{SHERPA}.jar"
    if not any(a["name"] == api_name for a in lock["assets"]):
        asset = release("v" + SHERPA)[api_name]
        lock["assets"].append({"name": api_name, "url": asset["browser_download_url"], "sha256": (asset.get("digest") or "").removeprefix("sha256:")})
    def download(a):
        p = fetch(a["url"], CACHE / a["name"], a["sha256"] or None)
        # Record verified local hashes even for older upstream assets without GitHub digest.
        a["sha256"] = hashlib.sha256(p.read_bytes()).hexdigest()
        return p
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        files = list(pool.map(download, lock["assets"]))
    lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    safe_extract(files[0], ROOT / ".runtime" / "sherpa")
    safe_extract(files[1], ROOT / "models")
    shutil.copy2(files[2], ROOT / "models" / "silero_vad.onnx")
    shutil.copy2(files[4], ROOT / ".runtime" / "sherpa" / api_name)
    if not (ROOT / ".tools" / f"gradle-{GRADLE}" / "bin" / "gradle.bat").exists():
        with zipfile.ZipFile(files[3]) as z:
            z.extractall(ROOT / ".tools")
    for name in ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar"]:
        fetch(f"https://raw.githubusercontent.com/gradle/gradle/v{GRADLE}/{name}", ROOT / name)
    props = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
    content = props.read_text(encoding="utf-8")
    if "distributionSha256Sum=" not in content:
        props.write_text(content + "distributionSha256Sum=" + lock["assets"][3]["sha256"] + "\n", encoding="utf-8")
    print("Windows dependencies ready. No network is needed by the app.", flush=True)

if __name__ == "__main__":
    main()
