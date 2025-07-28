/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { isInline } from "./Inline.js";
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
  return node.nodeType === Node.TEXT_NODE
      || isLineBreak(node);
}

/**
 * Returns true if the text node is empty.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isEmptyTextNode(node) {
  return node.nodeType === Node.TEXT_NODE
      && node.nodeValue === "";
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
  if (isInline(node)) return node.firstChild;
  if (isParagraph(node)) return node.firstChild.firstChild;
  if (isRoot(node)) return node.firstChild.firstChild.firstChild;
  if (isEditor(node)) return node.firstChild.firstChild.firstChild.firstChild;
  throw new Error("Cannot find a text node");
}
