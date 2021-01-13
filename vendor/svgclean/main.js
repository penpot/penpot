const plugins = [
    {removeDimensions: true},
    {removeXMLNS: true},
    {removeScriptElement: true},
    {removeViewBox: false},
    {moveElemsAttrsToGroup: false},
    {
      convertPathData: {
        lineShorthands: false,
        curveSmoothShorthands: false,
        forceAbsolutePath: true,
      },
    },
];

const svgc = require("./src/svgclean.js");
const inst = svgc.configure({plugins, multipass: undefined});

exports.optimize = function(data) {
  return svgc.optimize(inst, data)
    .then((result) => result.data);
};
