/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * delete the content directly before the caret position and this intention is
 * not covered by another `inputType` or delete the selection with the
 * selection collapsing to its start after the deletion.
 *
 * @param {InputEvent} event
 * @param {TextEditor} editor
 * @param {SelectionController} selectionController
 */
export function deleteContentBackward(event, editor, selectionController) {
  event.preventDefault();
  // If the editor is empty this is a no op.
  if (editor.isEmpty) return;

  // If not is collapsed AKA is a selection, then
  // we removeSelected.
  if (!selectionController.isCollapsed) {
    return selectionController.removeSelected({ direction: 'backward' });
  }

  // If we're in a text node and the offset is
  // greater than 0 (not at the start of the text span)
  // we simple remove a character from the text.
  if (selectionController.isTextFocus && selectionController.focusOffset > 0) {
    return selectionController.removeBackwardText();

  // If we're in a text node but we're at the end of the
  // paragraph, we should merge the current paragraph
  // with the following paragraph.
  } else if (
    selectionController.isTextFocus &&
    selectionController.focusAtStart
  ) {
    return selectionController.mergeBackwardParagraph();

  // If we're at an text span or a line break paragraph
  // and there's more than one paragraph, then we should
  // remove the next paragraph.
  } else if (
    selectionController.isTextSpanFocus ||
    selectionController.isLineBreakFocus
  ) {
    return selectionController.removeBackwardParagraph();
  }
}
