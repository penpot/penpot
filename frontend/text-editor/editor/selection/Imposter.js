/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Creates a new selection imposter from a list of client rects.
 *
 * @param {DOMRect} referenceRect
 * @param {DOMRectList} clientRects
 * @returns {DocumentFragment}
 */
export function createSelectionImposterFromClientRects(
  referenceRect,
  clientRects
) {
  const fragment = document.createDocumentFragment();
  for (const rect of clientRects) {
    const rectElement = document.createElement("div");
    rectElement.className = "selection-imposter-rect";
    rectElement.style.left = `${rect.x - referenceRect.x}px`;
    rectElement.style.top = `${rect.y - referenceRect.y}px`;
    rectElement.style.width = `${rect.width}px`;
    rectElement.style.height = `${rect.height}px`;
    fragment.appendChild(rectElement);
  }
  return fragment;
}
