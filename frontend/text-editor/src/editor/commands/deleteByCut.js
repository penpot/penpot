/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Remove the current selection as part of a cut.
 *
 * @param {InputEvent} event
 * @param {TextEditor} editor
 * @param {SelectionController} selectionController
 */
export function deleteByCut(event, editor, selectionController) {
  event.preventDefault();
  if (selectionController.isCollapsed) {
    throw new Error("This should be impossible");
  }
  return selectionController.removeSelected();
}
