/**
 * kdtree
 *
 * Is a modified and google closure adapted kdtree implementation
 * of https://github.com/ubilabs/kd-tree-javascript.
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @author Mircea Pricop <pricop@ubilabs.net>, 2012
 * @author Martin Kleppe <kleppe@ubilabs.net>, 2012
 * @author Ubilabs http://ubilabs.net, 2012
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

"use strict";

goog.provide("kdtree.core");
goog.require("kdtree.heap");
goog.require("lru");
goog.require("goog.asserts");

goog.scope(function() {
  const assert = goog.asserts.assert;

  // Hardcoded dimensions value;
  const dimensions = 2;

  class Node {
    constructor(obj, dimension, parent) {
      this.obj = obj;
      this.left = null;
      this.right = null;
      this.parent = parent;
      this.dimension = dimension;
    }
  }

  class KDTree {
    constructor() {
      this.root = null;
    }
  }

  // --- Private Api (implementation)

  function precision(v) {
    return parseFloat(v.toFixed(6));
  }

  function calculateDistance(a, b){
    return Math.sqrt(Math.pow(a[0] - b[0], 2) +  Math.pow(a[1] - b[1], 2));
  }

  function buildTree(parent, points, depth) {
    const dim = depth % dimensions;

    if (points.length === 0) {
      return null;
    }

    if (points.length === 1) {
      return new Node(points[0], dim, parent);
    }

    points.sort((a, b) => {
      return a[dim] - b[dim];
    });

    const median = Math.floor(points.length / 2);
    const node = new Node(points[median], dim, parent);
    node.left = buildTree(node, points.slice(0, median), depth + 1);
    node.right = buildTree(node, points.slice(median + 1), depth + 1);

    return node;
  }

  function searchNearest(root, point, maxNodes) {
    const search = (best, node) => {
      if (best === null) {
        best = new kdtree.heap.MinHeap((x, y) => x[1] - y[1]);
      }

      let distance = precision(calculateDistance(point, node.obj));

      if (best.isEmpty()) {
        best.insert([node.obj, distance]);
      } else {
        if (distance < best.peek()[1]) {
          best.insert([node.obj, distance]);
        }
      }

      if (node.right === null && node.left === null) {
        return best;
      }

      let bestChild = null;
      if (node.right === null) {
        bestChild = node.left;
      } else if (node.left === null) {
        bestChild = node.right;
      } else {
        if (point[node.dimension] < node.obj[node.dimension]) {
          bestChild = node.left;
        } else {
          bestChild = node.right;
        }
      }

      best = search(best, bestChild);

      let candidate = [null, null];
      for (let i = 0; i < dimensions; i += 1) {
        if (i === node.dimension) {
          candidate[i] = point[i];
        } else {
          candidate[i] = node.obj[i];
        }
      }

      distance = Math.abs(calculateDistance(candidate, node.obj));

      if (best.size < maxNodes || distance < best.peek()[1]) {
        let otherChild;
        if (bestChild === node.left) {
          otherChild = node.right;
        } else {
          otherChild = node.left;
        }
        if (otherChild !== null) {
          best = search(best, otherChild);
        }
      }

      return best;
    };

    const best = search(null, root);
    const result = [];

    for (let i=0; i < (Math.min(maxNodes, best.size)); i++) {
      result.push(best.removeHead());
    }

    return result;
  }

  // --- Public Api
  const cache = new lru.create();

  function create(points) {
    const tree = new KDTree();
    if (goog.isArray(points)) {
      return initialize(tree, points);
    } else {
      return tree;
    }
  };

  function generate(width, height, widthStep, heightStep) {
    const key = `${width}.${height}.${widthStep}.${heightStep}`;

    let tree = lru.get(cache, key);
    if (tree instanceof KDTree) {
      return tree;
    } else {
      tree = new KDTree();
      setup(tree, width, height, widthStep, heightStep);
      lru.set(cache, key, tree);
      return tree;
    }
  }

  function initialize(tree, points) {
    assert(goog.isArray(points));
    assert(tree instanceof KDTree);

    tree.root = buildTree(null, points, 0);
    return tree;
  }

  function setup(tree, width, height, widthStep, heightStep) {
    const totalSize = Math.floor((width/widthStep) * (height/heightStep));
    const points = new Array(totalSize);
    let pos = 0;

    for (let i = 0; i <= width; i += widthStep){
      for (let z = 0; z <= height; z += heightStep){
        points[pos++] = [i, z];
      }
    }

    initialize(tree, points);
    return tree;
  }

  function isInitialized(tree) {
    assert(tree instanceof KDTree);
    return tree.root !== null;
  }

  function clear(tree) {
    assert(tree instanceof KDTree);
    tree.root = null;
    return tree;
  }

  function nearest(tree, point, maxNodes) {
    assert(goog.isArray(point));
    assert(maxNodes >= 1);
    assert(isInitialized(tree));
    return searchNearest(tree.root, point, maxNodes);
  }

  // Factory functions
  kdtree.core.create = create;
  kdtree.core.generate = generate;
  kdtree.core.initialize = initialize;
  kdtree.core.setup = setup;
  kdtree.core.isInitialized = isInitialized;
  kdtree.core.clear = clear;
  kdtree.core.nearest = nearest;
});
