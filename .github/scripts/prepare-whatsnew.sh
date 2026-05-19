#!/usr/bin/env bash
# Writes Play Console whatsnew files from CHANGELOG.md for the given version.
set -euo pipefail

VERSION="${1:?usage: prepare-whatsnew.sh <X.Y.Z>}"
OUT_DIR="${2:-distribution/whatsnew}"

mkdir -p "$OUT_DIR"

awk -v ver="$VERSION" '
BEGIN { hdr = "^## \\[" ver "\\]" }
$0 ~ hdr { p=1; next }
p && $0 ~ /^## \[/ { exit }
p { print }
' CHANGELOG.md > "${OUT_DIR}/whatsnew-en-US"

if [ ! -s "${OUT_DIR}/whatsnew-en-US" ]; then
  printf 'See CHANGELOG.md for version %s.\n' "$VERSION" > "${OUT_DIR}/whatsnew-en-US"
fi

# Greek store listing (same notes until we split locales in CHANGELOG).
cp "${OUT_DIR}/whatsnew-en-US" "${OUT_DIR}/whatsnew-el-GR"

echo "Prepared whatsnew for $VERSION in $OUT_DIR"
