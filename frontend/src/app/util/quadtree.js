/**
 * Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>
 * Copyright (c) 2012-2020 Timo Hausmann
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.  THE
 * SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 **/

/**
 * Changes to the original code:
 * - Use ES6+.
 * - Add the Node class for manage childs.
 * - Use generators where is possible.
 **/

"use strict";

goog.provide("app.util.quadtree");
goog.require("cljs.core");

goog.scope(function() {
  const self = app.util.quadtree;
  const eq = cljs.core._EQ_;
  const contains = cljs.core.contains_QMARK_;

  class Node {
    constructor(id, bounds, data) {
      this.id = id;
      this.bounds = bounds;
      this.data = data;
    }
  }

  class Quadtree {
    constructor(bounds, maxObjects, maxLevels, level) {
      this.maxObjects = maxObjects || 10;
      this.maxLevels = maxLevels || 4;

      this.level = level || 0;
      this.bounds = bounds;

      this.objects = [];
      this.indexes = [];
    }

    split() {
      const nextLevel   = this.level + 1;
      const subWidth    = this.bounds.width/2;
      const subHeight   = this.bounds.height/2;
      const x           = this.bounds.x;
      const y           = this.bounds.y;

      //top right node
      this.indexes[0] = new Quadtree({
        x       : x + subWidth,
        y       : y,
        width   : subWidth,
        height  : subHeight
      }, this.maxObjects, this.maxLevels, nextLevel);

      //top left node
      this.indexes[1] = new Quadtree({
        x       : x,
        y       : y,
        width   : subWidth,
        height  : subHeight
      }, this.maxObjects, this.maxLevels, nextLevel);

      //bottom left node
      this.indexes[2] = new Quadtree({
        x       : x,
        y       : y + subHeight,
        width   : subWidth,
        height  : subHeight
      }, this.maxObjects, this.maxLevels, nextLevel);

      //bottom right node
      this.indexes[3] = new Quadtree({
        x       : x + subWidth,
        y       : y + subHeight,
        width   : subWidth,
        height  : subHeight
      }, this.maxObjects, this.maxLevels, nextLevel);
    }

    *getIndexes(rect) {
      const verticalMidpoint    = this.bounds.x + (this.bounds.width/2);
      const horizontalMidpoint  = this.bounds.y + (this.bounds.height/2);

      const startIsNorth = rect.y < horizontalMidpoint;
      const startIsWest  = rect.x < verticalMidpoint;
      const endIsEast    = rect.x + rect.width > verticalMidpoint;
      const endIsSouth   = rect.y + rect.height > horizontalMidpoint;

      //top-right quad
      if (startIsNorth && endIsEast) {
        yield this.indexes[0];
      }

      //top-left quad
      if (startIsWest && startIsNorth) {
        yield this.indexes[1]
      }

      //bottom-left quad
      if (startIsWest && endIsSouth) {
        yield this.indexes[2];
      }

      //bottom-right quad
      if (endIsEast && endIsSouth) {
        yield this.indexes[3];
      }
    }

    insert(node) {
      //if we have subindexes, call insert on matching subindexes
      if (this.indexes.length > 0) {
        for (const index of this.getIndexes(node.bounds)) {
          index.insert(node);
        }
      } else {
        //otherwise, store object here
        this.objects.push(node);

        // max objects reached
        if (this.objects.length > this.maxObjects
            && this.level < this.maxLevels) {

          //split if we don't already have subindexes
          if (this.indexes.length === 0) {
            this.split();
          }

          //add all objects to their corresponding subnode
          for (const obj of this.objects) {
            for (const index of this.getIndexes(obj.bounds)) {
              index.insert(obj);
            }
          }

          this.objects = [];
        }
      }
    }

    count() {
      if (this.indexes.length === 0) {
        return this.objects.length;
      } else {
        let sum = 0;
        for (const index of this.indexes) {
          sum += index.count();
        }

        return sum;
      }
    }

    *search(rect) {
      if (this.indexes.length === 0) {
        yield* this.objects;
      } else {
        for (const index of this.getIndexes(rect)) {
          yield* index.search(rect);
        }
      }
    }

    clear() {
      this.objects = [];
      this.indexes = [];
    }

    getObjects() {
      return this.objects;
    }
  }

  self.create = function(rect) {
    return new Quadtree(rect, 10, 4, 0);
  };

  self.insert = function(index, id, bounds, data) {
    const node = new Node(id, bounds, data);
    index.insert(node);
    return index;
  };

  self.clear = function(index) {
    index.clear();
    return index;
  };

  self.search = function*(index, rect) {
    const tmp = new Set();
    for (const item of index.search(rect)) {
      if (!tmp.has(item)) {
        tmp.add(item);
        yield item;
      }
    }
  };

  self.remove = function(index, id) {
    const result = self.create(index.bounds);

    for (let node of index.objects) {
      if (!eq(id, node.id)) {
        self.insert(result, node.id, node.bounds, node.data);
      }
    }

    return result;
  }

  // FIXME: Inefficient to recreate the index. Needs to be improved
  self.remove_all = function(index, ids) {
    const result = self.create(index.bounds);

    for (let node of self.search(index, index.bounds)) {
      if (!contains(ids, node.id)) {
        self.insert(result, node.id, node.bounds, node.data);
      }
    }

    return result;
  }

});
