"""Fetch pinned official llama.cpp tools into this ignored workspace."""
import hashlib
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TAG = "b12377"


def fetch(url, path):
    if path.exists():
        return
    with urllib.request.urlopen(url, timeout=120) as response, path.open("wb") as output:
        while block := response.read(1024 * 1024):
            output.write(block)


def extract(archive, target):
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            resolved = (target / entry.filename).resolve()
            if not resolved.is_relative_to(target.resolve()):
                raise ValueError("Unsafe archive path")
        z.extractall(target)


def main():
    dest = ROOT / "tools" / f"llama-{TAG}"
    dest.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(f"https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/{TAG}", timeout=60) as response:
        release = json.load(response)
    asset = next(a for a in release["assets"] if a["name"] == f"llama-{TAG}-bin-win-cpu-x64.zip")
    binary = dest / asset["name"]
    fetch(asset["browser_download_url"], binary)
    assert binary.stat().st_size == asset["size"]
    digest = hashlib.sha256(binary.read_bytes()).hexdigest()
    if asset.get("digest"):
        assert asset["digest"] == "sha256:" + digest
    extract(binary, dest / "bin")
    source = dest / "source.zip"
    fetch(f"https://github.com/ggml-org/llama.cpp/archive/refs/tags/{TAG}.zip", source)
    extract(source, dest / "source")
    manifest = {"tag": TAG, "binary_url": asset["browser_download_url"], "binary_sha256": digest,
                "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
    (dest / "manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(manifest), flush=True)


if __name__ == "__main__":
    main()
