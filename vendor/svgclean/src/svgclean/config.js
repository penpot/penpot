'use strict';

const _collections = require('./plugins/_collections');
const _path = require('./plugins/_path');
const _transforms = require('./plugins/_transforms');
const addAttributesToSVGElement = require('./plugins/addAttributesToSVGElement');
const addClassesToSVGElement = require('./plugins/addClassesToSVGElement');
const cleanupAttrs = require('./plugins/cleanupAttrs');
const cleanupEnableBackground = require('./plugins/cleanupEnableBackground');
const cleanupIDs = require('./plugins/cleanupIDs');
const cleanupListOfValues = require('./plugins/cleanupListOfValues');
const cleanupNumericValues = require('./plugins/cleanupNumericValues');
const collapseGroups = require('./plugins/collapseGroups');
const convertColors = require('./plugins/convertColors');
const convertEllipseToCircle = require('./plugins/convertEllipseToCircle');
const convertPathData = require('./plugins/convertPathData');
const convertShapeToPath = require('./plugins/convertShapeToPath');
const convertStyleToAttrs = require('./plugins/convertStyleToAttrs');
const convertTransform = require('./plugins/convertTransform');
const inlineStyles = require('./plugins/inlineStyles');
const mergePaths = require('./plugins/mergePaths');
const minifyStyles = require('./plugins/minifyStyles');
const moveElemsAttrsToGroup = require('./plugins/moveElemsAttrsToGroup');
const moveGroupAttrsToElems = require('./plugins/moveGroupAttrsToElems');
const prefixIds = require('./plugins/prefixIds');
const removeAttributesBySelector = require('./plugins/removeAttributesBySelector');
const removeAttrs = require('./plugins/removeAttrs');
const removeComments = require('./plugins/removeComments');
const removeDesc = require('./plugins/removeDesc');
const removeDimensions = require('./plugins/removeDimensions');
const removeDoctype = require('./plugins/removeDoctype');
const removeEditorsNSData = require('./plugins/removeEditorsNSData');
const removeElementsByAttr = require('./plugins/removeElementsByAttr');
const removeEmptyAttrs = require('./plugins/removeEmptyAttrs');
const removeEmptyContainers = require('./plugins/removeEmptyContainers');
const removeEmptyText = require('./plugins/removeEmptyText');
const removeHiddenElems = require('./plugins/removeHiddenElems');
const removeMetadata = require('./plugins/removeMetadata');
const removeNonInheritableGroupAttrs = require('./plugins/removeNonInheritableGroupAttrs');
const removeOffCanvasPaths = require('./plugins/removeOffCanvasPaths');
const removeRasterImages = require('./plugins/removeRasterImages');
const removeScriptElement = require('./plugins/removeScriptElement');
const removeStyleElement = require('./plugins/removeStyleElement');
const removeTitle = require('./plugins/removeTitle');
const removeUnknownsAndDefaults = require('./plugins/removeUnknownsAndDefaults');
const removeUnusedNS = require('./plugins/removeUnusedNS');
const removeUselessDefs = require('./plugins/removeUselessDefs');
const removeUselessStrokeAndFill = require('./plugins/removeUselessStrokeAndFill');
const removeViewBox = require('./plugins/removeViewBox');
const removeXMLNS = require('./plugins/removeXMLNS');
const removeXMLProcInst = require('./plugins/removeXMLProcInst');
const reusePaths = require('./plugins/reusePaths');
const sortAttrs = require('./plugins/sortAttrs');
const sortDefsChildren = require('./plugins/sortDefsChildren');

const builtinPlugins = {
  removeOffCanvasPaths,
  _collections,
  _path,
  _transforms,
  addAttributesToSVGElement,
  addClassesToSVGElement,
  cleanupAttrs,
  cleanupEnableBackground,
  cleanupIDs,
  cleanupListOfValues,
  cleanupNumericValues,
  collapseGroups,
  convertColors,
  convertEllipseToCircle,
  convertPathData,
  convertShapeToPath,
  convertStyleToAttrs,
  convertTransform,
  inlineStyles,
  mergePaths,
  minifyStyles,
  moveElemsAttrsToGroup,
  moveGroupAttrsToElems,
  prefixIds,
  removeAttributesBySelector,
  removeAttrs,
  removeComments,
  removeDesc,
  removeDimensions,
  removeDoctype,
  removeEditorsNSData,
  removeElementsByAttr,
  removeEmptyAttrs,
  removeEmptyContainers,
  removeEmptyText,
  removeHiddenElems,
  removeMetadata,
  removeNonInheritableGroupAttrs,
  removeRasterImages,
  removeScriptElement,
  removeStyleElement,
  removeTitle,
  removeUnknownsAndDefaults,
  removeUnusedNS,
  removeUselessDefs,
  removeUselessStrokeAndFill,
  removeViewBox,
  removeXMLNS,
  removeXMLProcInst,
  reusePaths,
  sortAttrs,
  sortDefsChildren,
};

const defaultPlugins = {
  prefixIds: true,
  cleanupAttrs: true,
  cleanupEnableBackground: true,
  cleanupIDs: true,
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
  moveElemsAttrsToGroup: false,
  moveGroupAttrsToElems: false,
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
  removeViewBox: false,
  removeXMLNS: true,
  removeXMLProcInst: true,
  sortAttrs: false,
  sortDefsChildren: true
};

function optimizePlugins(plugins) {
  let prev;

  return plugins.reduce(function(plugins, item) {
    if (prev && item.type == prev[0].type) {
      prev.push(item);
    } else {
      plugins.push(prev = [item]);
    }
    return plugins;
  }, []);
}

exports.loadPlugins = function(config={}) {
  let plugins = Object.assign({}, config.plugins || defaultPlugins);
  let configuredPlugins = [];

  for (let key of Object.keys(plugins)) {
    let pluginOpt = plugins[key];
    let plugin = null;

    if (typeof pluginOpt === "object") {
      plugin = Object.assign({}, builtinPlugins[key]);
      plugin.params = Object.assign({}, pluginOpt);
    } else if (pluginOpt === true) {
      plugin = Object.assign({}, builtinPlugins[key]);
    } else {
      continue;
    }

    plugin.active = true;
    configuredPlugins.push(plugin);
  }

  return optimizePlugins(configuredPlugins);
};

exports.executePlugins = function(plugins, data, info) {
  plugins.forEach(function(group) {
    switch(group[0].type) {
    case 'perItem':
      data = perItem(data, info, group);
      break;
    case 'perItemReverse':
      data = perItem(data, info, group, true);
      break;
    case 'full':
      data = full(data, info, group);
      break;
    }

  });

  return data;
}

/**
 * Direct or reverse per-item loop.
 *
 * @param {Object} data input data
 * @param {Object} info extra information
 * @param {Array} plugins plugins list to process
 * @param {Boolean} [reverse] reverse pass?
 * @return {Object} output data
 */
function perItem(data, info, plugins, reverse) {

  function monkeys(items) {
    items.content = items.content.filter(function(item) {

      // reverse pass
      if (reverse && item.content) {
        monkeys(item);
      }

      // main filter
      let filter = true;

      for (let i = 0; filter && i < plugins.length; i++) {
        let plugin = plugins[i];

        if (plugin.active && plugin.fn(item, plugin.params, info) === false) {
          filter = false;
        }
      }

      // direct pass
      if (!reverse && item.content) {
        monkeys(item);
      }

      return filter;

    });

    return items;

  }

  return monkeys(data);
}

/**
 * "Full" plugins.
 *
 * @param {Object} data input data
 * @param {Object} info extra information
 * @param {Array} plugins plugins list to process
 * @return {Object} output data
 */
function full(data, info, plugins) {
  plugins.forEach(function(plugin) {
    if (plugin.active) {
      data = plugin.fn(data, plugin.params, info);
    }
  });
  return data;
}
