#!/usr/bin/env bash
# Converts the source SVGs in design/icons/ into Android vector drawables.
#
# Three stages, and the first two are not optional. svg2vectordrawable only understands <path>: any
# <circle>, <rect>, <ellipse>, <polygon> or <line> is dropped silently, so artwork disappears with
# no error. svgo flattens those to paths first — with convertArcs enabled, which is off by default
# and is what covers <circle> and <ellipse>.
set -euo pipefail

cd "$(dirname "$0")"
SRC="icons"
OUT="../app/src/main/res/drawable"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

shopt -s nullglob
for svg in "$SRC"/*.svg; do
  name="$(basename "${svg%.svg}")"
  python3 bake-gradient-transforms.py "$svg" "$TMP/$name.baked.svg"
  npx --yes svgo@latest --config svgo.config.mjs -i "$TMP/$name.baked.svg" -o "$TMP/$name.svg" >/dev/null
  npx --yes svg2vectordrawable@latest -i "$TMP/$name.svg" -o "$OUT/$name.xml" >/dev/null
  echo "  $svg -> $OUT/$name.xml"
done
echo "Done. Check each drawable has as many <path> entries as the SVG had shapes."
