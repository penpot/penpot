/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC Sucursal en España SL
 */

import { isTextSpan } from "./TextSpan.js";
import { isLineBreak } from "./LineBreak.js";
import { isParagraph } from "./Paragraph.js";
import { isEditor } from "./Editor.js";
import { isRoot } from "./Root.js";

/**
 * Returns true if the node is "like"
 * text, this means that it is a Text
 * node or a <br> element.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isTextNode(node) {
  if (!node) throw new TypeError("Invalid text node");
  return node.nodeType === Node.TEXT_NODE || isLineBreak(node);
}

/**
 * Returns true if the text node is empty.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isEmptyTextNode(node) {
  return node.nodeType === Node.TEXT_NODE && node.nodeValue === "";
}

/**
 * Returns the content length of the
 * node.
 *
 * @param {Node} node
 * @returns {number}
 */
export function getTextNodeLength(node) {
  if (!node) throw new TypeError("Invalid text node");
  if (isLineBreak(node)) return 0;
  return node.nodeValue.length;
}

/**
 * Gets the closest text node.
 *
 * @param {Node} node
 * @returns {Node}
 */
export function getClosestTextNode(node) {
  if (isTextNode(node)) return node;
  if (isTextSpan(node)) return node.firstChild;
  if (isParagraph(node)) return node.firstChild.firstChild;
  if (isRoot(node)) return node.firstChild.firstChild.firstChild;
  if (isEditor(node)) return node.firstChild.firstChild.firstChild.firstChild;
  throw new Error("Cannot find a text node");
}

/**
 * @typedef {Object} TextNodePosition
 * @property {Text|HTMLBRElement} node
 * @property {number} offset
 */

/**
 * Resolves a (node, offset) pair to an equivalent position on a text node
 * or a line break.
 *
 * Browsers are free to report a caret on a container element, in which case
 * the offset is a child index instead of a character index (Firefox does this
 * routinely, e.g. on empty paragraphs). This function walks down the content
 * tree to the addressed descendant so callers can always work with text
 * nodes.
 *
 * Unlike `getClosestTextNode`, this never throws: it returns `null` when the
 * position cannot be resolved, letting the caller decide the fallback.
 *
 * @param {Node} node
 * @param {number} [offset=0]
 * @returns {TextNodePosition|null}
 */
export function resolveTextNodePosition(node, offset = 0) {
  if (!node) return null;
  if (node.nodeType === Node.TEXT_NODE || isLineBreak(node)) {
    return { node, offset };
  }

  if (isTextSpan(node)) {
    // Within a text span the children are text nodes or a line break, so an
    // index past the last child means "at the end of the last child".
    const child = node.childNodes[offset];
    if (child) return resolveTextNodePosition(child, 0);
    const lastChild = node.lastChild;
    if (!lastChild) return null;
    if (lastChild.nodeType !== Node.TEXT_NODE && !isLineBreak(lastChild)) {
      return null;
    }
    return resolveTextNodePosition(lastChild, getTextNodeLength(lastChild));
  }

  if (isParagraph(node) || isRoot(node) || isEditor(node)) {
    const child = node.children[offset];
    if (child) return resolveTextNodePosition(child, 0);
    const lastChild = node.lastElementChild;
    if (!lastChild) return null;
    return resolveTextNodePosition(lastChild, lastChild.childNodes.length);
  }

  return null;
}
