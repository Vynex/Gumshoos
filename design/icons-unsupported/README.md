# Artwork that cannot be a vector drawable

Source SVGs kept here **on purpose**, out of `../icons/`, because `convert-icons.sh` globs
`icons/*.svg` and would convert them on its next run — overwriting a correct drawable with a broken
one, silently, in a build that succeeds and passes lint. That has happened before (see the
hand-authored outline icons in CLAUDE.md); this folder is what stops it happening again.

Nothing in here is read by any script. It is the record of *why* a drawable is a raster.

## Gemini

`gemini-296px.svg` is the current mark, `ic_provider_gemini.svg` an earlier 65px export of the same
idea. Both are an alpha mask over Gaussian-blurred colour blobs — seven in the current one, eleven
in the older.

Android vector drawables have **no filters, no blur and no masks**, and the converter drops
`clip-path` as well. There is no preprocessing fix: the format simply cannot express this artwork.
Converting it anyway produced hard-edged flat colour patches clipped to the star — it built, it
passed lint, and it looked broken.

So it ships as a raster: `app/src/main/res/drawable-nodpi/ic_provider_gemini.webp`.

- **`drawable-nodpi/`, not `drawable/`.** A bitmap in plain `res/drawable/` is treated as mdpi and
  upscaled about fourfold at decode.
- **192px.** The largest draw site is 28dp (`MissingAppRow` on the widget bar settings screen); the
  widget itself draws 22dp. That is ~112px on a 4x device, so 192 keeps useful headroom without
  paying for pixels nothing asks for.
- **The resource name is unchanged**, so `R.drawable.ic_provider_gemini` still resolves and no code
  moved. Its only reference is `WidgetActions.kt`.
- **Nothing tints it** — Gemini is `tintable = false`, which is the one thing that would have argued
  for keeping a vector. The widget reaches it through `rasterizeIcon`, which fits on
  `intrinsicWidth`/`intrinsicHeight` and so handles a bitmap exactly as it did the vector.

Replacing it means dropping a new file at that path under that name. Don't put the SVG back in
`../icons/`.

## Google Calendar

`google-calendar-192px.svg`, shipping as
`app/src/main/res/drawable-nodpi/ic_provider_google_calendar.webp` for the same reasons and at the
same size. It carries **two alpha masks and a Gaussian-blurred highlight** over two linear
gradients.

This one is worth knowing about because it did **not** fail loudly. The pipeline converted it
without complaint and produced a plausible-looking drawable — five paths, both gradients, the masks
turned into clip-paths — with the blur simply absent, since a vector drawable has no filters. A
drawable that converts *most* of the artwork is more dangerous than one that converts none of it:
there is nothing in the build output to react to.

It replaced a Lucide outline calendar, which converted perfectly and is gone. If a plain glyph is
ever wanted again, that is the cheaper route by a long way — `../icons/` is where it would go.
