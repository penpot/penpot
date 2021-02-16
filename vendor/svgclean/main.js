
const plugins = [
    { "minifyStyles" : false },
    { "convertStyleToAttrs" : false },
    {
        "cleanupIDs" : {
            remove: false,
            minify: false,
            force: false
        }
    },
    { "cleanupListOfValues" : true },
    { "removeUnknownsAndDefaults" : false },
    { "removeViewBox" : false },
    { "convertShapeToPath" : false },
    { "convertEllipseToCircle" : false },
    { "moveElemsAttrsToGroup" : false },
    { "moveGroupAttrsToElems" : false },
    { "collapseGroups" : false },
    {
        "convertPathData" : {
            lineShorthands: false,
            curveSmoothShorthands: false,
            forceAbsolutePath: true,
        }
    },
    { "convertTransform" : false },
    { "removeEmptyContainers" : false },
    { "mergePaths" : false },
    { "sortDefsChildren" : false },
    { "removeDimensions" : true },
    { "removeStyleElement" : false },
    { "removeScriptElement" : true },
    { "removeOffCanvasPaths" : false },
    { "cleanupNumericValues": true}
];


const svgc = require("./src/svgclean.js");
const inst = svgc.configure({plugins});

exports.optimize = function(data) {
  return svgc.optimize(inst, data)
    .then((result) => result.data);
};
