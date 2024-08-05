import LayoutType from "./layout/LayoutType.js";
import textLayoutImpl from "./TextLayout.js";

/**
 * Performs a layout operation using a HTML element.
 *
 * @param {HTMLElement} element
 * @returns {ContentLayout}
 */
export function layoutFromElement(element) {
  return textLayout.layoutFromElement(element);
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
  if (type === LayoutType.FULL) {
    return textLayout.layoutFromElement(editor.element);
  }
  return textLayout.partialLayoutFromElement(editor.element, mutations);
}

export const textLayout = textLayoutImpl;

export default {
  layoutFromEditor,
  layoutFromElement,
};
