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
import { sanitizeFontFamily } from "./Style.js";

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
    const textSpan = createTextSpan(
      new Text(currentNode.nodeValue),
      currentStyle,
    );
    const fontSize = textSpan.style.getPropertyValue("font-size");
    if (!fontSize) {
      console.warn("font-size", fontSize);
      textSpan.style.setProperty(
        "font-size",
        styleDefaults?.getPropertyValue("font-size") ?? DEFAULT_FONT_SIZE,
      );
    }
    let fontFamily = textSpan.style.getPropertyValue("font-family");
    if (!fontFamily) {
      console.warn("font-family", fontFamily);
      fontFamily =
        styleDefaults?.getPropertyValue("font-family") ?? DEFAULT_FONT_FAMILY;
    }

    fontFamily = sanitizeFontFamily(fontFamily);
    textSpan.style.setProperty("font-family", fontFamily);

    const fontWeight = textSpan.style.getPropertyValue("font-weight");
    if (!fontWeight) {
      console.warn("font-weight", fontWeight);
      textSpan.style.setProperty(
        "font-weight",
        styleDefaults?.getPropertyValue("font-weight") ?? DEFAULT_FONT_WEIGHT,
      );
    }
    const fills = textSpan.style.getPropertyValue("--fills");
    if (!fills) {
      console.warn("fills", fills);
      textSpan.style.setProperty(
        "--fills",
        styleDefaults?.getPropertyValue("--fills") ?? DEFAULT_FILLS,
      );
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
 * Converts HTML to plain text, preserving line breaks from <br> tags and block elements.
 *
 * @param {string} html - The HTML string to convert
 * @returns {string} Plain text with preserved line breaks
 */
export function htmlToText(html) {
  const tmp = document.createElement("div");
  tmp.innerHTML = html;

  const blockTags = [
    "P",
    "DIV",
    "SECTION",
    "ARTICLE",
    "HEADER",
    "FOOTER",
    "UL",
    "OL",
    "LI",
    "TABLE",
    "TR",
    "TD",
    "TH",
    "PRE",
  ];

  function walk(node) {
    let text = "";

    node.childNodes.forEach((child) => {
      if (child.nodeType === Node.TEXT_NODE) {
        text += child.textContent;
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        if (child.tagName === "BR") {
          text += "\n";
        }

        if (blockTags.includes(child.tagName)) {
          text += "\n" + walk(child) + "\n";
          return;
        }

        text += walk(child);
      }
    });

    return text;
  }

  let result = walk(tmp);
  result = result.replace(/\n{3,}/g, "\n\n");

  return result.trim();
}

/**
 * Maps any HTML into a valid content DOM element.
 *
 * @param {string} html
 * @param {CSSStyleDeclaration} [styleDefaults]
 * @param {boolean} [allowHTMLPaste=false]
 * @returns {DocumentFragment}
 */
export function mapContentFragmentFromHTML(
  html,
  styleDefaults,
  allowHTMLPaste,
) {
  if (allowHTMLPaste) {
    try {
      const parser = new DOMParser();
      const document = parser.parseFromString(html, "text/html");
      return mapContentFragmentFromDocument(document, styleDefaults);
    } catch (error) {
      console.error("Couldn't parse HTML", html, error);
      const plainText = htmlToText(html);
      return mapContentFragmentFromString(plainText, styleDefaults);
    }
  }
  const plainText = htmlToText(html);
  return mapContentFragmentFromString(plainText, styleDefaults);
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
