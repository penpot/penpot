const plugins = {
  // prefixIds: true,
  cleanupAttrs: true,
  // cleanupEnableBackground: true,
  // cleanupIDs: true,
  cleanupNumericValues: true,
  collapseGroups: true,
  convertColors: true,
  convertEllipseToCircle: true,
  convertPathData: true,
  convertShapeToPath: true,
  convertStyleToAttrs: true,
  convertTransform: true,
  inlineStyles: true,
  mergePaths: false,
  minifyStyles: true,
  removeComments: true,
  removeDesc: true,
  removeDimensions: false,
  removeDoctype: true,
  removeEditorsNSData: true,
  removeEmptyAttrs: true,
  removeEmptyContainers: true,
  removeEmptyText: true,
  removeHiddenElems: true,
  removeNonInheritableGroupAttrs: true,
  removeRasterImages: true,
  removeTitle: true,
  removeUnknownsAndDefaults: true,
  removeUnusedNS: true,
  removeUselessDefs: true,
  removeUselessStrokeAndFill: true,
  removeXMLNS: true,
  removeXMLProcInst: true
};

const svgc = require("./src/svgclean.js");
const inst = svgc.configure({plugins});

exports.optimize = function(data) {
  return svgc.optimize(inst, data)
    .then((result) => result.data);
};
