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
 * @license MIT License <http://www.opensource.org/licenses/mit-license.php>
 */

goog.provide("kdtree");
goog.provide("kdtree.Point2d");
goog.provide("kdtree.KDTree");

goog.require('goog.array');
goog.require('goog.asserts');

goog.scope(function() {
  "use strict";

  const assert = goog.asserts.assert;
  const assertNumber = goog.asserts.assertNumber;
  const every = goog.array.every;

  class Point2d  {
    constructor(x, y, data) {
      this.type = Point2d;
      this.x = x;
      this.y = y;
      this.data = data;
    }

    static empty() {
      return new Point2d(0, 0, null);
    }

    get(index) {
      if (index === 0) {
        return this.x;
      } else {
        return this.y;
      }
    }

    set(index, value) {
      if (index === 0) {
        this.x = value;
      } else {
        this.y = value;
      }
      return this;
    }
  }

  class Node {
    constructor(obj, dimension, parent) {
      this.obj = obj;
      this.left = null;
      this.right = null;
      this.parent = parent;
      this.dimension = dimension;
    }
  }

  function buildTree(points, depth, parent, dimensions) {
    const dim = depth % dimensions;

    if (points.length === 0) {
      return null;
    }

    if (points.length === 1) {
      return new Node(points[0], dim, parent);
    }

    points.sort((a, b) => {
      return a.get(dim) - b.get(dim);
    });

    const median = Math.floor(points.length / 2);
    const node = new Node(points[median], dim, parent);
    node.left = buildTree(points.slice(0, median), depth + 1, node, dimensions);
    node.right = buildTree(points.slice(median + 1), depth + 1, node, dimensions);

    return node;
  }

  function findMin(node, dim) {
    let dimension, own, left, right, min;

    if (node === null) {
      return null;
    }

    if (node.dimension === dim) {
      if (node.left !== null) {
        return findMin(node.left, dim);
      }
      return node;
    }

    own = node.obj.get(dim);
    left = findMin(node.left, dim);
    right = findMin(node.right, dim);
    min = node;

    if (left !== null && left.obj.get(dim) < own) {
      min = left;
    }
    if (right !== null && right.obj.get(dim) < min.obj.get(dim)) {
      min = right;
    }
    return min;
  }

  function innerSearch(point, node, parent) {
    if (node === null) {
      return parent;
    }

    if (point.get(dim) < node.obj.get(dim)) {
      return innerSearch(point, node.left, node);
    } else {
      return innerSearch(point, node.right, node);
    }
  }

  function nodeSearch(point, node) {
    if (node === null) {
      return null;
    }

    if (node.obj === point) {
      return node;
    }

    if (point.get(node.dimension) < node.obj.get(node.dimension)) {
      return nodeSearch(point, node.left);
    } else {
      return nodeSearch(point, node.right);
    }
  }

  class KDTree {
    constructor(points, metric, dimensions) {
      assert(points.length !== 0);
      assertNumber(dimensions);

      this.root = buildTree(points, 0, null, dimensions);
      this.metric = metric;
      this.dimensions = dimensions;
    }

    insert(point) {
      const insertPosition = innerSearch(point, this.root, null);

      if (insertPosition === null) {
        this.root = new Node(point, 0, null);
        return;
      }

      const newNode = new Node(point,
                               (insertPosition.dimension + 1) % this.dimensions,
                               insertPosition);

      const dimension = insertPosition.dimension;
      if (point.get(dimension) < insertPosition.obj.get(dimension)) {
        insertPosition.left = newNode;
      } else {
        insertPosition.right = newNode;
      }
    }

    remove(point) {
      const node = nodeSearch(point, this.root);
      if (node === null) {
        return;
      }

      if (node.left === null && node.right === null) {
        if (node.parent === null) {
          this.root = null;
          return;
        }

        const pdim = node.parent.dimension;

        if (node.obj.get(pdim) < node.parent.obj.get(pdim)) {
          node.parent.left = null;
        } else {
          node.parent.right = null;
        }
        return;
      }

      // If the right subtree is not empty, swap with the minimum element on the
      // node's dimension. If it is empty, we swap the left and right subtrees and
      // do the same.
      let nextNode, nextObj;

      if (node.right !== null) {
        nextNode = findMin(node.right, node.dimension);
        nextObj = nextNode.obj;
        removeNode(nextNode);
        node.obj = nextObj;
      } else {
        nextNode = findMin(node.left, node.dimension);
        nextObj = nextNode.obj;
        removeNode(nextNode);
        node.right = node.left;
        node.left = null;
        node.obj = nextObj;
      }

    }

    nearest(point, maxNodes, maxDistance) {
      let i, result, bestNodes;

      if (maxNodes === undefined) {
        maxNodes = 1;
      }

      bestNodes = new BinaryHeap(function (e) { return -e[1]; });

      const nearestSearch = (node) => {
        const ownDistance = self.metric(point, node.obj);
        const dimension = node.dimension;
        const pointType = node.obj.type;
        const linearPoint = pointType.empty();

        let otherChild, linearDistance, bestChild, i;

        function saveNode(node, distance) {
          bestNodes.push([node, distance]);
          if (bestNodes.size() > maxNodes) {
            bestNodes.pop();
          }
        }

        for (i = 0; i < this.dimensions; i += 1) {
          if (i === node.dimension) {
            linearPoint.set(i, point.get(i));
          } else {
            linearPoint.set(i, node.obj.get(i));
          }
        }

        linearDistance = this.metric(linearPoint, node.obj);

        if (node.right === null && node.left === null) {
          if (bestNodes.size() < maxNodes || ownDistance < bestNodes.peek()[1]) {
            saveNode(node, ownDistance);
          }
          return;
        }

        if (node.right === null) {
          bestChild = node.left;
        } else if (node.left === null) {
          bestChild = node.right;
        } else {
          if (point.get(dimension) < node.obj.get(dimension)) {
            bestChild = node.left;
          } else {
            bestChild = node.right;
          }
        }

        nearestSearch(bestChild);

        if (bestNodes.size() < maxNodes || ownDistance < bestNodes.peek()[1]) {
          saveNode(node, ownDistance);
        }

        if (bestNodes.size() < maxNodes || Math.abs(linearDistance) < bestNodes.peek()[1]) {
          if (bestChild === node.left) {
            otherChild = node.right;
          } else {
            otherChild = node.left;
          }
          if (otherChild !== null) {
            nearestSearch(otherChild);
          }
        }
      }

      if (maxDistance) {
        for (i = 0; i < maxNodes; i += 1) {
          bestNodes.push([null, maxDistance]);
        }
      }

      if(this.root) {
        nearestSearch(this.root);
      }

      result = [];

      for (i = 0; i < Math.min(maxNodes, bestNodes.content.length); i += 1) {
        if (bestNodes.content[i][0]) {
          result.push([bestNodes.content[i][0].obj, bestNodes.content[i][1]]);
        }
      }
      return result;
    }

    balanceFactor() {
      function height(node) {
        if (node === null) {
          return 0;
        }
        return Math.max(height(node.left), height(node.right)) + 1;
      }

      function count(node) {
        if (node === null) {
          return 0;
        }
        return count(node.left) + count(node.right) + 1;
      }

      return height(this.root) / (Math.log(count(this.root)) / Math.log(2));
    }
  }

  // Binary heap implementation from:
  // http://eloquentjavascript.net/appendix2.html

  function BinaryHeap(scoreFunction){
    this.content = [];
    this.scoreFunction = scoreFunction;
  }

  BinaryHeap.prototype = {
    push: function(element) {
      // Add the new element to the end of the array.
      this.content.push(element);
      // Allow it to bubble up.
      this.bubbleUp(this.content.length - 1);
    },

    pop: function() {
      // Store the first element so we can return it later.
      var result = this.content[0];
      // Get the element at the end of the array.
      var end = this.content.pop();
      // If there are any elements left, put the end element at the
      // start, and let it sink down.
      if (this.content.length > 0) {
        this.content[0] = end;
        this.sinkDown(0);
      }
      return result;
    },

    peek: function() {
      return this.content[0];
    },

    remove: function(node) {
      var len = this.content.length;
      // To remove a value, we must search through the array to find
      // it.
      for (var i = 0; i < len; i++) {
        if (this.content[i] == node) {
          // When it is found, the process seen in 'pop' is repeated
          // to fill up the hole.
          var end = this.content.pop();
          if (i != len - 1) {
            this.content[i] = end;
            if (this.scoreFunction(end) < this.scoreFunction(node))
              this.bubbleUp(i);
            else
              this.sinkDown(i);
          }
          return;
        }
      }
      throw new Error("Node not found.");
    },

    size: function() {
      return this.content.length;
    },

    bubbleUp: function(n) {
      // Fetch the element that has to be moved.
      var element = this.content[n];
      // When at 0, an element can not go up any further.
      while (n > 0) {
        // Compute the parent element's index, and fetch it.
        var parentN = Math.floor((n + 1) / 2) - 1,
            parent = this.content[parentN];
        // Swap the elements if the parent is greater.
        if (this.scoreFunction(element) < this.scoreFunction(parent)) {
          this.content[parentN] = element;
          this.content[n] = parent;
          // Update 'n' to continue at the new position.
          n = parentN;
        }
        // Found a parent that is less, no need to move it further.
        else {
          break;
        }
      }
    },

    sinkDown: function(n) {
      // Look up the target element and its score.
      var length = this.content.length,
          element = this.content[n],
          elemScore = this.scoreFunction(element);

      while(true) {
        // Compute the indices of the child elements.
        var child2N = (n + 1) * 2, child1N = child2N - 1;
        // This is used to store the new position of the element,
        // if any.
        var swap = null;
        // If the first child exists (is inside the array)...
        if (child1N < length) {
          // Look it up and compute its score.
          var child1 = this.content[child1N],
              child1Score = this.scoreFunction(child1);
          // If the score is less than our element's, we need to swap.
          if (child1Score < elemScore)
            swap = child1N;
        }
        // Do the same checks for the other child.
        if (child2N < length) {
          var child2 = this.content[child2N],
              child2Score = this.scoreFunction(child2);
          if (child2Score < (swap == null ? elemScore : child1Score)){
            swap = child2N;
          }
        }

        // If the element needs to be moved, swap it, and continue.
        if (swap != null) {
          this.content[n] = this.content[swap];
          this.content[swap] = element;
          n = swap;
        }
        // Otherwise, we are done.
        else {
          break;
        }
      }
    }
  };

  function distance2d(a, b){
    return Math.pow(a.x - b.x, 2) +  Math.pow(a.y - b.y, 2);
  }

  function point2d(x, y, data) {
    return new Point2d(x, y, data);
  }

  function create2d(points) {
    return new KDTree(points, distance2d, 2);
  };

  // Types
  kdtree.KDTree = KDTree;

  // Factory functions
  kdtree.point2d = point2d;
  kdtree.create2d = create2d;
});
