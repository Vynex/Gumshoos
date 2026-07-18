export default {
  plugins: [
    { name: 'preset-default', params: { overrides: { mergePaths: false } } },
    // convertArcs is off by default, which silently leaves <circle>/<ellipse> untouched — and
    // svg2vectordrawable then drops them, losing whole parts of the artwork with no warning.
    { name: 'convertShapeToPath', params: { convertArcs: true } },
    'convertStyleToAttrs',
    'removeDimensions',
  ],
};
