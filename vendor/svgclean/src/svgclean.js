'use strict';

// encodeSVGDatauri = require('./svgclean/tools.js').encodeSVGDatauri,

const cfg     = require("./svgclean/config.js");
const svgToJs = require('./svgclean/svg2js.js');
const jsToSvg = require('./svgclean/js2svg.js');

exports.configure = function(config={}) {
  const plugins = cfg.loadPlugins(config);
  return Object.assign({}, config, {plugins});
};

exports.optimize = function(config, svgstr) {
  const info = {};

  return new Promise((resolve, reject) => {
    let maxPassCount = config.multipass ? 10 : 1;
    let counter = 0;
    let prevResultSize = Number.POSITIVE_INFINITY;

    function optimize(root) {
      if (root.error) {
        reject(root.error);
        return;
      }

      info.multipassCount = counter;
      if (++counter < maxPassCount && root.data.length < prevResultSize) {
        prevResultSize = root.data.length;
        runOptimizations(config, root.data, info, optimize);
      } else {
        // if (config.datauri) {
        //   root.data = encodeSVGDatauri(root.data, config.datauri);
        // }
        if (info && info.path) {
          root.path = info.path;
        }

        resolve(root);
      }
    };

    runOptimizations(config, svgstr, info, optimize);
  });
};

function runOptimizations(config, svgstr, info, callback) {
  const plugins = config.plugins;

  svgToJs(svgstr).then(function(doc) {
    doc = cfg.executePlugins(plugins, doc, info);
    // TODO: pass formating (js2svg) config
    callback(jsToSvg(doc, config.format || {}));
  }, function(error) {
    callback({error: error});
  });
};

