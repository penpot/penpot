/**
 * lru - an LRU (least recently used) cache implementation.
 *
 * Implemented using double-linked list and hash-map that
 * gives O(1) time complexity and O(n) in space usage.
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

"use strict";

goog.provide("uxbox.util.lru_impl");
goog.require("goog.asserts");

goog.scope(function() {
  const self = uxbox.util.lru_impl;
  const assert = goog.asserts.assert;

  class Node {
    constructor(key, value) {
      this.key = key;
      this.value = value;

      this.prev = null;
      this.next = null;
    }
  }

  class Cache {
    constructor(limit) {
      assert(goog.isNumber(limit), "`limit` should be a number")
      this.limit = limit
      this.clear();
    }

    clear() {
      this.map = new Map();
      this.size = 0;
      this.head = null;
      this.tail = null;
    }

    setHead(node) {
      assert(node instanceof Node, "Node instance expected");

      node.next = this.head;
      node.prev = null;
      if (this.head !== null) {
        this.head.prev = node;
      }
      this.head = node;
      if (this.tail === null) {
        this.tail = node;
      }
      this.size++;
      this.map.set(node.key, node);
      return this;
    }

    setItem(key, value) {
      const node = new Node(key, value);
      if (this.map.has(key)) {
        const item = this.map.get(key);
        item.value = node.value;
        this.removeItem(node.key);
      } else {
        if (this.size >= this.limit) {
          this.map.delete(key);
          this.size--;
          this.tail = this.tail.prev;
          this.tail.next = null;
        }
      }

      this.setHead(node);
      return this;
    }

    getItem(key) {
      if (this.map.has(key)) {
        const value = this.map.get(key).value;
        const node = new Node(key, value);
        this.removeItem(key);
        this.setHead(node);
        return value;
      } else {
        return undefined;
      }
    }

    removeItem(key) {
      if (!this.map.has(key)) {
        return this;
      }

      const node = this.map.get(key);
      if (node.prev !== null) {
        node.prev.next = node.next;
      } else {
        this.head = node.next;
      }

      if (node.next !== null) {
        node.next.prev = node.prev;
      } else {
        this.tail = node.prev;
      }

      this.map.delete(key);
      this.size--;

      return this;
    }
  }

  function create(limit) {
    limit = limit || 50;
    return new Cache(limit);
  }

  self.create = create;
  self.get = (c, key) => c.getItem(key);
  self.set = (c, key, val) => c.setItem(key, val);
  self.remove = (c, key) => c.removeItem(key);
});
