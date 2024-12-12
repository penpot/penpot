/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Insert a paragraph
 *
 * @see https://w3c.github.io/input-events/#interface-InputEvent
 * @param {InputEvent} event
 * @param {TextEditor} editor
 * @param {SelectionController} selectionController
 */
export function insertParagraph(event, editor, selectionController) {
  event.preventDefault();
  if (selectionController.isCollapsed) {
    return selectionController.insertParagraph();
  }
  return selectionController.replaceWithParagraph();
}
