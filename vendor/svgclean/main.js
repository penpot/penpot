/*
const plugins = [
    {removeDimensions: true},
    {removeScriptElement: true},
    {removeViewBox: false},
    {moveElemsAttrsToGroup: false},
    {convertStyleToAttrs: false},
    {removeUselessDefs: false},
    {convertPathData: {
      lineShorthands: false,
      curveSmoothShorthands: false,
      forceAbsolutePath: true,
    }}
];
*/

const plugins = [
    'removeDoctype',
    'removeXMLProcInst',
    'removeComments',
    'removeMetadata',
    // 'removeXMLNS',
    'removeEditorsNSData',
    'cleanupAttrs',
    'inlineStyles',
    'minifyStyles',
    // 'convertStyleToAttrs'
    'cleanupIDs',
    // 'prefixIds',
    // 'removeRasterImages',
    // 'removeUselessDefs',
    'cleanupNumericValues',
    // 'cleanupListOfValues',
    'convertColors',
    'removeUnknownsAndDefaults',
    'removeNonInheritableGroupAttrs',
    'removeUselessStrokeAndFill',
    // 'removeViewBox',
    'cleanupEnableBackground',
    'removeHiddenElems',
    'removeEmptyText',
    'convertShapeToPath',
    'convertEllipseToCircle',
    // 'moveElemsAttrsToGroup',
    'moveGroupAttrsToElems',
    'collapseGroups',
    {'convertPathData': {
        'lineShorthands': false,
        'curveSmoothShorthands': false,
        'forceAbsolutePath': true,
    }},
    'convertTransform',
    'removeEmptyAttrs',
    'removeEmptyContainers',
    'mergePaths',
    'removeUnusedNS',
    // 'sortAttrs',
    'sortDefsChildren',
    'removeTitle',
    'removeDesc',
    'removeDimensions',
    'removeAttrs',
    // 'removeAttributesBySelector',
    // 'removeElementsByAttr',
    // 'addClassesToSVGElement',
    'removeStyleElement',
    'removeScriptElement',
    // 'addAttributesToSVGElement',
    // 'removeOffCanvasPaths',
    // 'reusePaths',
];

const svgc = require("./src/svgclean.js");
const inst = svgc.configure({plugins});

exports.optimize = function(data) {
  return svgc.optimize(inst, data)
    .then((result) => result.data);
};
