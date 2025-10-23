/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Used for handling debugging.
 */
export class SelectionControllerDebug {
  /**
   * @type {Object.<string, HTMLElement>}
   */
  #elements = null;

  /**
   * Constructor
   *
   * @param {Object.<string, HTMLElement>} elements List of elements used to debug the SelectionController
   */
  constructor(elements) {
    this.#elements = elements;
  }

  getNodeDescription(node, offset) {
    if (!node) return "null";
    return `${node.nodeName} ${
      node.nodeType === Node.TEXT_NODE
        ? node.nodeValue + (typeof offset === "number" ? `(${offset})` : "")
        : node.dataset.itype
    }`;
  }

  update(selectionController) {
    this.#elements.direction.value = selectionController.direction;
    this.#elements.multiElement.checked = selectionController.isMulti;
    this.#elements.multiTextSpanElement.checked =
      selectionController.isMultiTextSpan;
    this.#elements.multiParagraphElement.checked =
      selectionController.isMultiParagraph;
    this.#elements.isParagraphStart.checked =
      selectionController.isParagraphStart;
    this.#elements.isParagraphEnd.checked = selectionController.isParagraphEnd;
    this.#elements.isTextSpanStart.checked = selectionController.isTextSpanStart;
    this.#elements.isTextSpanEnd.checked = selectionController.isTextSpanEnd;
    this.#elements.isTextAnchor.checked = selectionController.isTextAnchor;
    this.#elements.isTextFocus.checked = selectionController.isTextFocus;
    this.#elements.focusNode.value = this.getNodeDescription(
      selectionController.focusNode,
      selectionController.focusOffset
    );
    this.#elements.focusOffset.value = selectionController.focusOffset;
    this.#elements.anchorNode.value = this.getNodeDescription(
      selectionController.anchorNode,
      selectionController.anchorOffset
    );
    this.#elements.anchorOffset.value = selectionController.anchorOffset;
    this.#elements.focusTextSpan.value = this.getNodeDescription(
      selectionController.focusTextSpan
    );
    this.#elements.anchorTextSpan.value = this.getNodeDescription(
      selectionController.anchorTextSpan
    );
    this.#elements.focusParagraph.value = this.getNodeDescription(
      selectionController.focusParagraph
    );
    this.#elements.anchorParagraph.value = this.getNodeDescription(
      selectionController.anchorParagraph
    );
    this.#elements.startContainer.value = this.getNodeDescription(selectionController.startContainer);
    this.#elements.endContainer.value = this.getNodeDescription(selectionController.endContainer);
  }
}
