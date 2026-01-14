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
 * Returns true if passed node is a text span.
 *
 * @param {Node} node
 * @returns {boolean}
 */
export function isTextSpan(node) {
  if (!node) return false;
  if (!isElement(node, TAG)) return false;
  if (node.dataset.itype !== TYPE) return false;
  return true;
}

/**
 * Returns true if the passed node "behaves" like a
 * text span.
 *
 * @param {Node} element
 * @returns {boolean}
 */
export function isLikeTextSpan(element) {
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
 * Creates a new TextSpan
 *
 * @param {Text|HTMLBRElement} text
 * @param {Object.<string, *>|CSSStyleDeclaration} styles
 * @param {Object.<string, *>} [attrs]
 * @returns {HTMLSpanElement}
 */
export function createTextSpan(textOrLineBreak, styles, attrs) {
  if (
    !(textOrLineBreak instanceof HTMLBRElement) &&
    !(textOrLineBreak instanceof Text)
  ) {
    throw new TypeError("Invalid text span child");
  }
  if (
    textOrLineBreak instanceof Text &&
    textOrLineBreak.nodeValue.length === 0
  ) {
    throw new TypeError("Invalid text span child, cannot be an empty text");
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
 * Creates a new text span from an older text span. This only
 * merges styles from the older text span to the new text span.
 *
 * @param {HTMLSpanElement} textSpan
 * @param {Object.<string, *>} textOrLineBreak
 * @param {Object.<string, *>|CSSStyleDeclaration} styles
 * @param {Object.<string, *>} [attrs]
 * @returns {HTMLSpanElement}
 */
export function createTextSpanFrom(textSpan, textOrLineBreak, styles, attrs) {
  return createTextSpan(
    textOrLineBreak,
    mergeStyles(STYLES, textSpan.style, styles),
    attrs,
  );
}

/**
 * Creates a new empty text span.
 *
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLSpanElement}
 */
export function createEmptyTextSpan(styles) {
  return createTextSpan(createLineBreak(), styles);
}

/**
 * Creates a new text span with an empty text. The difference between
 * this and the createEmptyTextSpan is that createEmptyTextSpan creates
 * a text span with a <br> inside.
 *
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLSpanElement}
 */
export function createVoidTextSpan(styles) {
  return createElement(TAG, {
    attributes: { id: createRandomId() },
    data: { itype: TYPE },
    styles: styles,
    allowedStyles: STYLES,
    children: new Text(""),
  });
}

/**
 * Sets the text span styles.
 *
 * @param {HTMLSpanElement} element
 * @param {Object.<string,*>|CSSStyleDeclaration} styles
 * @returns {HTMLSpanElement}
 */
export function setTextSpanStyles(element, styles) {
  return setStyles(element, STYLES, styles);
}

/**
 * Gets the closest text span from a node.
 *
 * @param {Node} node
 * @returns {HTMLElement|null}
 */
export function getTextSpan(node) {
  if (!node) return null; // FIXME: Should throw?
  if (isTextSpan(node)) return node;
  if (node.nodeType === Node.TEXT_NODE) {
    const textSpan = node?.parentElement;
    if (!textSpan) return null;
    if (!isTextSpan(textSpan)) return null;
    return textSpan;
  }
  return node.closest(QUERY);
}

/**
 * Returns true if we are at the start offset
 * of a text span.
 *
 * NOTE: Only the first text span returns this as true
 *
 * @param {TextNode|HTMLBRElement} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isTextSpanStart(node, offset) {
  const textSpan = getTextSpan(node);
  if (!textSpan) return false;
  return isOffsetAtStart(textSpan, offset);
}

/**
 * Returns true if we are at the end offset
 * of a text span.
 *
 * @param {TextNode|HTMLBRElement} node
 * @param {number} offset
 * @returns {boolean}
 */
export function isTextSpanEnd(node, offset) {
  const textSpan = getTextSpan(node);
  if (!textSpan) return false;
  return isOffsetAtEnd(textSpan.firstChild, offset);
}

/**
 * Splits a text span.
 *
 * @param {HTMLSpanElement} textSpan
 * @param {number} offset
 */
export function splitTextSpan(textSpan, offset) {
  const textNode = textSpan.firstChild;
  const style = textSpan.style;
  const newTextNode = textNode.splitText(offset);
  return createTextSpan(newTextNode, style);
}

/**
 * Returns all the text spans of a paragraph starting at
 * the specified text span.
 *
 * @param {HTMLSpanElement} startTextSpan
 * @returns {Array<HTMLSpanElement>}
 */
export function getTextSpansFrom(startTextSpan) {
  const textSpans = [];
  let currentTextSpan = startTextSpan;
  let index = 0;
  while (currentTextSpan) {
    if (index > 0) textSpans.push(currentTextSpan);
    currentTextSpan = currentTextSpan.nextElementSibling;
    index++;
  }
  return textSpans;
}

/**
 * Returns the length of a text span.
 *
 * @param {HTMLElement} textSpan
 * @returns {number}
 */
export function getTextSpanLength(textSpan) {
  if (!isTextSpan(textSpan)) throw new Error("Invalid text span");
  if (isLineBreak(textSpan.firstChild)) return 0;
  return textSpan.firstChild.nodeValue.length;
}

/**
 * Merges two text spans.
 *
 * @param {HTMLSpanElement} a
 * @param {HTMLSpanElement} b
 * @returns {HTMLSpanElement}
 */
export function mergeTextSpans(a, b) {
  a.append(...b.childNodes);
  b.remove();
  // We need to normalize Text nodes.
  a.normalize();
  return a;
}
