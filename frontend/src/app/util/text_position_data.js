/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

goog.provide("app.util.text_position_data");

goog.scope(function () {
  const self = app.util.text_position_data;
  const document = goog.global.document;

  // We do not need to create new ranges.
  const range = document.createRange();
  function getRangeRects(node, start, end) {
    range.setStart(node, start);
    range.setEnd(node, end);
    return [...range.getClientRects()].filter((r) => r.width > 0);
  }

  self.parse_text_nodes = function(parent, textNode, textAlign) {
    const content = textNode.textContent;
    const textSize = content.length;

    let from = 0;
    let to = 0;
    let current = "";
    let result = [];
    let prevRect = null;

    // This variable is to make sure there are not infinite loops
    // when we don't advance `to` we store true and then force to
    // advance `to` on the next iteration if the condition is true again
    let safeguard = false;

    while (to < textSize) {
      const rects = getRangeRects(textNode, from, to + 1);
      const splitByWords = textAlign == "justify" && content[to].trim() == "";

      if (rects.length > 1 && safeguard) {
        from++;
        to++;
        safeguard = false;

      } else if (rects.length > 1 || splitByWords) {
        const position = prevRect;

        result.push({
          node: parent,
          position: position,
          text: current
        });

        if (splitByWords) {
          to++;
        }

        from = to;
        current = "";
        safeguard = true;
      } else {
        prevRect = rects[0];
        current += content[to];
        to = to + 1;
        safeguard = false;
      }
    }

    // to == textSize
    const rects = getRangeRects(textNode, from, to);
    result.push({node: parent, position: rects[0], text: current});
    return result;
  };
});
