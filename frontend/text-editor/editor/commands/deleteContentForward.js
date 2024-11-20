/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * delete the content directly after the caret position and this intention is not covered by
 * another inputType or delete the selection with the selection collapsing to its end after the deletion
 *
 * @param {InputEvent} event
 * @param {TextEditor} editor
 * @param {SelectionController} selectionController
 */
export function deleteContentForward(event, editor, selectionController) {
  event.preventDefault();
  // If the editor is empty this is a no op.
  if (editor.isEmpty) return;

  // If not is collapsed AKA is a selection, then
  // we removeSelected.
  if (!selectionController.isCollapsed) {
    return selectionController.removeSelected({ direction: "forward" });
  }

  // If we're in a text node and the offset is
  // greater than 0 (not at the start of the inline)
  // we simple remove a character from the text.
  if (selectionController.isTextFocus
   && selectionController.focusAtEnd) {
    return selectionController.mergeForwardParagraph();

  // If we're in a text node but we're at the end of the
  // paragraph, we should merge the current paragraph
  // with the following paragraph.
  } else if (
    selectionController.isTextFocus &&
    selectionController.focusOffset >= 0
  ) {
    return selectionController.removeForwardText();

  // If we're at an inline or a line break paragraph
  // and there's more than one paragraph, then we should
  // remove the next paragraph.
  } else if (
    (selectionController.isInlineFocus ||
      selectionController.isLineBreakFocus) &&
    editor.numParagraphs > 1
  ) {
    return selectionController.removeForwardParagraph();
  }
}
