/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { setStyles } from "./Style.js";

/**
 * @typedef {Object} CreateElementOptions
 * @property {Object.<string,*>} [attributes]
 * @property {Object.<string,*>} [data]
 * @property {Object.<string,*>|CSSStyleDeclaration} [styles]
 * @property {Array<[string,?string]>} [allowedStyles]
 * @property {Array|Node} [children]
 */

/**
 * Creates a new random id to identify content nodes.
 *
 * @returns {string}
 */
export function createRandomId() {
  return Math.floor(Math.random() * Number.MAX_SAFE_INTEGER).toString(36);
}

/**
 * Creates a new HTML element.
 *
 * @param {string} tag
 * @param {*} options
 * @returns {HTMLElement}
 */
export function createElement(tag, options) {
  const element = document.createElement(tag);
  if (options?.attributes) {
    Object.entries(options.attributes).forEach(([name, value]) =>
      element.setAttribute(name, value)
    );
  }
  if (options?.data) {
    Object.entries(options.data).forEach(
      ([name, value]) => (element.dataset[name] = value)
    );
  }
  if (options?.styles && options?.allowedStyles) {
    setStyles(element, options.allowedStyles, options.styles);
  }
  if (options?.children) {
    if (Array.isArray(options.children)) {
      element.append(...options.children);
    } else {
      element.appendChild(options.children);
    }
  }
  return element;
}

/**
 * Returns true if passed node is an element.
 *
 * @param {Node} element
 * @param {string} nodeName
 * @returns {boolean}
 */
export function isElement(element, nodeName) {
  return (
    element.nodeType === Node.ELEMENT_NODE &&
    element.nodeName === nodeName.toUpperCase()
  );
}

/**
 * Returns true if the specified offset is at the start of the element.
 *
 * @param {Node} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isOffsetAtStart(node, offset) {
  return offset === 0;
}

/**
 * Returns true if the specified offset is at the end of the element.
 *
 * @param {Node} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isOffsetAtEnd(node, offset) {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.nodeValue.length === offset;
  }
  return true;
}
