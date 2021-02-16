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

const svgc = require("./src/svgclean.js");
const inst = svgc.configure({plugins});

exports.optimize = function(data) {
  return svgc.optimize(inst, data)
    .then((result) => result.data);
};
