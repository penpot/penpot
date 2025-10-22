/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { createTextSpan, isLikeTextSpan } from "./TextSpan.js";
import {
  createEmptyParagraph,
  createParagraph,
  isLikeParagraph,
} from "./Paragraph.js";
import { isDisplayBlock, normalizeStyles } from "./Style.js";

const DEFAULT_FONT_SIZE = "14px";
const DEFAULT_FONT_WEIGHT = 400;
const DEFAULT_FONT_FAMILY = "sourcesanspro";
const DEFAULT_FILLS = '[["^ ","~:fill-color", "#000000","~:fill-opacity", 1]]';

/**
 * Returns if the content fragment should be treated as
 * text span content and not a paragraphed one.
 *
 * @returns {boolean}
 */
function isContentFragmentFromDocumentTextSpan(document) {
  const nodeIterator = document.createNodeIterator(
    document.documentElement,
    NodeFilter.SHOW_ELEMENT,
  );
  let currentNode = nodeIterator.nextNode();
  while (currentNode) {
    if (["HTML", "HEAD", "BODY"].includes(currentNode.nodeName)) {
      currentNode = nodeIterator.nextNode();
      continue;
    }

    if (!isLikeTextSpan(currentNode)) return false;

    currentNode = nodeIterator.nextNode();
  }
  return true;
}

/**
 * Maps any HTML into a valid content DOM element.
 *
 * @param {Document} document
 * @param {HTMLElement} root
 * @param {CSSStyleDeclaration} [styleDefaults]
 * @returns {DocumentFragment}
 */
export function mapContentFragmentFromDocument(document, root, styleDefaults) {
  const nodeIterator = document.createNodeIterator(root, NodeFilter.SHOW_TEXT);
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
    const textSpan = createTextSpan(new Text(currentNode.nodeValue), currentStyle);
    const fontSize = textSpan.style.getPropertyValue("font-size");
    if (!fontSize) {
      console.warn("font-size", fontSize);
      textSpan.style.setProperty("font-size", styleDefaults?.getPropertyValue("font-size") ?? DEFAULT_FONT_SIZE);
    }
    const fontFamily = textSpan.style.getPropertyValue("font-family");
    if (!fontFamily) {
      console.warn("font-family", fontFamily);
      textSpan.style.setProperty("font-family", styleDefaults?.getPropertyValue("font-family") ?? DEFAULT_FONT_FAMILY);
    }
    const fontWeight = textSpan.style.getPropertyValue("font-weight");
    if (!fontWeight) {
      console.warn("font-weight", fontWeight);
      textSpan.style.setProperty("font-weight", styleDefaults?.getPropertyValue("font-weight") ?? DEFAULT_FONT_WEIGHT)
    }
    const fills = textSpan.style.getPropertyValue('--fills');
    if (!fills) {
      console.warn("fills", fills);
      textSpan.style.setProperty("--fills", styleDefaults?.getPropertyValue("--fills") ?? DEFAULT_FILLS);
    }

    currentParagraph.appendChild(textSpan);

    currentNode = nodeIterator.nextNode();
  }

  if (currentParagraph) {
    fragment.appendChild(currentParagraph);
  }

  if (fragment.children.length === 1) {
    const isContentTextSpan = isContentFragmentFromDocumentTextSpan(document);
    if (isContentTextSpan) {
      currentParagraph.dataset.textSpan = "force";
    }
  }

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
    styleDefaults,
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
        createParagraph(
          [createTextSpan(new Text(line), styleDefaults)],
          styleDefaults,
        ),
      );
    }
  }
  return fragment;
}
