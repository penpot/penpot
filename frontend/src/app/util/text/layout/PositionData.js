/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import cljs from "goog:cljs.core";
import Keyword from "./Keyword";

/**
 * Returns a new function that maps values from a CSSStyleDeclaration.
 *
 * @param {string} styleName
 * @param {string} styleDefault
 * @returns {Function}
 */
function createStyleMapper(styleName, styleDefault) {
  return function styleMap(layoutNode) {
    const style = window.getComputedStyle(layoutNode.node);
    const value = style.getPropertyValue(styleName);
    if (styleName.startsWith("--") && value) {
      return value;
    }
    if (!value && styleDefault) {
      return styleDefault;
    }
    return value;
  };
}

/**
 * Returns a new function that maps values from a DOMRect.
 *
 * @param {string} propertyName
 * @returns {Function}
 */
function createRectMapper(propertyName) {
  return function rectMap(layoutNode) {
    return layoutNode.rect[propertyName];
  };
}

const DEFAULT_FILLS = cljs.PersistentHashMap.fromArrays(
  [Keyword.FILL_COLOR, Keyword.FILL_OPACITY],
  ["#000000", 1],
);

const LayoutNodeMap = [
  [Keyword.FILLS, createStyleMapper("--fills", DEFAULT_FILLS)],
  [Keyword.KEY, (layoutNode) => layoutNode.node.id],
  [Keyword.TEXT, (layoutNode) => layoutNode.text],
  [Keyword.DIRECTION, createStyleMapper("direction", "ltr")],
  [Keyword.FONT_FAMILY, createStyleMapper("font-family")],
  [Keyword.FONT_SIZE, createStyleMapper("font-size")],
  [Keyword.FONT_STYLE, createStyleMapper("font-style")],
  [Keyword.FONT_WEIGHT, createStyleMapper("font-weight")],
  [Keyword.FONT_VARIANT_ID, createStyleMapper("--font-variant-id")],
  [Keyword.TEXT_DECORATION, createStyleMapper("text-decoration")],
  [Keyword.TEXT_TRANSFORM, createStyleMapper("text-transform")],
  [Keyword.LINE_HEIGHT, createStyleMapper("line-height")],
  [Keyword.LETTER_SPACING, createStyleMapper("letter-spacing")],
  [Keyword.X, createRectMapper("x")],
  [Keyword.Y, createRectMapper("y")],
  [Keyword.WIDTH, createRectMapper("width")],
  [Keyword.HEIGHT, createRectMapper("height")],
  [Keyword.X1, createRectMapper("x")],
  [Keyword.Y1, createRectMapper("y")],
  [Keyword.X2, (layoutNode) => layoutNode.rect.x + layoutNode.rect.width],
  [Keyword.Y2, (layoutNode) => layoutNode.rect.y + layoutNode.rect.height],
];

/**
 * @typedef {object} LayoutNode
 * @property {string} text
 * @property {DOMRect} rect
 * @property {Node} node
 */

/**
 * Maps a LayoutNode returned by the text
 * layout functions into a PersistentHashMap
 * compatible with our ClojureScript functions.
 *
 * @param {LayoutNode} layoutNode
 * @returns {PersistentHashMap<Keyword, *>}
 */
export function mapLayoutNode(layoutNode) {
  return cljs.PersistentHashMap.fromArrays(
    ...LayoutNodeMap.reduce(
      (kvs, [keyword, mapFn]) => {
        kvs[0].push(keyword);
        kvs[1].push(mapFn(layoutNode));
        return kvs;
      },
      [[], []],
    ),
  );
}

export const PositionData = {
  mapLayoutNode,
};

export default PositionData;
