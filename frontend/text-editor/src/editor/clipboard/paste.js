/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import {
  mapContentFragmentFromHTML,
  mapContentFragmentFromString,
} from "../content/dom/Content.js";
import { TextEditor } from "../TextEditor.js";

/**
 * Returns a DocumentFragment from text/html.
 *
 * @param {DataTransfer} clipboardData
 * @returns {DocumentFragment}
 */
function getFormattedFragmentFromClipboardData(
  selectionController,
  clipboardData,
) {
  return mapContentFragmentFromHTML(
    clipboardData.getData("text/html"),
    selectionController.currentStyle,
  );
}

/**
 * Returns a DocumentFragment from text/plain.
 *
 * @param {DataTransfer} clipboardData
 * @returns {DocumentFragment}
 */
function getPlainFragmentFromClipboardData(selectionController, clipboardData) {
  return mapContentFragmentFromString(
    clipboardData.getData("text/plain"),
    selectionController.currentStyle,
  );
}

/**
 * Returns a document fragment of html data.
 *
 * @param {DataTransfer} clipboardData
 * @returns {DocumentFragment}
 */
function getFormattedOrPlainFragmentFromClipboardData(
  selectionController,
  clipboardData,
) {
  if (clipboardData.types.includes("text/html")) {
    return getFormattedFragmentFromClipboardData(
      selectionController,
      clipboardData,
    );
  } else if (clipboardData.types.includes("text/plain")) {
    return getPlainFragmentFromClipboardData(
      selectionController,
      clipboardData,
    );
  }
}

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
  if (editor?.options?.allowHTMLPaste) {
    fragment = getFormattedOrPlainFragmentFromClipboardData(
      event.clipboardData,
    );
  } else {
    fragment = getPlainFragmentFromClipboardData(
      selectionController,
      event.clipboardData,
    );
  }

  if (!fragment) {
    // NOOP
    return;
  }

  if (selectionController.isCollapsed) {
    const hasOnlyOneParagraph = fragment.children.length === 1;
    const hasOnlyOneTextSpan = fragment.firstElementChild.children.length === 1;
    const forceTextSpan =
      fragment.firstElementChild.dataset.textSpan === "force";
    if (hasOnlyOneParagraph && hasOnlyOneTextSpan && forceTextSpan) {
      selectionController.insertIntoFocus(fragment.textContent);
    } else {
      selectionController.insertPaste(fragment);
    }
  } else {
    const hasOnlyOneParagraph = fragment.children.length === 1;
    const hasOnlyOneTextSpan = fragment.firstElementChild.children.length === 1;
    const forceTextSpan =
      fragment.firstElementChild.dataset.textSpan === "force";
    if (hasOnlyOneParagraph && hasOnlyOneTextSpan && forceTextSpan) {
      selectionController.replaceText(fragment.textContent);
    } else {
      selectionController.replaceWithPaste(fragment);
    }
  }
}
