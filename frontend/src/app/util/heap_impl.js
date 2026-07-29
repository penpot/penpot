/**
 * kdtree
 *
 * Is a modified and google closure adapted kdtree implementation
 * of https://github.com/ubilabs/kd-tree-javascript.
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

"use strict";

goog.provide("app.util.heap_impl");
goog.provide("app.util.heap_impl.MinHeap");

goog.scope(function() {
  const self = app.util.heap_impl;

  const compare = (x, y) => x - y;

  class MinHeap {
    constructor(cmp) {
      this.cmp = cmp || compare;
      this.heap = [];
      this.size = 0;
    }

    insert(item) {
      const heap  = this.heap;

      let index = this.size++;
      let parent = (index-1)>>1;

      heap[index] = item;

      while ((index > 0) && this.cmp(heap[parent], item) > 0) {
        const tmp = heap[parent];
        heap[parent] = heap[index];
        heap[index] = tmp;
        index = parent;
        parent = (index-1)>>1;
      }
    }

    isEmpty() {
      return this.size === 0;
    }

    peek() {
      return this.heap[0];
    }

    removeHead() {
      const heap = this.heap;
      const cmp = this.cmp;

      if (this.size === 0) {
        return null;
      }

      const head = heap[0];

      this._bubble(0);
      return head;
    }

    remove(item) {
      const heap = this.heap;

      for (let i = 0; i < this.size; ++i) {
        if (heap[i] === item) {
          this._bubble(i);
          return true;
        }
      }

      return false;
    }

    _bubble(index) {
      const heap = this.heap;
      const cmp = this.cmp;

      heap[index] = heap[--this.size];
      heap[this.size] = null;

      while (true) {

      const leftIndex = (index<<1)+1;
        const rightIndex = (index<<1)+2;
        let minIndex = index;

        if (leftIndex < this.size && cmp(heap[leftIndex], heap[minIndex]) < 0) {
          minIndex = leftIndex;
        }

        if (rightIndex < this.size && cmp(heap[rightIndex], heap[minIndex]) < 0) {
          minIndex = rightIndex;
        }

        if (minIndex !== index) {
          const tmp = heap[index];
          heap[index] = heap[minIndex];
          heap[minIndex] = tmp;
          index = minIndex;
        } else {
          break;
        }
      }
    }
  }

  self.MinHeap = MinHeap;
});
