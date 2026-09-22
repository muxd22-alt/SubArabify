#!/data/data/com.termux/files/usr/bin/bash
# SubArabify Jellyfin addon — Android Termux installer/runner (v1.0.4-pre)
set -e
echo "== SubArabify addon for Termux =="
pkg update -y || true
pkg install -y python git
pip install --upgrade pip
pip install -r "$(dirname "$0")/requirements.txt" || {
  echo "[warn] full requirements failed (torch is heavy on phones)."
  echo "[warn] installing light mode (glossary + memory, no neural MT)…"
  pip install transformers huggingface-hub requests || true
}
# first enable = model download happens here, before any movie
MEDIA_DIR="${1:-$HOME/storage/movies}"
mkdir -p "$MEDIA_DIR"
echo "media dir: $MEDIA_DIR"
python "$(dirname "$0")/subarabify_jellyfin.py" --media "$MEDIA_DIR" --once
echo ""
echo "Single scan done. For continuous watching run:"
echo "  python $(dirname "$0")/subarabify_jellyfin.py --media \"$MEDIA_DIR\" --watch"
