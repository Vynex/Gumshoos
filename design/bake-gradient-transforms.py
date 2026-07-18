#!/usr/bin/env python3
"""Fold gradientTransform into gradient coordinates.

Android vector drawables have no equivalent of SVG's gradientTransform, and svg2vectordrawable
drops the attribute without warning — so a gradient renders in the wrong place and nothing says so.
On the Google mark that removed the green entirely: 19.2% of pixels differed from the source.

Applying the matrix to the coordinates instead brings that to 3.3%. Linear gradients come out exact,
since transforming both endpoints is lossless. Radial ones are approximated: a matrix with unequal
axis scales or any skew makes the gradient elliptical, which a single centre-plus-radius cannot
express. sqrt(|det|) is the area-preserving compromise.

Usage: bake-gradient-transforms.py <in.svg> <out.svg>
"""
import math
import re
import sys

GRADIENT = re.compile(r'<(radial|linear)Gradient[^>]*>')
MATRIX = re.compile(r'gradientTransform="matrix\(([^)]*)\)"')
OTHER_TRANSFORM = re.compile(r'gradientTransform="(?!matrix)([^"]*)"')


def numbers(text):
    return [float(n) for n in re.findall(r'-?\d*\.?\d+(?:e-?\d+)?', text)]


def attr(tag, name, default=None):
    found = re.search(rf'\b{name}="([^"]*)"', tag)
    return float(found.group(1)) if found else default


def bake(svg_text):
    result = svg_text
    warnings = []

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
    baked, warnings = bake(open(source).read())
    open(destination, 'w').write(baked)
    for warning in warnings:
        print(f"    WARNING {warning}", file=sys.stderr)
