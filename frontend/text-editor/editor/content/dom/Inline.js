/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import {
  createElement,
  isElement,
  isOffsetAtStart,
  isOffsetAtEnd,
} from "./Element.js";
import { createLineBreak, isLineBreak } from "./LineBreak.js";
import { setStyles, mergeStyles } from "./Style.js";
import { createRandomId } from "./Element.js";

export const TAG = "SPAN";
export const TYPE = "inline";
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
];

/**
 * Returns true if passed node is an inline.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isInline(node) {
  if (!node) return false;
  if (!isElement(node, TAG)) return false;
  if (node.dataset.itype !== TYPE) return false;
  return true;
}

/**
 * Returns true if the passed node "behaves" like an
 * inline.
 *
 * @param {Node} element
 * @returns {boolean}
 */
export function isLikeInline(element) {
  return element
    ? [
        "A",
        "ABBR",
        "ACRONYM",
        "B",
        "BDO",
        "BIG",
        "BR",
        "BUTTON",
        "CITE",
        "CODE",
        "DFN",
        "EM",
        "I",
        "IMG",
        "INPUT",
        "KBD",
        "LABEL",
        "MAP",
        "OBJECT",
        "OUTPUT",
        "Q",
        "SAMP",
        "SCRIPT",
        "SELECT",
        "SMALL",
        "SPAN",
        "STRONG",
        "SUB",
        "SUP",
        "TEXTAREA",
        "TIME",
        "TT",
        "VAR",
      ].includes(element.nodeName)
    : false;
}

/**
 * Creates a new Inline
 *
 * @param {Text|HTMLBRElement} text
 * @param {Object.<string, *>|CSSStyleDeclaration} styles
 * @param {Object.<string, *>} [attrs]
 * @returns {HTMLSpanElement}
 */
export function createInline(textOrLineBreak, styles, attrs) {
  if (
    !(textOrLineBreak instanceof HTMLBRElement) &&
    !(textOrLineBreak instanceof Text)
  ) {
    throw new TypeError("Invalid inline child");
  }
  if (textOrLineBreak instanceof Text
   && textOrLineBreak.nodeValue.length === 0) {
    console.trace("nodeValue", textOrLineBreak.nodeValue)
    throw new TypeError("Invalid inline child, cannot be an empty text");
  }
  return createElement(TAG, {
    attributes: { id: createRandomId(), ...attrs },
    data: { itype: TYPE },
    styles: styles,
    allowedStyles: STYLES,
    children: textOrLineBreak,
  });
}

/**
 * Creates a new inline from an older inline. This only
 * merges styles from the older inline to the new inline.
 *
 * @param {HTMLSpanElement} inline
 * @param {Object.<string, *>} textOrLineBreak
 * @param {Object.<string, *>|CSSStyleDeclaration} styles
 * @param {Object.<string, *>} [attrs]
 * @returns {HTMLSpanElement}
 */
export function createInlineFrom(inline, textOrLineBreak, styles, attrs) {
  return createInline(
    textOrLineBreak,
    mergeStyles(STYLES, inline.style, styles),
    attrs
  );
}

/**
 * Creates a new empty inline.
 *
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLSpanElement}
 */
export function createEmptyInline(styles) {
  return createInline(createLineBreak(), styles);
}

/**
 * Sets the inline styles.
 *
 * @param {HTMLSpanElement} element
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLSpanElement}
 */
export function setInlineStyles(element, styles) {
  return setStyles(element, STYLES, styles);
}

/**
 * Gets the closest inline from a node.
 *
 * @param {Node} node
 * @returns {HTMLElement|null}
 */
export function getInline(node) {
  if (!node) return null; // FIXME: Should throw?
  if (isInline(node)) return node;
  if (node.nodeType === Node.TEXT_NODE) {
    const inline = node?.parentElement;
    if (!inline) return null;
    if (!isInline(inline)) return null;
    return inline;
  }
  return node.closest(QUERY);
}

/**
 * Returns true if we are at the start offset
 * of an inline.
 *
 * NOTE: Only the first inline returns this as true
 *
 * @param {TextNode|HTMLBRElement} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isInlineStart(node, offset) {
  const inline = getInline(node);
  if (!inline) return false;
  return isOffsetAtStart(inline, offset);
}

/**
 * Returns true if we are at the end offset
 * of an inline.
 *
 * @param {TextNode|HTMLBRElement} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isInlineEnd(node, offset) {
  const inline = getInline(node);
  if (!inline) return false;
  return isOffsetAtEnd(inline.firstChild, offset);
}

/**
 * Splits an inline.
 *
 * @param {HTMLSpanElement} inline
 * @param {number} offset
 */
export function splitInline(inline, offset) {
  const textNode = inline.firstChild;
  const style = inline.style;
  const newTextNode = textNode.splitText(offset);
  return createInline(newTextNode, style);
}

/**
 * Returns all the inlines of a paragraph starting at
 * the specified inline.
 *
 * @param {HTMLSpanElement} startInline
 * @returns {Array<HTMLSpanElement>}
 */
export function getInlinesFrom(startInline) {
  const inlines = [];
  let currentInline = startInline;
  let index = 0;
  while (currentInline) {
    if (index > 0) inlines.push(currentInline);
    currentInline = currentInline.nextElementSibling;
    index++;
  }
  return inlines;
}

/**
 * Returns the length of an inline.
 *
 * @param {HTMLElement} inline
 * @returns {number}
 */
export function getInlineLength(inline) {
  if (!isInline(inline)) throw new Error("Invalid inline");
  if (isLineBreak(inline.firstChild)) return 0;
  return inline.firstChild.nodeValue.length;
}

/**
 * Merges two inlines.
 *
 * @param {HTMLSpanElement} a
 * @param {HTMLSpanElement} b
 * @returns {HTMLSpanElement}
 */
export function mergeInlines(a, b) {
  a.append(...b.childNodes);
  b.remove();
  // We need to normalize Text nodes.
  a.normalize();
  return a;
}
