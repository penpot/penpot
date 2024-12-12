/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { createInline } from "./Inline.js";
import {
  createEmptyParagraph,
  createParagraph,
  isLikeParagraph,
} from "./Paragraph.js";
import { isDisplayBlock, normalizeStyles } from "./Style.js";

/**
 * Maps any HTML into a valid content DOM element.
 *
 * @param {Document} document
 * @param {HTMLElement} root
 * @param {CSSStyleDeclaration} [styleDefaults]
 * @returns {DocumentFragment}
 */
export function mapContentFragmentFromDocument(document, root, styleDefaults) {
  const nodeIterator = document.createNodeIterator(
    root,
    NodeFilter.SHOW_TEXT
  );
  const fragment = document.createDocumentFragment();

  let currentParagraph = null;
  let currentNode = nodeIterator.nextNode();
  while (currentNode) {
    // We cannot call document.defaultView because it is `null`.
    const currentStyle = normalizeStyles(currentNode, styleDefaults);
    if (
      isDisplayBlock(currentNode.parentElement.style) ||
      isDisplayBlock(currentStyle) ||
      isLikeParagraph(currentNode.parentElement)
    ) {
      if (currentParagraph) {
        fragment.appendChild(currentParagraph);
      }
      currentParagraph = createParagraph(undefined, currentStyle);
    } else {
      if (currentParagraph === null) {
        currentParagraph = createParagraph(undefined, currentStyle);
      }
    }

    const inline = createInline(new Text(currentNode.nodeValue), currentStyle);
    const fontSize = inline.style.getPropertyValue("font-size");
    if (!fontSize) console.warn("font-size", fontSize);
    currentParagraph.appendChild(inline);

    currentNode = nodeIterator.nextNode();
  }

  fragment.appendChild(currentParagraph);
  return fragment;
}

/**
 * Maps any HTML into a valid content DOM element.
 *
 * @param {string} html
 * @param {CSSStyleDeclaration} [styleDefaults]
 * @returns {DocumentFragment}
 */
export function mapContentFragmentFromHTML(html, styleDefaults) {
  const parser = new DOMParser();
  const htmlDocument = parser.parseFromString(html, "text/html");
  return mapContentFragmentFromDocument(
    htmlDocument,
    htmlDocument.documentElement,
    styleDefaults
  );
}

/**
 * Maps a plain text into a valid content DOM element.
 *
 * @param {string} string
 * @param {CSSStyleDeclaration} [styleDefaults]
 * @returns {DocumentFragment}
 */
export function mapContentFragmentFromString(string, styleDefaults) {
  const lines = string.replace(/\r/g, "").split("\n");
  const fragment = document.createDocumentFragment();
  for (const line of lines) {
    if (line === "") {
      fragment.appendChild(createEmptyParagraph(styleDefaults));
    } else {
      fragment.appendChild(
        createParagraph([
          createInline(new Text(line), styleDefaults)
        ], styleDefaults)
      );
    }
  }
  return fragment;
}
