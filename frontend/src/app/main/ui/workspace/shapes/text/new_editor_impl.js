/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import TextEditor from "./new_editor/TextEditor.js";

/**
 * Applies styles to the current selection or the
 * saved selection.
 *
 * @param {TextEditor} editor
 * @param {*} styles
 */
export function applyStylesToSelection(editor, styles) {
  return editor.applyStylesToSelection(styles);
}

/**
 * Returns the editor root.
 *
 * @param {TextEditor} editor
 * @returns {HTMLDivElement}
 */
export function getRoot(editor) {
  return editor.root;
}

/**
 * Sets the editor root.
 *
 * @param {TextEditor} editor
 * @param {HTMLDivElement} root
 * @returns {TextEditor}
 */
export function setRoot(editor, root) {
  editor.root = root;
  return editor;
}

/**
 * Creates a new Text Editor instance.
 *
 * @param {HTMLElement} element
 * @param {object} options
 * @returns {TextEditor}
 */
export function createTextEditor(element, options) {
  return new TextEditor(element, {
    ...options,
  });
}

export default {
  createTextEditor,
  setRoot,
  getRoot
};
