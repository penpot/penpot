/**
 * intervaltree
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

"use strict";

goog.provide("app.util.intervaltree_impl");
goog.require("goog.asserts");
goog.require("goog.array");

goog.scope(function() {
  const self = app.util.intervaltree_impl;

  const assert = goog.asserts.assert;
  const every = goog.array.every;

  const ID_SYM = Symbol.for("app.util.intervaltree:id-sym");

  // --- Types Declaration

  class Interval {
    constructor(start, end, data) {
      this.start = start;
      this.end = end;
      this.data = data || null;
    }

    toString() {
      return `v=${this.start},${this.end} d=${this.data}`;
    }
  }

  class Node {
    constructor(interval) {
      this.interval = interval;
      this.left = null;
      this.right = null;
      this.maxEnd = interval.end;
      this.height = 1;
    }
  }

  class IntervalTree {
    constructor() {
      this.root = null;
      this.byId = new Map();
    }
  }

  // --- Private Api (Implementation)

  const nextId = (function() {
    let counter = 0;
    return function() {
      return counter++;
    };
  })();

  function addNode(root, node) {
    if (root === null) {
      return node;
    }

    if (root.interval.start === node.interval.start &&
        root.interval.end == node.interval.end) {
      return root;
    }

    if (node.interval.start <= root.interval.start) {
      root.left = addNode(root.left, node);
    } else {
      root.right = addNode(root.right, node);
    }

    root.maxEnd = calculateMaxEnd(root);
    root.height = calculateHeight(root);

    const balance = calculateBalance(root);
    if (balance > 1) {
      if (node.interval.start < root.left.interval.start) {
        return rotateRight(root);
      }
      // else {
      //   root.left = rotateLeft(root.left);
      //   return rotateRight(root);
      // }
    } else if (balance < -1) {
      if (node.interval.start > root.right.interval.start) {
        return rotateLeft(root);
      }
      // else {
      //   root.right = rotateRight(root.right);
      //   return rotateLeft(current);
      // }
    }

    return root;
  }

  function removeInterval(root, index, interval) {
    if (root === null) {
      return root;
    } else if (root.interval.start === interval.start &&
               root.interval.end === interval.end) {

      // Remove interval from the index.
      const intervalId = root.interval[ID_SYM];
      index.delete(intervalId);

      if (root.left === null) {
        return root.right;
      } else if (root.right === null) {
        return root.left;
      } else {
        const result = removeLeftMost(root.right);
        const newroot = result[0];
        const newright = result[1];

        newroot.left = root.left;
        newroot.right = newright;
        return newroot;
      }
    } else {
      root.left = remove(root.left, index, interval);
      root.right = remove(root.right, index, interval);

      root.height = calculateHeight(root);
      root.maxEnd = calculateMaxEnd(root);

      return root;
    }
  }

  function removeLeftMost(root) {
    if (root === null) {
      return [null, null];
    } else if (root.left === null) {
      return [root, root.right];
    } else {
      const result = removeLeftMost(root.left);
      const newroot = result[0];
      const newleft = result[1];
      root.left = newleft;

      return [newroot, root];
    }
  }

  function calculateMaxEnd(node) {
    const left = node.left ? node.left.maxEnd : 0;
    const right = node.right ? node.right.maxEnd: 0;
    return Math.max(node.interval.end, Math.max(left, right));
  }

  function calculateHeight(node) {
    const left = node.left ? node.left.height : 0;
    const right = node.right ? node.right.height: 0;
    return Math.max(left, right) + 1;
  }

  function calculateBalance(node) {
    if (node === null) {
      return 0;
    }

    const left = node.left ? node.left.height: 0;
    const right = node.right ? node.right.height: 0;
    return left - right;
  }

  function rotateLeft(z) {
    const y = z.right;
    const x = y.right;

    const t1 = z.left;
    const t2 = y.left;

    z.left = t1;
    z.right = t2;

    y.left = z;
    y.right = x;

    z.height = calculateHeight(z);
    z.maxEnd = calculateMaxEnd(z);
    y.height = calculateHeight(z);
    y.maxEnd = calculateMaxEnd(z);

    return y;
  }

  function rotateRight(z) {
    const y = z.left;
    const x = y.left;

    const t3 = y.right;
    const t4 = z.right;

    z.left = t3;
    z.right = t4;

    y.left = x;
    y.right = z;

    z.height = calculateHeight(z);
    z.maxEnd = calculateMaxEnd(z);
    y.height = calculateHeight(z);
    y.maxEnd = calculateMaxEnd(z);

    return y;
  }

  function containsPoint(root, point) {
    if (root.interval.start <= point &&
        root.interval.end >= point) {
      return true;
    } else {
      let result = false;

      if (root.left && root.left.maxEnd >= point) {
        result = result || containsPoint(root.left, point);
      }

      if (root.right && root.right.maxEnd >= point) {
        result = result || containsPoint(root.right, point);
      }

      return result;
    }
  }

  function isIntervalIntersect(a, b) {
    return ((a.start <= b.start && a.end >= b.start) ||
            (a.start <= b.end && a.end >= b.end) ||
            (b.start <= a.start && b.end >= a.start) ||
            (b.start <= a.end && b.end >= a.end));
  }

  function searchSingleInterval(root, interval) {
    if (isIntervalIntersect(root.interval, interval)) {
      return root.interval;
    } else {
      let result = null;

      if (root.left && root.left.maxEnd >= interval.start) {
        result = result || search(root.left, interval);
      }

      if (root.right && root.right.maxEnd >= interval.start) {
        result = result || search(root.right, interval);
      }

      return result;
    }
  }

  function searchInterval(root, interval) {
    const result = new Set();

    if (isIntervalIntersect(root.interval, interval)) {
      result.add(root.interval);
    }

    if (root.left && root.left.maxEnd >= interval.start) {
      for (let item of searchMany(root.left, interval)) {
        result.add(item);
      }
    }

    if (root.right && root.right.maxEnd >= interval.start) {
      for (let item of searchMany(root.right, interval)) {
        result.add(item);
      }
    }

    return result;
  }

  function createInterval(value) {
    if (value instanceof Interval) {
      return value
    } else if (goog.isArray(value)) {
      if (value.length === 1 &&
          goog.isNumber(value[0])) {
        return new Interval(value[0], value[0]);
      } else if (value.length === 2 && goog.isNumber(value[0])) {
        if (goog.isNumber(value[1])) {
          return new Interval(value[0], value[1]);
        } else {
          return new Interval(value[0], value[0], value[1]);
        }
      } else if (value.length === 3 &&
                 goog.isNumber(value[0]) &&
                 goog.isNumber(value[1])) {
        return new Interval(value[0], value[1], value[2]);
      }
    } else if (arguments.length === 1 && goog.isNumber(value)) {
      return new Interval(value, value);
    } else if (arguments.length >= 2 && goog.isNumber(value)) {
      if (goog.isNumber(arguments[1])) {
        return new Interval(value, arguments[1], arguments[2]);
      } else {
        return new Interval(value, value, arguments[1]);
      }
    } else {
      throw new Error("Unexpected input.");
    }
  };

  // --- Public Api

  function create(items) {
    const tree = new IntervalTree();

    if (goog.isArray(items)) {
      for(let item of items) {
        tree.add(item);
      }
    }

    return tree;
  }

  function add(tree, id, item) {
    assert(tree instanceof IntervalTree);

    if (id && !item) {
      item = id;
      id = nextId();
    }

    // Coerce to interval
    const interval = createInterval(item);
    const node = new Node(interval);

    interval[ID_SYM] = id;

    tree.byId.set(id, interval);
    tree.root = addNode(tree.root, node);
    return tree;
  }

  function clear(tree) {
    assert(tree instanceof IntervalTree);
    this.root = null;
    this.byId.clear();
  }

  function remove(tree, item) {
    assert(tree instanceof IntervalTree);

    const interval = createInterval(item);
    tree.root = removeInterval(tree.root, tree.byId, interval);
    return tree;
  }

  function removeById(tree, id) {
    assert(tree instanceof IntervalTree);

    if (tree.byId.has(id)) {
      const interval = this.byId.get(id);
      remove(tree, interval);
    }
  }

  function contains(tree, point) {
    assert(tree instanceof IntervalTree);
    assert(goog.isNumber(point));

    return containsPoint(tree.root, point);
  }

  function search(tree, item) {
    assert(tree instanceof IntervalTree);
    const interval = createInterval(item);
    return Array.from(searchInterval(tree.root, interval));
  }

  function searchSingle(tree, item) {
    assert(tree instanceof IntervalTree);
    const interval = createInterval(item);
    return searchSingleInterval(tree.root, interval);
  }

  // Api
  self.create = create;
  self.add = add;
  self.remove = remove;
  self.removeById = removeById;
  self.contains = contains;
  self.search = search;
  self.searchSingle = searchSingle;

  // Test
  self.test = function() {
    // const util = require('util');

    console.time("init");
    const tree = self.create([
      [1,5], [-5, 10], [4, 9],
      [10,14], [-10, 1], [9, 22],
    ]);
    console.timeEnd("init");
    console.dir(tree, { depth: 5});

    const n = 2;
    console.time("search");
    console.log("SEARCH***********************************");
    console.log(`RESULT searchMany(${n}):`);
    console.log(tree.searchMany(n));
    console.timeEnd("search");

    console.time("remove");
    // // tree.remove([4,9]);
    // tree.remove([9, 22]);
    tree.remove([-10, 1]);
    console.timeEnd("remove");

    // console.dir(tree, { depth: 5});
  };

});
