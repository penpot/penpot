/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Insert typed plain text
 *
 * @see https://w3c.github.io/input-events/#interface-InputEvent
 * @param {InputEvent} event
 * @param {TextEditor} editor
 * @param {SelectionController} selectionController
 */
export function insertText(event, editor, selectionController) {
  event.preventDefault();
  if (selectionController.isCollapsed) {
    if (selectionController.isTextFocus) {
      return selectionController.insertText(event.data);
    } else if (selectionController.isLineBreakFocus) {
      return selectionController.replaceLineBreak(event.data);
    }
  } else {
    if (selectionController.isMultiParagraph) {
      return selectionController.replaceParagraphs(event.data);
    } else if (selectionController.isMultiInline) {
      return selectionController.replaceInlines(event.data);
    } else if (selectionController.isTextSame) {
      return selectionController.replaceText(event.data);
    }
  }
}
