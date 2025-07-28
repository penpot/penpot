/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import {
  createRandomId,
  createElement,
  isElement,
  isOffsetAtStart,
  isOffsetAtEnd,
} from "./Element.js";
import {
  isInline,
  isLikeInline,
  getInline,
  getInlinesFrom,
  createInline,
  createEmptyInline,
  isInlineEnd,
  splitInline,
} from "./Inline.js";
import { createLineBreak, isLineBreak } from "./LineBreak.js";
import { setStyles } from "./Style.js";

export const TAG = "DIV";
export const TYPE = "paragraph";
export const QUERY = `[data-itype="${TYPE}"]`;
export const STYLES = [
  ["--typography-ref-id"],
  ["--typography-ref-file"],
  ["--font-id"],
  ["--font-variant-id"],
  ["--fills"],
  ["font-variant"],
  ["font-family"],
  ["font-size", "px"],
  ["font-weight"],
  ["font-style"],
  ["line-height"],
  ["letter-spacing", "px"],
  ["text-decoration"],
  ["text-transform"],
  ["text-align"],
  ["direction"],
];

/**
 * FIXME: This is a fix for Chrome that removes the
 * current inline when the last character is deleted
 * in `insertCompositionText`.
 *
 * @param {*} node
 */
export function fixParagraph(node) {
  if (!isParagraph(node) || !isLineBreak(node.firstChild)) {
    return;
  }
  const br = createLineBreak();
  node.replaceChildren(createInline(br));
  return br;
}

/**
 * Returns true if the passed node behaves like a paragraph.
 *
 * NOTE: This is mainly used in paste operations. Every element node
 * it's going to be treated as paragraph it
 *
 * @param {Node} element
 * @returns {boolean}
 */
export function isLikeParagraph(element) {
  return !isLikeInline(element);
}

/**
 * Returns true if we have an empty paragraph.
 *
 * @param {Node} element
 * @returns {boolean}
 */
export function isEmptyParagraph(element) {
  if (!isParagraph(element)) throw new TypeError("Invalid paragraph");
  const inline = element.firstChild;
  if (!isInline(inline)) throw new TypeError("Invalid inline");
  return isLineBreak(inline.firstChild);
}

/**
 * Returns true if passed node is a paragraph.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isParagraph(node) {
  if (!node) return false;
  if (!isElement(node, TAG)) return false;
  if (node.dataset.itype !== TYPE) return false;
  return true;
}

/**
 * Creates a new paragraph.
 *
 * @param {Array<HTMLDivElement>} inlines
 * @param {Object.<string, *>|CSSStyleDeclaration} styles
 * @param {Object.<string, *>} [attrs]
 * @returns {HTMLDivElement}
 */
export function createParagraph(inlines, styles, attrs) {
  if (inlines && (!Array.isArray(inlines) || !inlines.every(isInline)))
    throw new TypeError("Invalid paragraph children");
  return createElement(TAG, {
    attributes: { id: createRandomId(), ...attrs },
    data: { itype: TYPE },
    styles: styles,
    allowedStyles: STYLES,
    children: inlines,
  });
}

/**
 * Returns a new empty paragraph
 *
 * @param {Object.<string, *>} styles
 * @returns {HTMLDivElement}
 */
export function createEmptyParagraph(styles) {
  return createParagraph([createEmptyInline(styles)], styles);
}

/**
 * Sets the paragraph styles.
 *
 * @param {HTMLDivElement} element
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLDivElement}
 */
export function setParagraphStyles(element, styles) {
  return setStyles(element, STYLES, styles);
}

/**
 * Gets the closest paragraph from a node.
 *
 * @param {Text|HTMLBRElement} node
 * @returns {HTMLElement|null}
 */
export function getParagraph(node) {
  if (!node) return null;
  if (isParagraph(node)) return node;
  if (node.nodeType === Node.TEXT_NODE || isLineBreak(node)) {
    const paragraph = node?.parentElement?.parentElement;
    if (!paragraph) {
      return null;
    }
    if (!isParagraph(paragraph)) {
      return null;
    }
    return paragraph;
  }
  return node.closest(QUERY);
}

/**
 * Returns if the specified node and offset represents
 * the start of the paragraph.
 *
 * @param {Text|HTMLBRElement} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isParagraphStart(node, offset) {
  const paragraph = getParagraph(node);
  if (!paragraph) throw new Error("Can't find the paragraph");
  const inline = getInline(node);
  if (!inline) throw new Error("Can't find the inline");
  return (
    paragraph.firstElementChild === inline &&
    isOffsetAtStart(inline.firstChild, offset)
  );
}

/**
 * Returns if the specified node and offset represents
 * the end of the paragraph.
 *
 * @param {Text|HTMLBRElement} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isParagraphEnd(node, offset) {
  const paragraph = getParagraph(node);
  if (!paragraph) throw new Error("Cannot find the paragraph");
  const inline = getInline(node);
  if (!inline) throw new Error("Cannot find the inline");
  return (
    paragraph.lastElementChild === inline &&
    isOffsetAtEnd(inline.firstChild, offset)
  );
}

/**
 * Splits a paragraph.
 *
 * @param {HTMLDivElement} paragraph
 * @param {HTMLSpanElement} inline
 * @param {number} offset
 */
export function splitParagraph(paragraph, inline, offset) {
  const style = paragraph.style;
  if (isInlineEnd(inline, offset)) {
    const newParagraph = createParagraph(getInlinesFrom(inline), style);
    return newParagraph;
  }
  const newInline = splitInline(inline, offset);
  const newParagraph = createParagraph([newInline], style);
  return newParagraph;
}

/**
 * Splits a paragraph at a specified child node index
 *
 * @param {HTMLDivElement} paragraph
 * @param {number} startIndex
 */
export function splitParagraphAtNode(paragraph, startIndex) {
  const style = paragraph.style;
  const newParagraph = createParagraph(null, style);
  const newInlines = [];
  for (let index = startIndex; index < paragraph.children.length; index++) {
    newInlines.push(paragraph.children.item(index));
  }
  newParagraph.append(...newInlines);
  return newParagraph;
}

/**
 * Merges two paragraphs.
 *
 * @param {HTMLDivElement} a
 * @param {HTMLDivElement} b
 * @returns {HTMLDivElement}
 */
export function mergeParagraphs(a, b) {
  a.append(...b.children);
  b.remove();
  return a;
}
