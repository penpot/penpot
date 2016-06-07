/**
 * intervaltree
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @license MIT License <https://opensource.org/licenses/MIT>
 */
"use strict";

goog.provide("intervaltree.core");
goog.provide("intervaltree.core.IntervalTree");

goog.require("goog.asserts");
goog.require("goog.array");

goog.scope(function() {
  const assert = goog.asserts.assert;
  const every = goog.array.every;

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
    }

    add(item) {
      // Coerce to interval
      const interval = makeInterval(item);
      const node = new Node(interval);

      this.root = add(this.root, node);
      return this;
    }

    contains(point) {
      assert(goog.isNumber(point));
      assert(this.root !== null);
      return contains(this.root, point);
    }

    search(item) {
      const interval = makeInterval(item);
      return search(this.root, interval);
    }
  }

  // --- Private Api (Implementation)

  function add(root, node) {
    if (root === null) {
      return node;
    }

    if (root.interval.start === node.interval.start &&
        root.interval.end == node.interval.end) {
      return root;
    }

    if (node.interval.start <= root.interval.start) {
      root.left = add(root.left, node);
    } else {
      root.right = add(root.right, node);
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

  function contains(root, point) {
    if (root.interval.start <= point &&
        root.interval.end >= point) {
      return true;
    } else {
      let result = false;

      if (root.left && root.left.maxEnd >= point) {
        result = result || contains(root.left, point);
      }

      if (root.right && root.right.maxEnd >= point) {
        result = result || contains(root.right, point);
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

  function search(root, interval) {
    console.log("1111");
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

  // --- Public Api

  function makeInterval(value) {
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

  function makeTree(items) {
    const tree = new IntervalTree();

    if (goog.isArrayLike(items)) {
      for(let item of items) {
        tree.add(item);
      }
    }

    return tree;
  }

  const module = intervaltree.core;

  // Types
  module.IntervalTree = IntervalTree;
  module.Interval = Interval;
  module.Node = Node;

  // Constructors
  module.interval = makeInterval;
  module.create = makeTree;
});
