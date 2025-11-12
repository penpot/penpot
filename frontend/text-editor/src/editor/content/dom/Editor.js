/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { isElement } from "./Element.js";

export const TAG = "DIV";
export const TYPE = "editor";
export const QUERY = `[data-itype="${TYPE}"]`;

/**
 * Returns true if passed node is the editor element.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isEditor(node) {
  if (!node) return false;
  if (!isElement(node, TAG)) return false;
  if (node.dataset.itype !== TYPE) return false;
  return true;
}
