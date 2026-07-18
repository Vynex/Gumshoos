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
  # svgo first: it flattens shapes to paths and lifts style="" into attributes, which is what
  # prepare-svg.py then needs to see in order to normalise colours and fills.
  npx --yes svgo@latest --config svgo.config.mjs -i "$svg" -o "$TMP/$name.svgo.svg" >/dev/null
  python3 prepare-svg.py "$TMP/$name.svgo.svg" "$TMP/$name.svg"
  npx --yes svg2vectordrawable@latest -i "$TMP/$name.svg" -o "$OUT/$name.xml" >/dev/null
  echo "  $svg -> $OUT/$name.xml"
done
echo "Done. Check each drawable has as many <path> entries as the SVG had shapes."
