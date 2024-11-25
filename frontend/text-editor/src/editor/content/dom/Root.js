/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { createRandomId, createElement, isElement } from "./Element.js";
import { createEmptyParagraph, isParagraph } from "./Paragraph.js";
import { setStyles } from "./Style.js";

export const TAG = "DIV";
export const TYPE = "root";
export const QUERY = `[data-itype="${TYPE}"]`;
export const STYLES = [["--vertical-align"]];

/**
 * Returns true if passed node is a root.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isRoot(node) {
  if (!node) return false;
  if (!isElement(node, TAG)) return false;
  if (node.dataset.itype !== TYPE) return false;
  return true;
}

/**
 * Create a new root element
 *
 * @param {Array<HTMLDivElement>} paragraphs
 * @param {Object.<string, *>|CSSStyleDeclaration} styles,
 * @param {Object.<string, *>} [attrs]
 * @returns {HTMLDivElement}
 */
export function createRoot(paragraphs, styles, attrs) {
  if (!Array.isArray(paragraphs) || !paragraphs.every(isParagraph))
    throw new TypeError("Invalid root children");

  return createElement(TAG, {
    attributes: { id: createRandomId(), ...attrs },
    data: { itype: TYPE },
    styles: styles,
    allowedStyles: STYLES,
    children: paragraphs,
  });
}

/**
 * Creates a new empty root element
 *
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 */
export function createEmptyRoot(styles) {
  return createRoot([createEmptyParagraph(styles)], styles);
}

/**
 * Sets the root styles.
 *
 * @param {HTMLDivElement} element
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLDivElement}
 */
export function setRootStyles(element, styles) {
  return setStyles(element, STYLES, styles);
}
