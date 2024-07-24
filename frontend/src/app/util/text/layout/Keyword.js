/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import cljs from "goog:cljs.core";

/**
 * Keywords used by the content nodes.
 */
export const Keyword = {
  // Vertical align
  VERTICAL_ALIGN: cljs.keyword("vertical-align"),

  // Used in content.
  TYPE: cljs.keyword("type"),
  KEY: cljs.keyword("key"),
  CHILDREN: cljs.keyword("children"),

  // Common.
  FONT_ID: cljs.keyword("font-id"),
  FONT_FAMILY: cljs.keyword("font-family"),
  FONT_VARIANT_ID: cljs.keyword("font-variant-id"),
  FONT_SIZE: cljs.keyword("font-size"),
  FONT_WEIGHT: cljs.keyword("font-weight"),
  FONT_STYLE: cljs.keyword("font-style"),
  TEXT: cljs.keyword("text"),
  TEXT_TRANSFORM: cljs.keyword("text-transform"),
  TEXT_ALIGN: cljs.keyword("text-align"),
  TEXT_DECORATION: cljs.keyword("text-decoration"),
  TYPOGRAPHY_REF_ID: cljs.keyword("typography-ref-id"),
  TYPOGRAPHY_REF_FILE: cljs.keyword("typography-ref-file"),
  LINE_HEIGHT: cljs.keyword("line-height"),
  LETTER_SPACING: cljs.keyword("letter-spacing"),
  FILLS: cljs.keyword("fills"),

  // Fills.
  FILL_COLOR: cljs.keyword("fill-color"),
  FILL_OPACITY: cljs.keyword("fill-opacity"),
  FILL_IMAGE: cljs.keyword("fill-image"),
  FILL_REF_ID: cljs.keyword("fill-ref-id"),
  FILL_REF_FILE: cljs.keyword("fill-ref-file"),
  FILL_COLOR_GRADIENT: cljs.keyword("fill-color-gradient"),

  // Used in positional data.
  FONT_VARIANT: cljs.keyword("font-variant"),
  DIRECTION: cljs.keyword("direction"),
  X: cljs.keyword("x"),
  Y: cljs.keyword("y"),
  X1: cljs.keyword("x1"),
  Y1: cljs.keyword("y1"),
  X2: cljs.keyword("x2"),
  Y2: cljs.keyword("y2"),
  WIDTH: cljs.keyword("width"),
  HEIGHT: cljs.keyword("height"),
};

export default Keyword;
