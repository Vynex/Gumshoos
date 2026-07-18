#!/usr/bin/env python3
"""Compare each generated vector drawable against the SVG it came from.

The point is to render the *drawable*, not an intermediate. An earlier check compared the source
SVG against the pre-conversion SVG — both rendered by the same engine — so it could only ever
validate the preprocessing, never the converter. It passed every icon while YouTube was shipping as
a black rectangle and Spotify had lost its waves.

Vector drawables map cleanly back to SVG (paths, solid fills, strokes, gradients), so translating
one and rendering it exercises what the app will actually draw.

Usage: verify-icons.py            (needs cairosvg, numpy, pillow)
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
AAPT = '{http://schemas.android.com/aapt}'
HERE = os.path.dirname(os.path.abspath(__file__))


def argb_to_svg(colour):
    """#AARRGGBB -> (#RRGGBB, opacity). Android puts alpha first; SVG keeps it separate."""
    if not colour or not colour.startswith('#'):
        return None, 1.0
    digits = colour[1:]
    if len(digits) == 8:
        alpha = int(digits[0:2], 16) / 255.0
        return f'#{digits[2:]}', alpha
    return f'#{digits}', 1.0


def gradient_to_svg(node, index):
    stops = ''
    for item in node.findall(f'{ANDROID}item') or node.findall('item'):
        colour, alpha = argb_to_svg(item.get(f'{ANDROID}color'))
        offset = item.get(f'{ANDROID}offset', '0')
        stops += f'<stop offset="{offset}" stop-color="{colour}" stop-opacity="{alpha}"/>'

    kind = node.get(f'{ANDROID}type', 'linear')
    if kind == 'radial':
        cx = node.get(f'{ANDROID}centerX', '0')
        cy = node.get(f'{ANDROID}centerY', '0')
        r = node.get(f'{ANDROID}gradientRadius', '0')
        return (f'<radialGradient id="g{index}" cx="{cx}" cy="{cy}" r="{r}" '
                f'gradientUnits="userSpaceOnUse">{stops}</radialGradient>')
    x1 = node.get(f'{ANDROID}startX', '0')
    y1 = node.get(f'{ANDROID}startY', '0')
    x2 = node.get(f'{ANDROID}endX', '0')
    y2 = node.get(f'{ANDROID}endY', '0')
    return (f'<linearGradient id="g{index}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'gradientUnits="userSpaceOnUse">{stops}</linearGradient>')


def drawable_to_svg(path):
    root = ET.parse(path).getroot()
    width = root.get(f'{ANDROID}viewportWidth', '24')
    height = root.get(f'{ANDROID}viewportHeight', '24')

    defs, body, index = '', '', 0
    for element in root:
        if not element.tag.endswith('path'):
            continue
        data = element.get(f'{ANDROID}pathData', '')
        fill_colour, fill_alpha = argb_to_svg(element.get(f'{ANDROID}fillColor'))
        stroke_colour, stroke_alpha = argb_to_svg(element.get(f'{ANDROID}strokeColor'))

        gradient = None
        for child in element:
            if child.tag == f'{AAPT}attr' and child.get('name') == 'android:fillColor':
                for grandchild in child:
                    gradient = grandchild
        if gradient is not None:
            defs += gradient_to_svg(gradient, index)
            fill = f'url(#g{index})'
            fill_alpha = 1.0
            index += 1
        else:
            fill = fill_colour or 'none'

        attributes = f'fill="{fill}" fill-opacity="{fill_alpha}"'
        if element.get(f'{ANDROID}fillType', '').lower() == 'evenodd':
            attributes += ' fill-rule="evenodd"'
        if stroke_colour:
            attributes += (f' stroke="{stroke_colour}" stroke-opacity="{stroke_alpha}"'
                           f' stroke-width="{element.get(f"{ANDROID}strokeWidth", "1")}"')
            cap = element.get(f'{ANDROID}strokeLineCap')
            if cap:
                attributes += f' stroke-linecap="{cap}"'
        body += f'<path d="{data}" {attributes}/>'

    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}">'
            f'<defs>{defs}</defs>{body}</svg>')


def main():
    try:
        import cairosvg
        import numpy as np
        from PIL import Image
    except ImportError:
        print("needs cairosvg, numpy and pillow", file=sys.stderr)
        return 1

    failures = 0
    for source in sorted(glob.glob(f'{HERE}/icons/*.svg')):
        name = os.path.basename(source)[:-4]
        drawable = f'{HERE}/../app/src/main/res/drawable/{name}.xml'
        if not os.path.exists(drawable):
            print(f'{name}: no drawable')
            failures += 1
            continue

        translated = f'/tmp/{name}.vd.svg'
        open(translated, 'w').write(drawable_to_svg(drawable))
        cairosvg.svg2png(url=source, write_to=f'/tmp/{name}.src.png',
                         output_width=160, output_height=160)
        cairosvg.svg2png(url=translated, write_to=f'/tmp/{name}.vd.png',
                         output_width=160, output_height=160)

        a = np.asarray(Image.open(f'/tmp/{name}.src.png').convert('RGBA')).astype(int)
        b = np.asarray(Image.open(f'/tmp/{name}.vd.png').convert('RGBA')).astype(int)
        differing = (np.abs(a - b).sum(axis=2) > 30).mean() * 100
        verdict = 'ok' if differing < 6 else 'CHECK'
        if verdict == 'CHECK':
            failures += 1
        print(f'{name:26s} {differing:5.1f}% differing  {verdict}')

    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
