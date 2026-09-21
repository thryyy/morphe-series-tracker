#!/usr/bin/env python3
"""Fetch checksum-pinned public build inputs without GitHub Packages credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def fetch(item, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if digest(destination) != item["sha256"]:
            raise SystemExit(f"Checksum mismatch: {destination}; inspect/remove it before retrying")
        return
    temporary = destination.with_suffix(destination.suffix + ".partial")
    subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3",
                    item["url"], "--output", str(temporary)], check=True)
    if digest(temporary) != item["sha256"]:
        temporary.unlink()
        raise SystemExit(f"Downloaded checksum mismatch: {item['name']}")
    temporary.replace(destination)
    print(f"Verified {item['name']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--patch-tools", action="store_true", help="Also fetch official patches and Desktop CLI")
    args = parser.parse_args()
    lock = json.loads((ROOT / "config/toolchain-lock.json").read_text())
    for item in lock["sources"]:
        archive = LOCAL / "archives" / (item["name"] + ".tar.gz")
        fetch(item, archive)
        destination = LOCAL / "upstream" / item["name"]
        marker = destination / ".series-tracker-source"
        if destination.exists():
            if not marker.is_file() or marker.read_text().strip() != item["sha256"]:
                raise SystemExit(f"Unrecognized source directory: {destination}; move it aside before bootstrapping")
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
            extracted = Path(temporary) / "source"
            extracted.mkdir()
            # Extraction only happens after the exact trusted archive checksum passes.
            subprocess.run(["tar", "-xzf", str(archive), "--strip-components=1", "-C", str(extracted)], check=True)
            (extracted / marker.name).write_text(item["sha256"] + "\n")
            extracted.rename(destination)
    for item in lock["artifacts"]:
        if item["purpose"] == "build" or args.patch_tools:
            fetch(item, LOCAL / "dependencies" / item["name"])
    for archive in (LOCAL / "dependencies").glob("morphe-extensions-library-*.aar"):
        with ZipFile(archive) as source:
            archive.with_suffix(".jar").write_bytes(source.read("classes.jar"))
    print("Pinned public toolchain ready in .local/")


if __name__ == "__main__":
    main()
