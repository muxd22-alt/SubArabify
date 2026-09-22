#!/usr/bin/env python3
"""Generate the Jellyfin repository manifest with the real zip checksum.

Reads jellyfin-addon/manifest.json (template), hashes the packaged addon zip,
and writes public/jellyfin-manifest.json next to the Pages site.

Usage (from the repo root, as CI does):
    python3 jellyfin-addon/make_manifest.py public/subarabify-jellyfin-addon-0.2.3-alpha.zip
"""
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEMPLATE = HERE / "manifest.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main(argv=None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    if len(argv) < 1:
        print("usage: python3 jellyfin-addon/make_manifest.py <addon.zip>", file=sys.stderr)
        return 1
    zip_path = Path(argv[0])
    if not zip_path.exists():
        print(f"zip not found: {zip_path}", file=sys.stderr)
        return 1
    out_path = Path(argv[1]) if len(argv) > 1 else Path("public") / "jellyfin-manifest.json"

    manifest = json.loads(TEMPLATE.read_text(encoding="utf-8"))
    digest = sha256(zip_path)
    for art in manifest.get("artifacts", []):
        art["checksum"] = digest
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"jellyfin-manifest.json sha256: {digest}")
    print(f"wrote {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())