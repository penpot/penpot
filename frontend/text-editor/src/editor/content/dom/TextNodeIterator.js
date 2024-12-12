/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Iterator direction.
 *
 * @enum {number}
 */
export const TextNodeIteratorDirection = {
  FORWARD: 1,
  BACKWARD: 0,
};

/**
 * TextNodeIterator
 */
export class TextNodeIterator {
  /**
   * Returns if a specific node is a text node.
   *
   * @param {Node} node
   * @returns {boolean}
   */
  static isTextNode(node) {
    return (
      node.nodeType === Node.TEXT_NODE ||
      (node.nodeType === Node.ELEMENT_NODE && node.nodeName === "BR")
    );
  }

  /**
   * Returns if a specific node is a container node.
   *
   * @param {Node} node
   * @returns {boolean}
   */
  static isContainerNode(node) {
    return node.nodeType === Node.ELEMENT_NODE && node.nodeName !== "BR";
  }

  /**
   * Finds a node from an initial node and down the tree.
   *
   * @param {Node} startNode
   * @param {Node} rootNode
   * @param {Set<Node>} skipNodes
   * @param {number} direction
   * @returns {Node}
   */
  static findDown(
    startNode,
    rootNode,
    skipNodes = new Set(),
    direction = TextNodeIteratorDirection.FORWARD
  ) {
    if (startNode === rootNode) {
      return TextNodeIterator.findDown(
        direction === TextNodeIteratorDirection.FORWARD
          ? startNode.firstChild
          : startNode.lastChild,
        rootNode,
        skipNodes,
        direction
      );
    }

    // NOTE: This should not use the SafeGuard
    // module.
    let safeGuard = Date.now();
    let currentNode = startNode;
    while (currentNode) {
      if (Date.now() - safeGuard >= 1000) {
        throw new Error("Iteration timeout");
      }
      if (skipNodes.has(currentNode)) {
        currentNode =
          direction === TextNodeIteratorDirection.FORWARD
            ? currentNode.nextSibling
            : currentNode.previousSibling;
        continue;
      }
      if (TextNodeIterator.isTextNode(currentNode)) {
        return currentNode;
      } else if (TextNodeIterator.isContainerNode(currentNode)) {
        return TextNodeIterator.findDown(
          direction === TextNodeIteratorDirection.FORWARD
            ? currentNode.firstChild
            : currentNode.lastChild,
          rootNode,
          skipNodes,
          direction
        );
      }
      currentNode =
        direction === TextNodeIteratorDirection.FORWARD
          ? currentNode.nextSibling
          : currentNode.previousSibling;
    }
    return null;
  }

  /**
   * Finds a node from an initial node and up the tree.
   *
   * @param {Node} startNode
   * @param {Node} rootNode
   * @param {Set} backTrack
   * @param {number} direction
   * @returns {Node}
   */
  static findUp(
    startNode,
    rootNode,
    backTrack = new Set(),
    direction = TextNodeIteratorDirection.FORWARD
  ) {
    backTrack.add(startNode);
    if (TextNodeIterator.isTextNode(startNode)) {
      return TextNodeIterator.findUp(
        startNode.parentNode,
        rootNode,
        backTrack,
        direction
      );
    } else if (TextNodeIterator.isContainerNode(startNode)) {
      const found = TextNodeIterator.findDown(
        startNode,
        rootNode,
        backTrack,
        direction
      );
      if (found) {
        return found;
      }
      if (startNode !== rootNode) {
        return TextNodeIterator.findUp(
          startNode.parentNode,
          rootNode,
          backTrack,
          direction
        );
      }
    }
    return null;
  }

  /**
   * This is the root text node.
   *
   * @type {HTMLElement}
   */
  #rootNode = null;

  /**
   * This is the current text node.
   *
   * @type {Text|null}
   */
  #currentNode = null;

  /**
   * Constructor
   *
   * @param {HTMLElement} rootNode
   */
  constructor(rootNode) {
    if (!(rootNode instanceof HTMLElement)) {
      throw new TypeError("Invalid root node");
    }
    this.#rootNode = rootNode;
    this.#currentNode = TextNodeIterator.findDown(rootNode, rootNode);
  }

  /**
   * Current node we're into.
   *
   * @type {TextNode|HTMLBRElement}
   */
  get currentNode() {
    return this.#currentNode;
  }

  set currentNode(newCurrentNode) {
    const isContained =
      (newCurrentNode.compareDocumentPosition(this.#rootNode) &
        Node.DOCUMENT_POSITION_CONTAINS) ===
      Node.DOCUMENT_POSITION_CONTAINS;
    if (
      !(newCurrentNode instanceof Node) ||
      !TextNodeIterator.isTextNode(newCurrentNode) ||
      !isContained
    ) {
      throw new TypeError("Invalid new current node");
    }
    this.#currentNode = newCurrentNode;
  }

  /**
   * Returns the next Text node or <br> element or null if there are.
   *
   * @returns {Text|HTMLBRElement}
   */
  nextNode() {
    if (!this.#currentNode) return null;

    const nextNode = TextNodeIterator.findUp(
      this.#currentNode,
      this.#rootNode,
      new Set(),
      TextNodeIteratorDirection.FORWARD
    );

    if (!nextNode) {
      return null;
    }

    this.#currentNode = nextNode;
    return this.#currentNode;
  }

  /**
   * Returns the previous Text node or <br> element or null.
   *
   * @returns {Text|HTMLBRElement}
   */
  previousNode() {
    if (!this.#currentNode) return null;

    const previousNode = TextNodeIterator.findUp(
      this.#currentNode,
      this.#rootNode,
      new Set(),
      TextNodeIteratorDirection.BACKWARD
    );

    if (!previousNode) {
      return null;
    }

    this.#currentNode = previousNode;
    return this.#currentNode;
  }
}

export default TextNodeIterator;
