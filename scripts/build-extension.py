#!/usr/bin/env python3
"""Builds the Chrome extension from extension/ into build/extension-chrome/ (Chrome, Edge, Brave, Vivaldi, Opera)."""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "extension"
OUTPUT = ROOT / "build" / "extension-chrome"
SKIP = {"helpers.test.js", "package.json"}


def main():
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    shutil.copytree(SOURCE, OUTPUT, ignore=lambda _, names: [n for n in names if n in SKIP])
    manifest = json.loads((SOURCE / "manifest.json").read_text(encoding="utf-8"))
    manifest["version"] = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Built the Chrome extension {manifest['version']} in {OUTPUT}")


if __name__ == "__main__":
    main()
