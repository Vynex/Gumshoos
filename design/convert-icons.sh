#!/usr/bin/env bash
# Converts the source SVGs in design/icons/ into Android vector drawables.
#
# Three stages, and the first two are not optional. svg2vectordrawable only understands <path>: any
# <circle>, <rect>, <ellipse>, <polygon> or <line> is dropped silently, so artwork disappears with
# no error. svgo flattens those to paths first — with convertArcs enabled, which is off by default
# and is what covers <circle> and <ellipse>.
#
# Filled brand logos and outline UI icons both go through it, and are told apart by what they are
# rather than by where they are filed: an outline icon carries currentColor, which prepare-svg.py
# resolves to white and reports back so the drawable can be given a matching android:tint. There is
# no list to keep up to date and nothing to file in the right folder — the tell is in the artwork.
set -euo pipefail

cd "$(dirname "$0")"
SRC="icons"
OUT="../app/src/main/res/drawable"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Two sweeps. The top level is one icon per purpose, already named as its resource; weather/ is a
# set keyed by OpenWeather's condition codes, kept together because they are meaningless apart and
# are chosen at runtime rather than referenced by name from anywhere.
#
# Lucide's filenames are hyphenated and a hyphen is not legal in a resource id — the build rejects
# it outright, which is at least loud. The prefix is added here rather than by renaming 11 files,
# so the folder still matches what Lucide hands you.
shopt -s nullglob
for svg in "$SRC"/*.svg "$SRC"/weather/*.svg; do
  case "$svg" in
    "$SRC"/weather/*) name="ic_weather_$(basename "${svg%.svg}" | tr '-' '_')" ;;
    *) name="$(basename "${svg%.svg}")" ;;
  esac
  # svgo first: it flattens shapes to paths and lifts style="" into attributes, which is what
  # prepare-svg.py then needs to see in order to normalise colours and fills.
  npx --yes svgo@latest --config svgo.config.mjs -i "$svg" -o "$TMP/$name.svgo.svg" >/dev/null
  # stdout says TINT when the source drew in currentColor; warnings go to stderr and stay visible.
  tint="$(python3 prepare-svg.py "$TMP/$name.svgo.svg" "$TMP/$name.svg")"
  npx --yes svg2vectordrawable@latest -i "$TMP/$name.svg" -o "$OUT/$name.xml" >/dev/null
  if [ "$tint" = "TINT" ]; then
    python3 prepare-svg.py --add-tint "$OUT/$name.xml"
  fi
  echo "  $svg -> $OUT/$name.xml${tint:+  (tinted)}"
done
echo "Done. Check each drawable has as many <path> entries as the SVG had shapes."
