/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

export const TAG = "BR";

/**
 * Creates a new line break.
 *
 * @returns {HTMLBRElement}
 */
export function createLineBreak() {
  return document.createElement(TAG);
}

/**
 * Returns true if the passed node is a line break.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isLineBreak(node) {
  return node && node.nodeType === Node.ELEMENT_NODE && node.nodeName === TAG;
}
