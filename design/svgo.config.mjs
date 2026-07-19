// svgo runs here for exactly two jobs, and nothing else.
//
//   1. convertShapeToPath — svg2vectordrawable only understands <path>. Any <circle>, <rect>,
//      <ellipse>, <polygon> or <line> is dropped silently, so artwork disappears with no error.
//      convertArcs is off by default and is what covers <circle> and <ellipse>.
//   2. convertStyleToAttrs — lifts style="" into attributes, which is what prepare-svg.py then
//      needs to see in order to normalise colours and fills.
//
// preset-default is deliberately NOT enabled. Its optimisations actively destroy things the later
// stages depend on:
//
//   - collapseGroups and friends discard nested <g transform> instead of baking them. The Google
//     mic is PDF-derived — four paths at M0 0, each positioned by its own translate group, inside a
//     flipped-Y matrix — and came out with every path stacked at the origin, i.e. invisible.
//   - convertTransform rewrites gradientTransform="matrix(...)" into rotate(...)scale(...), which
//     prepare-svg.py cannot fold into coordinates. The Gemini mark rendered as a flat blob.
//
// Both failures build cleanly and produce a wrong icon, which is the whole reason this file is
// pinned down rather than left at defaults. Skipping the preset costs only minification of an
// intermediate file that nothing ships.
export default {
  plugins: [
    { name: 'convertShapeToPath', params: { convertArcs: true } },
    'convertStyleToAttrs',
    'removeDimensions',
  ],
};
