/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { mapContentFragmentFromHTML, mapContentFragmentFromString } from "../content/dom/Content.js";

/**
 * When the user pastes some HTML, what we do is generate
 * a new DOM based on what the user pasted and then we
 * insert it in the appropiate part (see `insertFromPaste` command).
 *
 * @param {ClipboardEvent} event
 * @param {TextEditor} editor
 * @param {SelectionController} selectionController
 * @returns {void}
 */
export function paste(event, editor, selectionController) {
  // We need to prevent default behavior
  // because we don't allow any HTML to
  // be pasted.
  event.preventDefault();

  let fragment = null;
  if (event.clipboardData.types.includes("text/html")) {
    const html = event.clipboardData.getData("text/html");
    fragment = mapContentFragmentFromHTML(html, selectionController.currentStyle);
  } else if (event.clipboardData.types.includes("text/plain")) {
    const plain = event.clipboardData.getData("text/plain");
    fragment = mapContentFragmentFromString(plain, selectionController.currentStyle);
  }

  if (!fragment) {
    return;
  }

  if (selectionController.isCollapsed) {
    selectionController.insertPaste(fragment);
  } else {
    selectionController.replaceWithPaste(fragment);
  }
}
