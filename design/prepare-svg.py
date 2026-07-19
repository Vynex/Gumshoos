#!/usr/bin/env python3
"""Normalise an SVG into the subset svg2vectordrawable converts correctly.

Four fixes, each for something the converter gets silently wrong.

0. A viewBox with a non-zero origin is copied to viewportWidth/Height with the origin thrown away,
   so every coordinate is off by it. Material Symbols icons all use "0 -960 960 960", which puts
   the whole glyph one viewport above the canvas: the drawable builds, and renders empty. The brand
   marks never hit this because they all start at "0 0". Fixed by folding the origin into a
   translate group, which the converter does carry across as <group android:translateX/Y>.

1. gradientTransform is dropped, because Android vector drawables have no equivalent, so the
   gradient renders in the wrong place. On the Google mark that removed the green entirely: 19.2%
   of pixels differed from the source. Folding the matrix into the coordinates brings it to 3.3%.
   Linear gradients come out exact — transforming both endpoints is lossless. Radial ones are
   approximated, because unequal axis scales or skew make the gradient elliptical and a single
   centre plus radius cannot express that; the shorter semi-axis is used, which is the direction
   the colour visibly changes over.

2. CSS named colours are not parsed, and fall back to black. YouTube shipped as a black rectangle
   because its source said fill:red.

3. A path with no fill is black in SVG but transparent in Android. Spotify lost its waves entirely
   that way — they had no fill attribute, so they simply stopped being drawn.


Usage: prepare-svg.py <in.svg> <out.svg>
"""
import math
import re
import sys

GRADIENT = re.compile(r'<(radial|linear)Gradient[^>]*>')
MATRIX = re.compile(r'gradientTransform="matrix\(([^)]*)\)"')
OTHER_TRANSFORM = re.compile(r'gradientTransform="(?!matrix)([^"]*)"')

# Above this axis ratio a transformed radial gradient is matched to its shorter axis rather than to
# the area-preserving mean. See the radial branch in prepare() for why there is a threshold at all.
ELLIPSE_LIMIT = 2.0


# The names that actually turn up in brand artwork. Anything outside this set is reported rather
# than guessed at, since a wrong colour is far harder to notice than a missing one.
NAMED_COLOURS = {
    'black': '#000000', 'white': '#ffffff', 'red': '#ff0000', 'lime': '#00ff00',
    'blue': '#0000ff', 'yellow': '#ffff00', 'cyan': '#00ffff', 'aqua': '#00ffff',
    'magenta': '#ff00ff', 'fuchsia': '#ff00ff', 'silver': '#c0c0c0', 'gray': '#808080',
    'grey': '#808080', 'maroon': '#800000', 'olive': '#808000', 'green': '#008000',
    'purple': '#800080', 'teal': '#008080', 'navy': '#000080', 'orange': '#ffa500',
    'pink': '#ffc0cb', 'gold': '#ffd700', 'indigo': '#4b0082', 'violet': '#ee82ee',
    'crimson': '#dc143c', 'transparent': 'none', 'none': 'none',
}

PATH_TAG = re.compile(r'<path\b[^>]*/?>')
SVG_TAG = re.compile(r'<svg\b[^>]*>')
VIEWBOX = re.compile(r'\bviewBox="\s*([^"]*?)\s*"')
# Lowercase 'transform' only, so this never touches gradientTransform, which is folded into
# coordinates further down instead.
ELEMENT_MATRIX = re.compile(r'(?<![a-zA-Z])transform="matrix\(([^)]*)\)"')


def rewrite_element_matrices(svg_text, warnings):
    """Turn an element matrix() into translate() scale(), which the converter can express.

    Android's <group> has translateX/Y, scaleX/Y and rotation — but no matrix. svg2vectordrawable
    responds to one by emitting an attribute-less <group> and dropping the transform entirely: the
    Google mic shipped with its paths at x=-16535 on a 300-wide viewport, i.e. invisible, from a
    <g transform="matrix(25.8,0,0,-25.8,-16535,6380)">. svgo leaves that matrix alone because the
    group carries a clip-path, so nothing upstream folds it either.

    A matrix with no skew term is exactly translate(e,f) scale(a,d), and the orders agree: Android
    builds its group matrix as scale-then-translate, which is what SVG's "translate(...) scale(...)"
    means too. Anything with a skew or rotation term is reported rather than approximated.
    """
    def replace(match):
        a, b, c, d, e, f = numbers(match.group(1))
        if b or c:
            warnings.append(
                f"element transform has rotation or skew and cannot become an Android group: "
                f"matrix({match.group(1)}); artwork may be misplaced"
            )
            return match.group(0)
        return f'transform="translate({e:g},{f:g}) scale({a:g},{d:g})"'

    return ELEMENT_MATRIX.sub(replace, svg_text)


def normalise_viewbox(svg_text, warnings):
    """Move a non-zero viewBox origin into a translate group.

    Android's viewport always starts at (0,0) — there is no equivalent of the viewBox origin, and
    svg2vectordrawable drops it rather than compensating. Material Symbols export at
    "0 -960 960 960", so every coordinate is negative and the glyph lands entirely above the canvas:
    a drawable that builds, passes lint, and draws nothing.

    Wrapping the content in <g transform="translate(-minX,-minY)"> puts it back, and the converter
    does carry that across as <group android:translateX/Y>. Gradients travel inside the same group,
    so their coordinates stay in step with the paths that reference them.
    """
    svg_tag = SVG_TAG.search(svg_text)
    if not svg_tag:
        return svg_text

    box = VIEWBOX.search(svg_tag.group(0))
    if not box:
        return svg_text

    parts = numbers(box.group(1))
    if len(parts) != 4:
        warnings.append(f"viewBox is not four numbers: {box.group(1)!r}; left as-is")
        return svg_text

    min_x, min_y, width, height = parts
    if min_x == 0 and min_y == 0:
        return svg_text

    reset = VIEWBOX.sub(f'viewBox="0 0 {width:g} {height:g}"', svg_tag.group(0))
    body_start, body_end = svg_tag.end(), svg_text.rindex('</svg>')
    body = svg_text[body_start:body_end]

    warnings.append(
        f"viewBox origin ({min_x:g},{min_y:g}) folded into a translate group; "
        "Android has no viewBox origin and the converter drops it"
    )
    return (
        f'{reset}<g transform="translate({-min_x:g},{-min_y:g})">'
        f'{body}</g>{svg_text[body_end:]}'
    )


def normalise_colours(svg_text, warnings):
    """Replace CSS colour names with hex, since the converter reads unknown names as black."""
    def replace(match):
        attribute, value = match.group(1), match.group(2).strip()
        key = value.lower()
        if key in NAMED_COLOURS:
            return f'{attribute}="{NAMED_COLOURS[key]}"'
        if not (value.startswith('#') or value.startswith('url(') or
                value.startswith('rgb') or key == 'currentcolor'):
            warnings.append(f"unrecognised colour {attribute}={value!r}; left as-is")
        return match.group(0)

    return re.sub(r'\b(fill|stroke|stop-color)="([^"]*)"', replace, svg_text)


def make_fills_explicit(svg_text, warnings):
    """Give every path an explicit fill.

    SVG treats a missing fill as black; Android treats a missing fillColor as nothing at all, so
    the shape silently stops being drawn.
    """
    def replace(match):
        tag = match.group(0)
        if re.search(r'\bfill="', tag) or re.search(r'\bfill:', tag):
            return tag
        # A path carrying only a stroke is intentionally unfilled — leave it be.
        if re.search(r'\bstroke="(?!none)', tag):
            return tag
        warnings.append("path had no fill; defaulting to black as SVG does")
        return tag.replace('<path', '<path fill="#000000"', 1)

    return PATH_TAG.sub(replace, svg_text)


def numbers(text):
    return [float(n) for n in re.findall(r'-?\d*\.?\d+(?:e-?\d+)?', text)]


def attr(tag, name, default=None):
    found = re.search(rf'\b{name}="([^"]*)"', tag)
    return float(found.group(1)) if found else default


def prepare(svg_text):
    warnings = []
    # Origin first: it rewrites the <svg> tag and wraps the body, so the later passes see final text.
    result = normalise_viewbox(svg_text, warnings)
    result = rewrite_element_matrices(result, warnings)
    result = normalise_colours(result, warnings)
    result = make_fills_explicit(result, warnings)
    if 'clip-path' in result:
        # Android groups do support a clip-path, but the converter drops it. Usually the clip is a
        # bounding box and nothing changes; when it isn't, artwork that should be cut off is not.
        warnings.append("clip-path present; the converter drops it, so nothing will be clipped")
    svg_text = result

    for match in re.finditer(GRADIENT, svg_text):
        tag = match.group(0)
        if OTHER_TRANSFORM.search(tag):
            warnings.append(f"non-matrix gradientTransform left as-is: {tag[:80]}")
            continue
        matrix = MATRIX.search(tag)
        if not matrix:
            continue

        a, b, c, d, e, f = numbers(matrix.group(1))
        updated = tag

        if tag.startswith('<radialGradient'):
            cx, cy = attr(tag, 'cx', 0.0), attr(tag, 'cy', 0.0)
            radius = attr(tag, 'r', 0.0)
            new_cx, new_cy = a * cx + c * cy + e, b * cx + d * cy + f

            # The transformed circle is an ellipse whose semi-axes are the matrix columns times r,
            # and Android can only express a circle — so one number stands in for both.
            #
            # sqrt(|det|) is the area-preserving mean and is what mildly stretched gradients want;
            # the Google mark measured 3.3% against its source that way. It fails badly once the
            # ellipse is extreme, because the mean is dragged up by the long axis until the ramp
            # runs off the canvas: the Gemini mark's axes differ 8:1, giving a radius of 1542 on a
            # 512 viewport, so the whole icon sat inside the first stop and rendered flat purple.
            #
            # Past ELLIPSE_LIMIT, match the shorter axis instead — the direction the colour visibly
            # changes over. Below it, keep the mean, so artwork already verified against its source
            # is not quietly re-rendered on a reasoning change nobody measured.
            axis_a = math.hypot(a, b)
            axis_b = math.hypot(c, d)
            longer, shorter = max(axis_a, axis_b), min(axis_a, axis_b)
            if shorter > 0 and longer > ELLIPSE_LIMIT * shorter:
                new_r = radius * shorter
                warnings.append(
                    f"radial gradient is elliptical ({longer / shorter:.1f}:1); Android has only a "
                    "circle, so it is matched along the shorter axis"
                )
            else:
                new_r = radius * math.sqrt(abs(a * d - b * c))
            updated = re.sub(r'\bcx="[^"]*"', f'cx="{new_cx:.4f}"', updated)
            updated = re.sub(r'\bcy="[^"]*"', f'cy="{new_cy:.4f}"', updated)
            updated = re.sub(r'\br="[^"]*"', f'r="{new_r:.4f}"', updated)
        else:
            for x_name, y_name in (('x1', 'y1'), ('x2', 'y2')):
                x, y = attr(tag, x_name, 0.0), attr(tag, y_name, 0.0)
                updated = re.sub(rf'\b{x_name}="[^"]*"',
                                 f'{x_name}="{a * x + c * y + e:.4f}"', updated)
                updated = re.sub(rf'\b{y_name}="[^"]*"',
                                 f'{y_name}="{b * x + d * y + f:.4f}"', updated)

        updated = re.sub(r'\s*gradientTransform="[^"]*"', '', updated)
        result = result.replace(tag, updated)

    return result, warnings


if __name__ == '__main__':
    source, destination = sys.argv[1], sys.argv[2]
    prepared, warnings = prepare(open(source).read())
    open(destination, 'w').write(prepared)
    for warning in warnings:
        print(f"    WARNING {warning}", file=sys.stderr)
