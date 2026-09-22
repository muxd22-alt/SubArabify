#!/data/data/com.termux/files/usr/bin/env bash
# SubArabify unified Jellyfin addon — Termux universal runner (v0.2.3-alpha)
#
# One backend URL powers the Android app, the Python client, and this addon.
# This script: installs the bits once, gets the Colab URL into config.json,
# then hands off to subarabify_jellyfin.py with your flags.
#
#   On an Android TV      → run inside Termux (the QR prints right on the TV)
#   On a phone            → pkg install termux , run here, or use the app
#
# Examples:
#   ./run-termux.sh --media ~/storage/movies --watch
#   ./run-termux.sh --qrcode
#   ./run-termux.sh --serve
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# 1) one-time deps
command -v python3 >/dev/null 2>&1 || pkg install -y python
command -v ffmpeg >/dev/null 2>&1 || pkg install -y ffmpeg
python3 -m pip install -q -r requirements.txt 2>/dev/null || python3 -m pip install -q requests qrcode

# 2) make sure config.json carries the backend URL (unless the user provided it)
NEEDS_URL=yes
for a in "$@"; do
  case "$a" in
    --url|--qrcode|--serve|--job) NEEDS_URL=no ;;
  esac
done
if [ "$NEEDS_URL" = "yes" ] && [ -z "${SUBARABIFY_COLAB_URL:-}" ]; then
  EXISTING="$(python3 -c "import json;print(json.load(open('config.json')).get('colab_url',''))" 2>/dev/null || true)"
  if [ -z "$EXISTING" ]; then
    printf 'Paste the Colab backend URL (notebook → PUBLIC URL): '
    read -r MYURL
    python3 - "$MYURL" <<'PY'
import json, sys
p = "config.json"
try:
    cfg = json.load(open(p))
except Exception:
    cfg = {}
cfg["colab_url"] = sys.argv[1].strip()
open(p, "w").write(json.dumps(cfg, indent=2) + "\n")
print("saved config.json — " + cfg["colab_url"])
PY
  fi
fi

exec python3 subarabify_jellyfin.py "$@"