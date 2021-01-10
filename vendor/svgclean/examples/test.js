'use strict';

var FS = require('fs'),
    PATH = require('path'),
    SVGO = require('../lib/svgo'),
    filepath = PATH.resolve(__dirname, 'test.svg'),
    svgo = new SVGO({
        plugins: [{
          cleanupAttrs: true,
        }, {
          removeDoctype: true,
        },{
          removeXMLProcInst: true,
        },{
          removeComments: true,
        },{
          removeMetadata: true,
        },{
          removeTitle: true,
        },{
          removeDesc: true,
        },{
          removeUselessDefs: true,
        },{
          removeEditorsNSData: true,
        },{
          removeEmptyAttrs: true,
        },{
          removeHiddenElems: true,
        },{
          removeEmptyText: true,
        },{
          removeEmptyContainers: true,
        },{
          removeViewBox: false,
        },{
          cleanupEnableBackground: true,
        },{
          convertStyleToAttrs: true,
        },{
          convertColors: true,
        },{
          convertPathData: true,
        },{
          convertTransform: true,
        },{
          removeUnknownsAndDefaults: true,
        },{
          removeNonInheritableGroupAttrs: true,
        },{
          removeUselessStrokeAndFill: true,
        },{
          removeUnusedNS: true,
        },{
          cleanupIDs: true,
        },{
          cleanupNumericValues: true,
        },{
          moveElemsAttrsToGroup: true,
        },{
          moveGroupAttrsToElems: true,
        },{
          collapseGroups: true,
        },{
          removeRasterImages: false,
        },{
          mergePaths: true,
        },{
          convertShapeToPath: true,
        },{
          sortAttrs: true,
        },{
          removeDimensions: true,
        },{
          removeAttrs: {attrs: '(stroke|fill)'},
        }]
      });

FS.readFile(filepath, 'utf8', function(err, data) {

    if (err) {
        throw err;
    }

    svgo.optimize(data, {path: filepath}).then(function(result) {

        console.log(result);

        // {
        //     // optimized SVG data string
        //     data: '<svg width="10" height="20">test</svg>'
        //     // additional info such as width/height
        //     info: {
        //         width: '10',
        //         height: '20'
        //     }
        // }

    });

});
