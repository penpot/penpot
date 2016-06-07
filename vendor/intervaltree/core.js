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
    constructor(interval, parent) {
      this.interval = interval;
      this.parent = parent || null
      this.left = null;
      this.right = null;
      this.max = -Infinity;
    }
  }

  class IntervalTree {
    constructor() {
      this.root = null;
    }

    add(item) {
      // Coerce to interval
      const interval = makeInterval(item);

      // Set root if the current tree is empty
      if (this.root === null) {
        this.root = new Node(interval, null);
      } else {
        insertInterval(this.root, interval);
      }

      return this;
    }

    contains(point) {
      assert(goog.isNumber(point));
      assert(this.root !== null);
      return contains(this.root, point);
    }

    search(item) {
      assert(this.root !== null);
      const interval = makeInterval(item);
      return search(this.root, interval);
    }
  }

  // --- Private Api (Implementation)

  function insertInterval(root, interval) {
    if (root.interval.start > interval.start) {
      if (root.left !== null) {
        insertInterval(root.left, interval);
      } else {
        root.left = new Node(interval, root);
        recalculateMax(root.left);
      }
    } else {
      if (root.right !== null) {
        insertInterval(root.right, interval);
      } else {
        root.right = new Node(interval, root);
        recalculateMax(root.right);
      }
    }
  }

  function recalculateMax(node) {
    const interval = node.interval;
    const parent = node.parent;

    if (parent.max < interval.end) {
      while (node) {
        if (node.max < interval.end) {
          node.max = interval.end;
        }
        node = node.parent;
      }
    }
  }

  function contains(root, point) {
    if (root.interval.start <= point &&
        root.interval.end >= point) {
      return true;
    } else {
      let result = false;

      if (root.left && root.left.max >= point) {
        result = result || contains(root.left, point);
      }

      if (root.right && root.right.max >= point) {
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

      if (root.left && root.left.max >= interval.start) {
        result = result || search(root.left, interval);
      }

      if (root.right && root.right.max >= interval.start) {
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

  // function getRandom(min, max) {
  //   var crypto = require("crypto");
  //   var MAX_UINT32 = 0xFFFFFFFF;
  //   var range = max - min;

  //   if (!(range <= MAX_UINT32)) {
  //     throw new Error(
  //       "Range of " + range + " covering " + min + " to " + max + " is > " +
  //         MAX_UINT32 + ".");
  //   } else if (min === max) {
  //     return min;
  //   } else if (!(max > min)) {
  //     throw new Error("max (" + max + ") must be >= min (" + min + ").");
  //   }

  //   // We need to cut off values greater than this to avoid bias in distribution
  //   // over the range.
  //   var maxUnbiased = MAX_UINT32 - ((MAX_UINT32 + 1) % (range + 1));

  //   var rand;
  //   do {
  //     rand = crypto.randomBytes(4).readUInt32LE();
  //     // rand = crypto.randomBytes(new Uint32Array(1))[0];
  //   } while (rand > maxUnbiased);

  //   var offset = rand % (range + 1);
  //   return min + offset;
  // }

  // function* randomSeq(n) {
  //   for(let i=0; i<n; i++) {
  //     let value = getRandom(0, 1000);
  //     yield [value, getRandom(value, value+10)]
  //   }
  // }

  // function randomList(n) {
  //   return Array.from(randomSeq(n));
  // }

  // module.randomList = randomList;
  // module.benchmark = function() {
  //   let util = require('util');

  //   const intervals = randomList(100000);
  //   console.time("init");
  //   const tree = module.create(intervals);
  //   console.timeEnd("init");

  //   console.log(util.inspect(tree, {showHidden: false, depth: 5}));


  //   console.time("search")

  //   console.log("result:", tree.search(getRandom(0, 100000)));

  //   console.timeEnd("search")
  // };

  // module.test = function() {
  //   let util = require('util');

  //   const tree = makeTree([[1,3], [-4,0], [7,8], [6, 100], [8,12]]);
  //   console.log(util.inspect(tree, {showHidden: false, depth: null}));

  //   console.log("result:", tree.search(8));
  // };

});
