import LayoutType from "./layout/LayoutType.js";
import textLayoutImpl from "./TextLayout.js";
import cljs from "goog:cljs.core";

/**
 * @typedef {object} LayoutFromRootOptions
 * @property {number} x
 * @property {number} y
 * @property {number} width
 * @property {number} height
 */

/**
 * Performs a layout operation using only content.
 *
 * @param {HTMLDivElement} root
 * @param {LayoutFromRootOptions} [options]
 * @returns {ContentLayout}
 */
export function layoutFromRoot(root, options) {
  return cljs.PersistentVector.fromArray(textLayout.layoutFromRoot(root, options));
}

/**
 * Performs a layout operation using a HTML element.
 *
 * @param {HTMLElement} element
 * @returns {ContentLayout}
 */
export function layoutFromElement(element) {
  return cljs.PersistentVector.fromArray(textLayout.layoutFromElement(element));
}

/**
 * Performs a layout operation using a TextEditor.
 *
 * @param {TextEditor} editor
 * @param {"full"|"partial"} type
 * @param {CommandMutations} mutations
 * @returns {ContentLayout}
 */
export function layoutFromEditor(editor, type, mutations) {
  if (!LayoutType.isLayoutType(type)) {
    throw new TypeError("`type` is not a valid layout type");
  }
  if (type === LayoutType.FULL) {
    return cljs.PersistentVector.fromArray(textLayout.layoutFromElement(editor.element));
  }
  return cljs.PersistentVector.fromArray(textLayout.partialLayoutFromElement(editor.element, mutations));
}

export const textLayout = textLayoutImpl;

export default {
  layoutFromEditor,
  layoutFromElement,
  layoutFromRoot,
};
