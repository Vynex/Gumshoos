#!/usr/bin/env python3
"""Normalise an SVG into the subset svg2vectordrawable converts correctly.

Three fixes, each for something the converter gets silently wrong.

1. gradientTransform is dropped, because Android vector drawables have no equivalent, so the
   gradient renders in the wrong place. On the Google mark that removed the green entirely: 19.2%
   of pixels differed from the source. Folding the matrix into the coordinates brings it to 3.3%.
   Linear gradients come out exact — transforming both endpoints is lossless. Radial ones are
   approximated, because unequal axis scales or skew make the gradient elliptical and a single
   centre plus radius cannot express that; sqrt(|det|) is the area-preserving compromise.

2. CSS named colours are not parsed, and fall back to black. YouTube shipped as a black rectangle
   because its source said fill:red.

3. A path with no fill is black in SVG but transparent in Android. Spotify lost its waves entirely
   that way — they had no fill attribute, so they simply stopped being drawn.


Android vector drawables have no equivalent of SVG's gradientTransform, and svg2vectordrawable
drops the attribute without warning — so a gradient renders in the wrong place and nothing says so.
On the Google mark that removed the green entirely: 19.2% of pixels differed from the source.

Applying the matrix to the coordinates instead brings that to 3.3%. Linear gradients come out exact,
since transforming both endpoints is lossless. Radial ones are approximated: a matrix with unequal
axis scales or any skew makes the gradient elliptical, which a single centre-plus-radius cannot
express. sqrt(|det|) is the area-preserving compromise.

Usage: prepare-svg.py <in.svg> <out.svg>
"""
import math
import re
import sys

GRADIENT = re.compile(r'<(radial|linear)Gradient[^>]*>')
MATRIX = re.compile(r'gradientTransform="matrix\(([^)]*)\)"')
OTHER_TRANSFORM = re.compile(r'gradientTransform="(?!matrix)([^"]*)"')


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
    result = normalise_colours(svg_text, warnings)
    result = make_fills_explicit(result, warnings)
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
