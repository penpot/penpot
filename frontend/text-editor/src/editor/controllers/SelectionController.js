/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { createLineBreak, isLineBreak } from "../content/dom/LineBreak.js";
import {
  createTextSpan,
  createTextSpanFrom,
  getTextSpan,
  getTextSpanLength,
  isTextSpan,
  isTextSpanStart,
  isTextSpanEnd,
  setTextSpanStyles,
  splitTextSpan,
  createEmptyTextSpan,
  createVoidTextSpan,
} from "../content/dom/TextSpan.js";
import {
  createEmptyParagraph,
  isEmptyParagraph,
  getParagraph,
  isParagraph,
  isParagraphStart,
  isParagraphEnd,
  setParagraphStyles,
  splitParagraph,
  mergeParagraphs,
  fixParagraph,
  createParagraph,
} from "../content/dom/Paragraph.js";
import {
  removeBackward,
  removeForward,
  replaceWith,
  insertInto,
  removeSlice,
  findPreviousWordBoundary,
  removeWordBackward,
} from "../content/Text.js";
import {
  getTextNodeLength,
  getClosestTextNode,
  isTextNode,
} from "../content/dom/TextNode.js";
import TextNodeIterator from "../content/dom/TextNodeIterator.js";
import TextEditor from "../TextEditor.js";
import CommandMutations from "../commands/CommandMutations.js";
import { isRoot, setRootStyles } from "../content/dom/Root.js";
import { SelectionDirection } from "./SelectionDirection.js";
import SafeGuard from "./SafeGuard.js";
import { sanitizeFontFamily } from "../content/dom/Style.js";

/**
 * Supported options for the SelectionController.
 *
 * @typedef {Object} SelectionControllerOptions
 * @property {SelectionControllerDebug} [debug] An object with references to DOM elements that will keep all the debugging values.
 */

/**
 * SelectionController uses the same concepts used by the Selection API but extending it to support
 * our own internal model based on paragraphs (in drafconst textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, "))]),
      createEmptyParagraph(),
      createParagraph([createTextSpan(new Text("World!"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection
    );
    focus(
      selection,
      textEditorMock,
      root.childNodes.item(2).firstChild.firstChild,
      0
    );
    selectionController.mergeBackwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children.length).toBe(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span"
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.textContent).toBe("Hello, ");
    expect(textEditorMock.root.lastChild.textContent).toBe("World!");
  t.js they were called blocks) and text spans.
 */
export class SelectionController extends EventTarget {
  /**
   * Reference to the text editor.
   *
   * @type {TextEditor}
   */
  #textEditor = null;

  /**
   * Selection.
   *
   * @type {Selection}
   */
  #selection = null;

  /**
   * Set of ranges (this should always have one)
   *
   * @type {Set<Range>}
   */
  #ranges = new Set();

  /**
   * Current range (.rangeAt 0)
   *
   * @type {Range}
   */
  #range = null;

  /**
   * @type {Node}
   */
  #focusNode = null;

  /**
   * @type {number}
   */
  #focusOffset = 0;

  /**
   * @type {Node}
   */
  #anchorNode = null;

  /**
   * @type {number}
   */
  #anchorOffset = 0;

  /**
   * Saved selection.
   *
   * @type {object}
   */
  #savedSelection = null;

  /**
   * TextNodeIterator that allows us to move
   * around the root element but only through
   * <br> and #text nodes.
   *
   * @type {TextNodeIterator}
   */
  #textNodeIterator = null;

  /**
   * CSSStyleDeclaration that we can mutate
   * to handle style changes.
   *
   * @type {CSSStyleDeclaration}
   */
  #currentStyle = null;

  /**
   * Element used to have a custom CSSStyleDeclaration
   * that we can modify to handle style changes when the
   * selection is changed.
   *
   * @type {HTMLDivElement}
   */
  #inertElement = null;

  /**
   * @type {SelectionControllerDebug}
   */
  #debug = null;

  /**
   * Command Mutations.
   *
   * @type {CommandMutations}
   */
  #mutations = new CommandMutations();

  /**
   * Style defaults.
   *
   * @type {Object.<string, *>}
   */
  #styleDefaults = null;

  /**
   * Fix for Chrome.
   */
  #fixInsertCompositionText = false;

  /**
   * @type {TextEditorOptions}
   */
  #options;

  /**
   * Constructor
   *
   * @param {TextEditor} textEditor
   * @param {Selection} selection
   * @param {SelectionControllerOptions} [options]
   */
  constructor(textEditor, selection, options) {
    super();
    // FIXME: We can't check if it is an instanceof TextEditor
    //        because tests use TextEditorMock.
    /*
    if (!(textEditor instanceof TextEditor)) {
      throw new TypeError("Invalid EventTarget");
    }
    */
    this.#options = options;
    this.#debug = options?.debug;
    this.#styleDefaults = options?.styleDefaults;
    this.#selection = selection;
    this.#textEditor = textEditor;
    this.#textNodeIterator = new TextNodeIterator(this.#textEditor.element);

    // Setups everything.
    this.#setup();
  }

  /**
   * Styles of the current text span.
   *
   * @type {CSSStyleDeclaration}
   */
  get currentStyle() {
    return this.#currentStyle;
  }

  /**
   * Text editor options.
   *
   * @type {TextEditorOptions}
   */
  get options() {
    return this.#options;
  }

  /**
   * Applies the default styles to the currentStyle
   * CSSStyleDeclaration.
   */
  #applyDefaultStylesToCurrentStyle() {
    if (this.#styleDefaults) {
      for (const [name, value] of Object.entries(this.#styleDefaults)) {
        this.#currentStyle.setProperty(
          name,
          value + (name === "font-size" ? "px" : ""),
        );
      }
    }
  }

  /**
   * Applies some styles to the currentStyle
   * CSSStyleDeclaration
   *
   * @param {HTMLElement} element
   */
  #applyStylesToCurrentStyle(element) {
    for (let index = 0; index < element.style.length; index++) {
      const styleName = element.style.item(index);

      let styleValue = element.style.getPropertyValue(styleName);
      if (styleName === "font-family") {
        styleValue = sanitizeFontFamily(styleValue);
      }

      this.#currentStyle.setProperty(styleName, styleValue);
    }
  }

  /**
   * Updates current styles based on the currently selected text span.
   *
   * @param {HTMLSpanElement} textSpan
   * @returns {SelectionController}
   */
  #updateCurrentStyle(textSpan) {
    this.#applyDefaultStylesToCurrentStyle();
    const root = textSpan.parentElement.parentElement;
    this.#applyStylesToCurrentStyle(root);
    const paragraph = textSpan.parentElement;
    this.#applyStylesToCurrentStyle(paragraph);
    this.#applyStylesToCurrentStyle(textSpan);
    return this;
  }

  /**
   * This is called on every `selectionchange` because it is dispatched
   * only by the `document` object.
   *
   * @param {Event} e
   */
  #onSelectionChange = (e) => {
    // If we're outside the contenteditable element, then
    // we return.
    if (!this.hasFocus) {
      return;
    }

    let focusNodeChanges = false;
    let anchorNodeChanges = false;

    if (this.#focusNode !== this.#selection.focusNode) {
      this.#focusNode = this.#selection.focusNode;
      focusNodeChanges = true;
    }
    this.#focusOffset = this.#selection.focusOffset;

    if (this.#anchorNode !== this.#selection.anchorNode) {
      this.#anchorNode = this.#selection.anchorNode;
      anchorNodeChanges = true;
    }
    this.#anchorOffset = this.#selection.anchorOffset;

    // We need to handle multi selection from firefox
    // and remove all the old ranges and just keep the
    // last one added.
    if (this.#selection.rangeCount > 1) {
      for (let index = 0; index < this.#selection.rangeCount; index++) {
        const range = this.#selection.getRangeAt(index);
        if (this.#ranges.has(range)) {
          this.#ranges.delete(range);
          this.#selection.removeRange(range);
        } else {
          this.#ranges.add(range);
          this.#range = range;
        }
      }
    } else if (this.#selection.rangeCount > 0) {
      const range = this.#selection.getRangeAt(0);
      this.#range = range;
      this.#ranges.clear();
      this.#ranges.add(range);
    } else {
      this.#range = null;
      this.#ranges.clear();
    }

    // If focus node changed, we need to retrieve all the
    // styles of the current text span and dispatch an event
    // to notify that the styles have changed.
    if (focusNodeChanges) {
      this.#notifyStyleChange();
    }

    if (this.#fixInsertCompositionText) {
      this.#fixInsertCompositionText = false;
      const lineBreak = fixParagraph(this.focusNode);
      this.collapse(lineBreak, 0);
    }

    if (this.#debug) {
      this.#debug.update(this);
    }
  };

  /**
   * Notifies that the styles have changed.
   */
  #notifyStyleChange() {
    const textSpan = this.focusTextSpan;
    if (textSpan) {
      this.#updateCurrentStyle(textSpan);
      this.dispatchEvent(
        new CustomEvent("stylechange", {
          detail: this.#currentStyle,
        }),
      );
    } else {
      const firstTextSpan =
        this.#textEditor.root?.firstElementChild?.firstElementChild;
      if (firstTextSpan) {
        this.#updateCurrentStyle(firstTextSpan);
        this.dispatchEvent(
          new CustomEvent("stylechange", {
            detail: this.#currentStyle,
          }),
        );
      }
    }
  }

  /**
   * Setups
   */
  #setup() {
    // This element is not attached to the DOM
    // so it doesn't trigger style or layout calculations.
    // That's why it's called "inertElement".
    this.#inertElement = document.createElement("div");
    this.#currentStyle = this.#inertElement.style;
    this.#applyDefaultStylesToCurrentStyle();

    if (this.#selection.rangeCount > 0) {
      const range = this.#selection.getRangeAt(0);
      this.#range = range;
      this.#ranges.add(range);
    }

    // If there are more than one range, we should remove
    // them because this is a feature not supported by browsers
    // like Safari and Chrome.
    if (this.#selection.rangeCount > 1) {
      for (let index = 1; index < this.#selection.rangeCount; index++) {
        this.#selection.removeRange(index);
      }
    }
    document.addEventListener("selectionchange", this.#onSelectionChange);
  }

  /**
   * Returns a Range-like object.
   *
   * @returns {RangeLike}
   */
  #getSavedRange() {
    if (!this.#range) {
      return {
        collapsed: true,
        commonAncestorContainer: null,
        startContainer: null,
        startOffset: 0,
        endContainer: null,
        endOffset: 0,
      };
    }
    return {
      collapsed: this.#range.collapsed,
      commonAncestorContainer: this.#range.commonAncestorContainer,
      startContainer: this.#range.startContainer,
      startOffset: this.#range.startOffset,
      endContainer: this.#range.endContainer,
      endOffset: this.#range.endOffset,
    };
  }

  /**
   * Saves the current selection and returns the client rects.
   *
   * @returns {boolean}
   */
  saveSelection() {
    this.#savedSelection = {
      isCollapsed: this.#selection.isCollapsed,
      focusNode: this.#selection.focusNode,
      focusOffset: this.#selection.focusOffset,
      anchorNode: this.#selection.anchorNode,
      anchorOffset: this.#selection.anchorOffset,
      range: this.#getSavedRange(),
    };
    return true;
  }

  /**
   * Restores a saved selection if there's any.
   *
   * @returns {boolean}
   */
  restoreSelection() {
    if (!this.#savedSelection) return false;

    if (this.#savedSelection.anchorNode && this.#savedSelection.focusNode) {
      if (this.#savedSelection.anchorNode === this.#savedSelection.focusNode) {
        this.#selection.setPosition(
          this.#savedSelection.focusNode,
          this.#savedSelection.focusOffset,
        );
      } else {
        this.#selection.setBaseAndExtent(
          this.#savedSelection.anchorNode,
          this.#savedSelection.anchorOffset,
          this.#savedSelection.focusNode,
          this.#savedSelection.focusOffset,
        );
      }
    }
    this.#savedSelection = null;
    return true;
  }

  /**
   * Marks the start of a mutation.
   *
   * Clears all the mutations kept in CommandMutations.
   */
  startMutation() {
    this.#mutations.clear();
    if (!this.#focusNode) return false;
    return true;
  }

  /**
   * Marks the end of a mutation.
   *
   * @returns
   */
  endMutation() {
    return this.#mutations;
  }

  /**
   * Selects all content.
   */
  selectAll() {
    if (this.#textEditor.isEmpty) {
      return this;
    }

    // Find the first and last text nodes to create a proper text selection
    // instead of selecting container elements
    const root = this.#textEditor.root;
    const firstParagraph = root.firstElementChild;
    const lastParagraph = root.lastElementChild;

    if (!firstParagraph || !lastParagraph) {
      return this;
    }

    const firstTextSpan = firstParagraph.firstElementChild;
    const lastTextSpan = lastParagraph.lastElementChild;

    if (!firstTextSpan || !lastTextSpan) {
      return this;
    }

    const firstTextNode = firstTextSpan.firstChild;
    const lastTextNode = lastTextSpan.lastChild;

    if (!firstTextNode || !lastTextNode) {
      return this;
    }

    // Create a range from first text node to last text node
    const range = document.createRange();
    range.setStart(firstTextNode, 0);
    range.setEnd(lastTextNode, lastTextNode.nodeValue?.length || 0);

    this.#selection.removeAllRanges();
    this.#selection.addRange(range);

    // Ensure internal state is synchronized
    this.#focusNode = this.#selection.focusNode;
    this.#focusOffset = this.#selection.focusOffset;
    this.#anchorNode = this.#selection.anchorNode;
    this.#anchorOffset = this.#selection.anchorOffset;
    this.#range = range;
    this.#ranges.clear();
    this.#ranges.add(range);

    // Notify style changes
    this.#notifyStyleChange();

    return this;
  }

  /**
   * Moves cursor to end.
   */
  cursorToEnd() {
    const range = document.createRange(); //Create a range (a range is a like the selection but invisible)
    range.selectNodeContents(this.#textEditor.element);
    range.collapse(false);
    this.#selection.removeAllRanges();
    this.#selection.addRange(range);
    return this;
  }

  /**
   * Collapses a selection.
   *
   * @param {Node} node
   * @param {number} offset
   */
  collapse(node, offset) {
    const nodeValue = node?.nodeValue ?? "";
    const nodeOffset =
      node.nodeType === Node.TEXT_NODE && offset >= nodeValue.length
        ? nodeValue.length
        : offset;

    return this.setSelection(node, nodeOffset, node, nodeOffset);
  }

  /**
   * Sets base and extent.
   *
   * @param {Node} anchorNode
   * @param {number} anchorOffset
   * @param {Node} [focusNode=anchorNode]
   * @param {number} [focusOffset=anchorOffset]
   */
  setSelection(
    anchorNode,
    anchorOffset,
    focusNode = anchorNode,
    focusOffset = anchorOffset,
  ) {
    if (!anchorNode.isConnected) {
      throw new Error("Invalid anchorNode");
    }
    if (!focusNode.isConnected) {
      throw new Error("Invalid focusNode");
    }
    if (this.#savedSelection) {
      this.#savedSelection.isCollapsed =
        focusNode === anchorNode && anchorOffset === focusOffset;
      this.#savedSelection.focusNode = focusNode;
      this.#savedSelection.focusOffset = focusOffset;
      this.#savedSelection.anchorNode = anchorNode;
      this.#savedSelection.anchorOffset = anchorOffset;

      this.#savedSelection.range.collapsed = this.#savedSelection.isCollapsed;
      const position = focusNode.compareDocumentPosition(anchorNode);
      if (position & Node.DOCUMENT_POSITION_FOLLOWING) {
        this.#savedSelection.range.startContainer = focusNode;
        this.#savedSelection.range.startOffset = focusOffset;
        this.#savedSelection.range.endContainer = anchorNode;
        this.#savedSelection.range.endOffset = anchorOffset;
      } else {
        this.#savedSelection.range.startContainer = anchorNode;
        this.#savedSelection.range.startOffset = anchorOffset;
        this.#savedSelection.range.endContainer = focusNode;
        this.#savedSelection.range.endOffset = focusOffset;
      }
    } else {
      this.#anchorNode = anchorNode;
      this.#anchorOffset = anchorOffset;
      if (anchorNode === focusNode) {
        this.#focusNode = this.#anchorNode;
        this.#focusOffset = this.#anchorOffset;
        this.#selection.setPosition(anchorNode, anchorOffset);
      } else {
        this.#focusNode = focusNode;
        this.#focusOffset = focusOffset;
        this.#selection.setBaseAndExtent(
          anchorNode,
          anchorOffset,
          focusNode,
          focusOffset,
        );
      }
    }
  }

  /**
   * Disposes the current resources.
   */
  dispose() {
    document.removeEventListener("selectionchange", this.#onSelectionChange);
    this.#textEditor = null;
    this.#ranges.clear();
    this.#ranges = null;
    this.#range = null;
    this.#selection = null;
    this.#focusNode = null;
    this.#anchorNode = null;
    this.#mutations.dispose();
    this.#mutations = null;
  }

  /**
   * Returns the current selection.
   *
   * @type {Selection}
   */
  get selection() {
    return this.#selection;
  }

  /**
   * Returns the current range.
   *
   * @type {Range}
   */
  get range() {
    return this.#range;
  }

  /**
   * Indicates the direction of the selection
   *
   * @type {SelectionDirection}
   */
  get direction() {
    if (this.isCollapsed) {
      return SelectionDirection.NONE;
    }
    if (this.focusNode !== this.anchorNode) {
      return this.startContainer === this.focusNode
        ? SelectionDirection.BACKWARD
        : SelectionDirection.FORWARD;
    }
    return this.focusOffset < this.anchorOffset
      ? SelectionDirection.BACKWARD
      : SelectionDirection.FORWARD;
  }

  /**
   * Indicates that the editor element has the
   * focus.
   *
   * @type {boolean}
   */
  get hasFocus() {
    return document.activeElement === this.#textEditor.element;
  }

  /**
   * Returns true if the selection is collapsed (caret)
   * or false otherwise.
   *
   * @type {boolean}
   */
  get isCollapsed() {
    if (this.#savedSelection) {
      return this.#savedSelection.isCollapsed;
    }
    return this.#selection.isCollapsed;
  }

  /**
   * Current or saved anchor node.
   *
   * @type {Node}
   */
  get anchorNode() {
    if (this.#savedSelection) {
      return this.#savedSelection.anchorNode;
    }
    return this.#anchorNode;
  }

  /**
   * Current or saved anchor offset.
   *
   * @type {number}
   */
  get anchorOffset() {
    if (this.#savedSelection) {
      return this.#savedSelection.anchorOffset;
    }
    return this.#selection.anchorOffset;
  }

  /**
   * Indicates that the caret is at the start of the node.
   *
   * @type {boolean}
   */
  get anchorAtStart() {
    return this.anchorOffset === 0;
  }

  /**
   * Indicates that the caret is at the end of the node.
   *
   * @type {boolean}
   */
  get anchorAtEnd() {
    return this.anchorOffset === (this.anchorNode.nodeValue?.length ?? 0);
  }

  /**
   * Current or saved focus node.
   *
   * @type {Node}
   */
  get focusNode() {
    if (this.#savedSelection) {
      return this.#savedSelection.focusNode;
    }
    return this.#focusNode;
  }

  /**
   * Current or saved focus offset.
   *
   * @type {number}
   */
  get focusOffset() {
    if (this.#savedSelection) {
      return this.#savedSelection.focusOffset;
    }
    return this.#focusOffset;
  }

  /**
   * Indicates that the caret is at the start of the node.
   *
   * @type {boolean}
   */
  get focusAtStart() {
    return this.focusOffset === 0;
  }

  /**
   * Indicates that the caret is at the end of the node.
   *
   * @type {boolean}
   */
  get focusAtEnd() {
    return this.focusOffset === (this.focusNode.nodeValue?.length ?? 0);
  }

  /**
   * Returns the paragraph in the focus node
   * of the current selection.
   *
   * @type {HTMLElement|null}
   */
  get focusParagraph() {
    return getParagraph(this.focusNode);
  }

  /**
   * Returns the text span in the focus node
   * of the current selection.
   *
   * @type {HTMLElement|null}
   */
  get focusTextSpan() {
    return getTextSpan(this.focusNode);
  }

  /**
   * Returns the current paragraph in the anchor
   * node of the current selection.
   *
   * @type {HTMLElement|null}
   */
  get anchorParagraph() {
    return getParagraph(this.anchorNode);
  }

  /**
   * Returns the current text span in the anchor
   * node of the current selection.
   *
   * @type {HTMLElement|null}
   */
  get anchorTextSpan() {
    return getTextSpan(this.anchorNode);
  }

  /**
   * Start container of the current range.
   */
  get startContainer() {
    if (this.#savedSelection) {
      return this.#savedSelection?.range?.startContainer;
    }
    return this.#range?.startContainer;
  }

  /**
   * `startOffset` of the current range.
   *
   * @type {number|null}
   */
  get startOffset() {
    if (this.#savedSelection) {
      return this.#savedSelection?.range?.startOffset;
    }
    return this.#range?.startOffset;
  }

  /**
   * Start paragraph of the current range.
   *
   * @type {HTMLElement|null}
   */
  get startParagraph() {
    const startContainer = this.startContainer;
    if (!startContainer) return null;
    return getParagraph(startContainer);
  }

  /**
   * Start text span of the current page.
   *
   * @type {HTMLElement|null}
   */
  get startTextSpan() {
    const startContainer = this.startContainer;
    if (!startContainer) return null;
    return getTextSpan(startContainer);
  }

  /**
   * End container of the current range.
   *
   * @type {Node}
   */
  get endContainer() {
    if (this.#savedSelection) {
      return this.#savedSelection?.range?.endContainer;
    }
    return this.#range?.endContainer;
  }

  /**
   * `endOffset` of the current range
   *
   * @type {HTMLElement|null}
   */
  get endOffset() {
    if (this.#savedSelection) {
      return this.#savedSelection?.range?.endOffset;
    }
    return this.#range?.endOffset;
  }

  /**
   * Paragraph element of the `endContainer` of
   * the current range.
   *
   * @type {HTMLElement|null}
   */
  get endParagraph() {
    const endContainer = this.endContainer;
    if (!endContainer) return null;
    return getParagraph(endContainer);
  }

  /**
   * TextSpan element of the `endContainer` of
   * the current range.
   *
   * @type {HTMLElement|null}
   */
  get endTextSpan() {
    const endContainer = this.endContainer;
    if (!endContainer) return null;
    return getTextSpan(endContainer);
  }

  /**
   * Returns true if the anchor node and the focus
   * node are the same text nodes.
   *
   * @type {boolean}
   */
  get isTextSame() {
    return (
      this.isTextFocus === this.isTextAnchor &&
      this.focusNode === this.anchorNode
    );
  }

  /**
   * Indicates that focus node is a text node.
   *
   * @type {boolean}
   */
  get isTextFocus() {
    return this.focusNode.nodeType === Node.TEXT_NODE;
  }

  /**
   * Indicates that anchor node is a text node.
   *
   * @type {boolean}
   */
  get isTextAnchor() {
    return this.anchorNode.nodeType === Node.TEXT_NODE;
  }

  /**
   * Is true if the current focus node is a text span.
   *
   * @type {boolean}
   */
  get isTextSpanFocus() {
    return isTextSpan(this.focusNode);
  }

  /**
   * Is true if the current anchor node is a text span.
   *
   * @type {boolean}
   */
  get isTextSpanAnchor() {
    return isTextSpan(this.anchorNode);
  }

  /**
   * Is true if the current focus node is a paragraph.
   *
   * @type {boolean}
   */
  get isParagraphFocus() {
    return isParagraph(this.focusNode);
  }

  /**
   * Is true if the current anchor node is a paragraph.
   *
   * @type {boolean}
   */
  get isParagraphAnchor() {
    return isParagraph(this.anchorNode);
  }

  /**
   * Is true if the current focus node is a line break.
   *
   * @type {boolean}
   */
  get isLineBreakFocus() {
    return (
      isLineBreak(this.focusNode) ||
      (isTextSpan(this.focusNode) && isLineBreak(this.focusNode.firstChild))
    );
  }

  /**
   * Is true if the current focus node is a root.
   *
   * @type {boolean}
   */
  get isRootFocus() {
    return isRoot(this.focusNode);
  }

  /**
   * Indicates that we have multiple nodes selected.
   *
   * @type {boolean}
   */
  get isMulti() {
    return this.focusNode !== this.anchorNode;
  }

  /**
   * Indicates that we have selected multiple
   * paragraph elements.
   *
   * @type {boolean}
   */
  get isMultiParagraph() {
    return this.isMulti && this.focusParagraph !== this.anchorParagraph;
  }

  /**
   * Indicates that we have selected multiple
   * text span elements.
   *
   * @type {boolean}
   */
  get isMultiTextSpan() {
    return this.isMulti && this.focusTextSpan !== this.anchorTextSpan;
  }

  /**
   * Indicates that the caret (only the caret)
   * is at the start of an text span.
   *
   * @type {boolean}
   */
  get isTextSpanStart() {
    if (!this.isCollapsed) return false;
    return isTextSpanStart(this.focusNode, this.focusOffset);
  }

  /**
   * Indicates that the caret (only the caret)
   * is at the end of an text span. This value doesn't
   * matter when dealing with selections.
   *
   * @type {boolean}
   */
  get isTextSpanEnd() {
    if (!this.isCollapsed) return false;
    return isTextSpanEnd(this.focusNode, this.focusOffset);
  }

  /**
   * Indicates that we're in the starting position of a paragraph.
   *
   * @type {boolean}
   */
  get isParagraphStart() {
    if (!this.isCollapsed) return false;
    return isParagraphStart(this.focusNode, this.focusOffset);
  }

  /**
   * Indicates that we're in the ending position of a paragraph.
   *
   * @type {boolean}
   */
  get isParagraphEnd() {
    if (!this.isCollapsed) return false;
    return isParagraphEnd(this.focusNode, this.focusOffset);
  }

  #getFragmentInlineTextNode(fragment) {
    if (isInline(fragment.firstElementChild.lastChild)) {
      return fragment.firstElementChild.firstElementChild.lastChild;
    }
    return fragment.firstElementChild.lastChild;
  }

  #getFragmentParagraphTextNode(fragment) {
    return fragment.lastElementChild.lastElementChild.lastChild;
  }

  /**
   * Insert pasted fragment.
   *
   * @param {DocumentFragment} fragment
   */
  insertPaste(fragment) {
    if (
      fragment.children.length === 1 &&
      fragment.firstElementChild?.dataset?.textSpan === "force"
    ) {
      const collapseNode = fragment.firstElementChild.firstChild;
      if (this.isTextSpanStart) {
        this.focusTextSpan.before(...fragment.firstElementChild.children);
      } else if (this.isTextSpanEnd) {
        this.focusTextSpan.after(...fragment.firstElementChild.children);
      } else {
        const newTextSpan = splitTextSpan(this.focusTextSpan, this.focusOffset);
        this.focusTextSpan.after(
          ...fragment.firstElementChild.children,
          newTextSpan,
        );
      }
      return this.collapse(collapseNode, collapseNode.nodeValue?.length || 0);
    }
    const collapseNode = this.#getFragmentParagraphTextNode(fragment);
    if (this.isParagraphStart) {
      const a = fragment.lastElementChild;
      const b = this.focusParagraph;
      this.focusParagraph.before(fragment);
      mergeParagraphs(a, b);
    } else if (this.isParagraphEnd) {
      const a = this.focusParagraph;
      const b = fragment.firstElementChild;
      this.focusParagraph.after(fragment);
      mergeParagraphs(a, b);
    } else {
      if (this.isTextSpanStart) {
        this.focusTextSpan.before(...fragment.firstElementChild.children);
      } else if (this.isTextSpanEnd) {
        this.focusTextSpan.after(...fragment.firstElementChild.children);
      } else {
        const newTextSpan = splitTextSpan(this.focusTextSpan, this.focusOffset);
        this.focusTextSpan.after(
          ...fragment.firstElementChild.children,
          newTextSpan,
        );
      }
    }
    if (isLineBreak(collapseNode)) {
      return this.collapse(collapseNode, 0);
    }
    return this.collapse(collapseNode, collapseNode.nodeValue?.length || 0);
  }

  /**
   * Replaces data with pasted fragment
   *
   * @param {DocumentFragment} fragment
   */
  replaceWithPaste(fragment) {
    this.removeSelected();
    this.insertPaste(fragment);
  }

  /**
   * Replaces the current line break with text
   *
   * @param {string} text
   */
  replaceLineBreak(text) {
    const newText = new Text(text);
    this.focusTextSpan.replaceChildren(newText);
    this.collapse(newText, text.length);
  }

  /**
   * Removes text forward from the current position.
   */
  removeForwardText() {
    this.#textNodeIterator.currentNode = this.focusNode;

    const removedData = removeForward(
      this.focusNode.nodeValue,
      this.focusOffset,
    );

    if (this.focusNode.nodeValue !== removedData) {
      this.focusNode.nodeValue = removedData;
    }

    const paragraph = this.focusParagraph;
    if (!paragraph) throw new Error("Cannot find paragraph");
    const textSpan = this.focusTextSpan;
    if (!textSpan) throw new Error("Cannot find text span");

    const nextTextNode = this.#textNodeIterator.nextNode();
    if (this.focusNode.nodeValue === "") {
      this.focusNode.remove();
    }

    if (paragraph.childNodes.length === 1 && textSpan.childNodes.length === 0) {
      const lineBreak = createLineBreak();
      textSpan.appendChild(lineBreak);
      return this.collapse(lineBreak, 0);
    } else if (
      paragraph.childNodes.length > 1 &&
      textSpan.childNodes.length === 0
    ) {
      textSpan.remove();
      return this.collapse(nextTextNode, 0);
    }
    return this.collapse(this.focusNode, this.focusOffset);
  }

  /**
   * Removes text backward from the current caret position.
   */
  removeBackwardText() {
    this.#textNodeIterator.currentNode = this.focusNode;

    // Remove the character from the string.
    const removedData = removeBackward(
      this.focusNode.nodeValue,
      this.focusOffset,
    );

    if (this.focusNode.nodeValue !== removedData) {
      this.focusNode.nodeValue = removedData;
    }

    // If the focusNode has content we don't need to do
    // anything else.
    if (this.focusOffset - 1 > 0) {
      return this.collapse(this.focusNode, this.focusOffset - 1);
    }

    const paragraph = this.focusParagraph;
    if (!paragraph) throw new Error("Cannot find paragraph");
    const textSpan = this.focusTextSpan;
    if (!textSpan) throw new Error("Cannot find text span");

    const previousTextNode = this.#textNodeIterator.previousNode();
    if (this.focusNode.nodeValue === "") {
      this.focusNode.remove();
    }

    if (paragraph.children.length === 1 && textSpan.childNodes.length === 0) {
      const lineBreak = createLineBreak();
      textSpan.appendChild(lineBreak);
      return this.collapse(lineBreak, 0);
    } else if (
      paragraph.children.length > 1 &&
      textSpan.childNodes.length === 0
    ) {
      textSpan.remove();
      return this.collapse(
        previousTextNode,
        getTextNodeLength(previousTextNode),
      );
    }

    return this.collapse(this.focusNode, this.focusOffset - 1);
  }

  /**
   * Removes word backward from the current caret position.
   */
  removeWordBackward() {
    if (!this.isCollapsed) {
      return this.removeSelected();
    }

    this.#textNodeIterator.currentNode = this.focusNode;

    const originalNodeValue = this.focusNode.nodeValue || "";
    const wordStart = findPreviousWordBoundary(
      originalNodeValue,
      this.focusOffset,
    );

    // Start node
    if (wordStart === this.focusOffset && this.focusOffset === 0) {
      if (this.focusTextSpan.previousElementSibling) {
        const prevTextSpan = this.focusTextSpan.previousElementSibling;
        const prevTextNode = prevTextSpan.lastChild;
        if (prevTextNode && prevTextNode.nodeType === Node.TEXT_NODE) {
          this.collapse(prevTextNode, prevTextNode.nodeValue.length);
          return this.removeWordBackward();
        }
      } else if (this.focusParagraph.previousElementSibling) {
        // Move to previous paragraph
        const prevParagraph = this.focusParagraph.previousElementSibling;
        const prevTextSpan = prevParagraph.lastElementChild;
        const prevTextNode = prevTextSpan?.lastChild;
        if (prevTextNode && prevTextNode.nodeType === Node.TEXT_NODE) {
          this.collapse(prevTextNode, prevTextNode.nodeValue.length);
          return this.removeWordBackward();
        } else {
          return this.mergeBackwardParagraph();
        }
      }
      return this;
    }

    const removedData = removeWordBackward(originalNodeValue, this.focusOffset);

    if (this.focusNode.nodeValue !== removedData) {
      this.focusNode.nodeValue = removedData;
      this.#mutations.update(this.focusTextSpan);
    }

    const paragraph = this.focusParagraph;
    if (!paragraph) throw new Error("Cannot find paragraph");
    const textSpan = this.focusTextSpan;
    if (!textSpan) throw new Error("Cannot find text span");

    // If the text node is empty, handle cleanup
    if (this.focusNode.nodeValue === "") {
      const previousTextNode = this.#textNodeIterator.previousNode();
      this.focusNode.remove();

      if (paragraph.children.length === 1 && textSpan.childNodes.length === 0) {
        const lineBreak = createLineBreak();
        textSpan.appendChild(lineBreak);
        return this.collapse(lineBreak, 0);
      } else if (
        paragraph.children.length > 1 &&
        textSpan.childNodes.length === 0
      ) {
        textSpan.remove();
        return this.collapse(
          previousTextNode,
          getTextNodeLength(previousTextNode),
        );
      }
    }

    return this.collapse(this.focusNode, wordStart);
  }

  /**
   * Inserts some text in the caret position.
   *
   * @param {string} newText
   */
  insertText(newText) {
    this.focusNode.nodeValue = insertInto(
      this.focusNode.nodeValue,
      this.focusOffset,
      newText,
    );
    this.#mutations.update(this.focusTextSpan);
    return this.collapse(this.focusNode, this.focusOffset + newText.length);
  }

  /**
   * Replaces the currently focus element
   * with some text.
   *
   * @param {string} newText
   */
  insertIntoFocus(newText) {
    if (this.isTextFocus) {
      this.focusNode.nodeValue = insertInto(
        this.focusNode.nodeValue,
        this.focusOffset,
        newText,
      );
    } else if (this.isLineBreakFocus) {
      const textNode = new Text(newText);
      this.focusNode.replaceWith(textNode);
      this.collapse(textNode, newText.length);
    } else {
      throw new Error("Unknown node type");
    }
  }

  /**
   * Replaces currently selected text.
   *
   * @param {string} newText
   */
  replaceText(newText) {
    const startOffset = Math.min(this.anchorOffset, this.focusOffset);
    const endOffset = Math.max(this.anchorOffset, this.focusOffset);

    if (this.isTextFocus) {
      this.focusNode.nodeValue = replaceWith(
        this.focusNode.nodeValue,
        startOffset,
        endOffset,
        newText,
      );
    } else if (this.isLineBreakFocus) {
      this.focusNode.replaceWith(new Text(newText));
    } else if (this.isRootFocus) {
      const newTextNode = new Text(newText);
      const newTextSpan = createTextSpan(newTextNode, this.#currentStyle);
      const newParagraph = createParagraph([newTextSpan], this.#currentStyle);
      this.focusNode.replaceChildren(newParagraph);
      return this.collapse(newTextNode, newText.length + 1);
    } else {
      const newTextNode = new Text(newText);
      const newTextSpan = createTextSpan(newTextNode, this.#currentStyle);
      const newParagraph = createParagraph([newTextSpan], this.#currentStyle);
      this.#textEditor.root.replaceChildren(newParagraph);
      return this.collapse(newTextNode, newText.length + 1);
    }
    this.#mutations.update(this.focusTextSpan);
    return this.collapse(this.focusNode, startOffset + newText.length);
  }

  /**
   * Replaces the selected text spans with new text.
   *
   * @param {string} newText
   */
  replaceTextSpans(newText) {
    const currentParagraph = this.focusParagraph;

    // This is the special (and fast) case where we're
    // removing everything inside a paragraph.
    if (
      this.startTextSpan === currentParagraph.firstChild &&
      this.startOffset === 0 &&
      this.endTextSpan === currentParagraph.lastChild &&
      this.endOffset === currentParagraph.lastChild.textContent.length
    ) {
      const newTextNode = new Text(newText);
      currentParagraph.replaceChildren(
        createTextSpan(newTextNode, this.anchorTextSpan.style),
      );
      return this.collapse(newTextNode, newTextNode.nodeValue?.length || 0);
    }

    this.removeSelected();
    this.insertIntoFocus(newText);

    /*
    this.focusNode.nodeValue = insertInto(
      this.focusNode.nodeValue,
      this.focusOffset,
      newText
    );
    */

    // FIXME: I'm not sure if we should merge text spans when they share the same styles.
    // For example: if we have > 2 text spans and the start text span and the end text span
    // share the same styles, maybe we should merge them?
    // mergeTextSpans(startTextSpan, endTextSpan);
    return this.collapse(this.focusNode, this.focusOffset + newText.length);
  }

  /**
   * Replaces paragraphs with text.
   *
   * @param {string} newText
   */
  replaceParagraphs(newText) {
    const currentParagraph = this.focusParagraph;

    this.removeSelected();
    this.insertIntoFocus(newText);

    for (const child of currentParagraph.children) {
      if (child.textContent === "") {
        child.remove();
      }
    }

    /*
    this.focusNode.nodeValue = insertInto(
      this.focusNode.nodeValue,
      this.focusOffset,
      newText
    );
    */
  }

  /**
   * Inserts a new paragraph after the current paragraph.
   */
  insertParagraphAfter() {
    const currentParagraph = this.focusParagraph;
    const newParagraph = createEmptyParagraph(this.#currentStyle);
    currentParagraph.after(newParagraph);
    this.#mutations.update(currentParagraph);
    this.#mutations.add(newParagraph);
    return this.collapse(newParagraph.firstChild.firstChild, 0);
  }

  /**
   * Inserts a new paragraph before the current paragraph.
   */
  insertParagraphBefore() {
    const currentParagraph = this.focusParagraph;
    const newParagraph = createEmptyParagraph(this.#currentStyle);
    currentParagraph.before(newParagraph);
    this.#mutations.update(currentParagraph);
    this.#mutations.add(newParagraph);
    return this.collapse(currentParagraph.firstChild.firstChild, 0);
  }

  /**
   * Splits the current paragraph.
   */
  splitParagraph() {
    const currentParagraph = this.focusParagraph;
    const newParagraph = splitParagraph(
      this.focusParagraph,
      this.focusTextSpan,
      this.#focusOffset,
    );
    this.focusParagraph.after(newParagraph);
    this.#mutations.update(currentParagraph);
    this.#mutations.add(newParagraph);
    return this.collapse(newParagraph.firstChild.firstChild, 0);
  }

  /**
   * Inserts a new paragraph.
   */
  insertParagraph() {
    if (this.isParagraphEnd) {
      return this.insertParagraphAfter();
    } else if (this.isParagraphStart) {
      return this.insertParagraphBefore();
    }
    return this.splitParagraph();
  }

  /**
   * Replaces the currently selected content with
   * a paragraph.
   */
  replaceWithParagraph() {
    const currentParagraph = this.focusParagraph;
    const currentTextSpan = this.focusTextSpan;

    this.removeSelected();

    const newParagraph = splitParagraph(
      currentParagraph,
      currentTextSpan,
      this.focusOffset,
    );
    currentParagraph.after(newParagraph);

    this.#mutations.update(currentParagraph);
    this.#mutations.add(newParagraph);

    // FIXME: Missing collapse?
  }

  /**
   * Removes a paragraph in backward direction.
   */
  removeBackwardParagraph() {
    const previousParagraph = this.focusParagraph.previousElementSibling;
    if (!previousParagraph) {
      return;
    }
    const paragraphToBeRemoved = this.focusParagraph;
    paragraphToBeRemoved.remove();
    const previousTextSpan =
      previousParagraph.children.length > 1
        ? previousParagraph.lastElementChild
        : previousParagraph.firstChild;
    const previousOffset = isLineBreak(previousTextSpan.firstChild)
      ? 0
      : previousTextSpan.firstChild.nodeValue?.length || 0;
    this.#mutations.remove(paragraphToBeRemoved);
    return this.collapse(previousTextSpan.firstChild, previousOffset);
  }

  /**
   * Merges the previous paragraph with the current paragraph.
   */
  mergeBackwardParagraph() {
    const currentParagraph = this.focusParagraph;
    const previousParagraph = this.focusParagraph.previousElementSibling;
    if (!previousParagraph) {
      return;
    }
    let previousTextSpan = previousParagraph.lastChild;
    const previousOffset = getTextSpanLength(previousTextSpan);
    if (isEmptyParagraph(previousParagraph)) {
      previousParagraph.replaceChildren(...currentParagraph.children);
      previousTextSpan = previousParagraph.firstChild;
      currentParagraph.remove();
    } else {
      mergeParagraphs(previousParagraph, currentParagraph);
    }
    this.#mutations.remove(currentParagraph);
    this.#mutations.update(previousParagraph);
    return this.collapse(previousTextSpan.firstChild, previousOffset);
  }

  /**
   * Merges the next paragraph with the current paragraph.
   */
  mergeForwardParagraph() {
    const currentParagraph = this.focusParagraph;
    const nextParagraph = this.focusParagraph.nextElementSibling;
    if (!nextParagraph) {
      return;
    }
    mergeParagraphs(this.focusParagraph, nextParagraph);
    this.#mutations.update(currentParagraph);
    this.#mutations.remove(nextParagraph);

    // FIXME: Missing collapse?
  }

  /**
   * Removes the forward paragraph.
   */
  removeForwardParagraph() {
    const nextParagraph = this.focusParagraph.nextSibling;
    if (!nextParagraph) {
      return;
    }
    const paragraphToBeRemoved = this.focusParagraph;
    paragraphToBeRemoved.remove();
    const nextTextSpan = nextParagraph.firstChild;
    const nextOffset = this.focusOffset;
    this.#mutations.remove(paragraphToBeRemoved);
    return this.collapse(nextTextSpan.firstChild, nextOffset);
  }

  /**
   * Cleans up all the affected paragraphs.
   *
   * @param {Set<HTMLDivElement>} affectedParagraphs
   * @param {Set<HTMLSpanElement>} affectedTextSpans
   */
  cleanUp(affectedParagraphs, affectedTextSpans) {
    // Remove empty text spans
    for (const textSpan of affectedTextSpans) {
      if (textSpan.textContent === "") {
        textSpan.remove();
        this.#mutations.remove(textSpan);
      }
    }

    // Remove empty paragraphs.
    for (const paragraph of affectedParagraphs) {
      if (paragraph.children.length === 0) {
        paragraph.remove();
        this.#mutations.remove(paragraph);
      }
    }
  }

  /**
   * Removes the selected content.
   *
   * @param {RemoveSelectedOptions} [options]
   */
  removeSelected(options) {
    if (this.isCollapsed) return;

    const affectedTextSpans = new Set();
    const affectedParagraphs = new Set();

    let previousNode = null;
    let nextNode = null;

    let { startNode, endNode, startOffset, endOffset } = this.getRanges();

    if (this.shouldHandleCompleteDeletion(startNode, endNode)) {
      return this.handleCompleteContentDeletion();
    }

    // This is the simplest case, when the startNode and
    // the endNode are the same and they're textNodes.
    if (startNode === endNode) {
      this.#textNodeIterator.currentNode = startNode;
      previousNode = this.#textNodeIterator.previousNode();

      this.#textNodeIterator.currentNode = startNode;
      nextNode = this.#textNodeIterator.nextNode();

      const textSpan = getTextSpan(startNode);
      const paragraph = getParagraph(startNode);
      affectedTextSpans.add(textSpan);
      affectedParagraphs.add(paragraph);

      const newNodeValue = removeSlice(
        startNode.nodeValue,
        startOffset,
        endOffset,
      );
      if (newNodeValue === "") {
        const lineBreak = createLineBreak();
        textSpan.replaceChildren(lineBreak);
        return this.collapse(lineBreak, 0);
      }
      startNode.nodeValue = newNodeValue;
      return this.collapse(startNode, startOffset);
    }

    // If startNode and endNode are different,
    // then we should process every text node from
    // start to end.

    // Select initial node.
    this.#textNodeIterator.currentNode = startNode;

    const startTextSpan = getTextSpan(startNode);
    const startParagraph = getParagraph(startNode);
    const endTextSpan = getTextSpan(endNode);
    const endParagraph = getParagraph(endNode);

    SafeGuard.start();
    do {
      SafeGuard.update();

      const { currentNode } = this.#textNodeIterator;

      // We retrieve the textSpan and paragraph of the
      // current node.
      const textSpan = getTextSpan(currentNode);
      const paragraph = getParagraph(currentNode);
      affectedTextSpans.add(textSpan);
      affectedParagraphs.add(paragraph);

      let shouldRemoveNodeCompletely = false;
      if (currentNode === startNode) {
        if (startOffset === 0) {
          // We should remove this node completely.
          shouldRemoveNodeCompletely = true;
        } else {
          // We should remove this node partially.
          currentNode.nodeValue = currentNode.nodeValue.slice(0, startOffset);
        }
      } else if (currentNode === endNode) {
        if (
          isLineBreak(endNode) ||
          (isTextNode(endNode) &&
            endOffset === (endNode.nodeValue?.length || 0))
        ) {
          // We should remove this node completely.
          shouldRemoveNodeCompletely = true;
        } else {
          // We should remove this node partially.
          currentNode.nodeValue = currentNode.nodeValue.slice(endOffset);
        }
      } else {
        // We should remove this node completely.
        shouldRemoveNodeCompletely = true;
      }

      this.#textNodeIterator.nextNode();

      // Realizamos el borrado del nodo actual.
      if (shouldRemoveNodeCompletely) {
        currentNode.remove();
        if (currentNode === startNode) {
          continue;
        }

        if (textSpan.childNodes.length === 0) {
          textSpan.remove();
        }

        if (paragraph !== startParagraph && paragraph.children.length === 0) {
          paragraph.remove();
        }
      }

      if (currentNode === endNode) {
        break;
      }
    } while (this.#textNodeIterator.currentNode);

    if (startParagraph !== endParagraph) {
      const mergedParagraph = mergeParagraphs(startParagraph, endParagraph);
      if (mergedParagraph.children.length === 0) {
        const newEmptyTextSpan = createEmptyTextSpan(this.#currentStyle);
        mergedParagraph.appendChild(newEmptyTextSpan);
        return this.collapse(newEmptyTextSpan.firstChild, 0);
      }
    }

    if (
      startTextSpan.childNodes.length === 0 &&
      endTextSpan.childNodes.length > 0
    ) {
      startTextSpan.remove();
      return this.collapse(endNode, 0);
    } else if (
      startTextSpan.childNodes.length > 0 &&
      endTextSpan.childNodes.length === 0
    ) {
      endTextSpan.remove();
      return this.collapse(startNode, startOffset);
    } else if (
      startTextSpan.childNodes.length === 0 &&
      endTextSpan.childNodes.length === 0
    ) {
      const previousTextSpan = startTextSpan.previousElementSibling;
      const nextTextSpan = endTextSpan.nextElementSibling;
      startTextSpan.remove();
      endTextSpan.remove();
      if (previousTextSpan) {
        return this.collapse(
          previousTextSpan.firstChild,
          previousTextSpan.firstChild.nodeValue?.length || 0,
        );
      }
      if (nextTextSpan) {
        return this.collapse(nextTextSpan.firstChild, 0);
      }
      const newEmptyTextSpan = createEmptyTextSpan(this.#currentStyle);
      startParagraph.appendChild(newEmptyTextSpan);
      return this.collapse(newEmptyTextSpan.firstChild, 0);
    }

    return this.collapse(startNode, startOffset);
  }

  getRanges() {
    let startNode = getClosestTextNode(this.#range.startContainer);
    let endNode = getClosestTextNode(this.#range.endContainer);

    let startOffset = this.#range.startOffset;
    let endOffset = this.#range.startOffset + this.#range.toString().length;

    return { startNode, endNode, startOffset, endOffset };
  }

  shouldHandleCompleteDeletion(startNode, endNode) {
    const root = this.#textEditor.root;
    return (
      (startNode &&
        endNode &&
        this.#range.toString() === (root.textContent || "") &&
        root.textContent.length > 0) ||
      !startNode ||
      !endNode
    );
  }

  /**
   * Handles complete content deletion when all content is selected.
   * @returns {SelectionController}
   */
  handleCompleteContentDeletion() {
    const root = this.#textEditor.root;
    const firstParagraph = root.firstElementChild;

    const newTextSpan = createEmptyTextSpan(this.#currentStyle);
    if (!newTextSpan.firstChild) {
      newTextSpan.appendChild(new Text(""));
    }

    if (firstParagraph) {
      firstParagraph.replaceChildren(newTextSpan);
      let currentParagraph = firstParagraph.nextElementSibling;
      while (currentParagraph) {
        const nextParagraph = currentParagraph.nextElementSibling;
        currentParagraph.remove();
        currentParagraph = nextParagraph;
      }
    } else {
      const newParagraph = createEmptyParagraph();
      newParagraph.appendChild(newTextSpan);
      root.replaceChildren(newParagraph);
    }

    return this.collapse(newTextSpan.firstChild, 0);
  }

  /**
   * Applies styles from the startNode to the endNode.
   *
   * @param {Node} startNode
   * @param {number} startOffset
   * @param {Node} endNode
   * @param {number} endOffset
   * @param {Object.<string,*>|CSSStyleDeclaration} newStyles
   * @returns {void}
   */
  #applyStylesTo(startNode, startOffset, endNode, endOffset, newStyles) {
    // Applies the necessary styles to the root element.
    const root = this.#textEditor.root;
    setRootStyles(root, newStyles);

    // If the startContainer and endContainer are the same
    // node, then we can apply styles directly to that
    // node.
    if (startNode === endNode && startNode.nodeType === Node.TEXT_NODE) {
      // The styles are applied to the node completely.
      if (startOffset === 0 && endOffset === (endNode.nodeValue?.length || 0)) {
        const paragraph = this.startParagraph;
        const textSpan = this.startTextSpan;
        setParagraphStyles(paragraph, newStyles);
        setTextSpanStyles(textSpan, newStyles);

        // The styles are applied to a part of the node.
      } else if (startOffset !== endOffset) {
        const paragraph = this.startParagraph;
        setParagraphStyles(paragraph, newStyles);
        const textSpan = this.startTextSpan;
        const midText = startNode.splitText(startOffset);
        const endText = midText.splitText(endOffset - startOffset);
        const midTextSpan = createTextSpanFrom(textSpan, midText, newStyles);
        textSpan.after(midTextSpan);
        if (endText.length > 0) {
          const endTextSpan = createTextSpan(endText, textSpan.style);
          midTextSpan.after(endTextSpan);
        }

        // NOTE: This is necessary because sometimes
        // text spans are splitted from the beginning
        // to a mid offset and then the starting node
        // remains empty.
        if (textSpan.firstChild.nodeValue === "") {
          textSpan.remove();
        }

        // FIXME: This can change focus <-> anchor order.
        this.setSelection(midText, 0, midText, midText.nodeValue?.length || 0);
      }
      // the styles are applied to the current caret
      else if (
        this.startOffset === this.endOffset &&
        this.endOffset === endNode.nodeValue.length
      ) {
        const newTextSpan = createVoidTextSpan(newStyles);
        this.endTextSpan.after(newTextSpan);
        this.setSelection(newTextSpan.firstChild, 0, newTextSpan.firstChild, 0);
      }
      // The styles are applied to the paragraph
      else {
        const paragraph = this.startParagraph;
        setParagraphStyles(paragraph, newStyles);
      }
      return this.#notifyStyleChange();

      // If the startContainer and endContainer are different
      // then we need to iterate through those nodes to apply
      // the styles.
    } else if (startNode !== endNode) {
      SafeGuard.start();
      const expectedEndNode = getClosestTextNode(endNode);
      this.#textNodeIterator.currentNode = getClosestTextNode(startNode);
      do {
        SafeGuard.update();

        const paragraph = getParagraph(this.#textNodeIterator.currentNode);
        setParagraphStyles(paragraph, newStyles);
        const textSpan = getTextSpan(this.#textNodeIterator.currentNode);
        // If we're at the start node and offset is greater than 0
        // then we should split the text span and apply styles to that
        // new text span.
        if (
          this.#textNodeIterator.currentNode === startNode &&
          startOffset > 0
        ) {
          const newTextSpan = splitTextSpan(textSpan, startOffset);
          setTextSpanStyles(newTextSpan, newStyles);
          textSpan.after(newTextSpan);
          // If we're at the start node and offset is equal to 0
          // or current node is different to start node and
          // different to end node or we're at the end node
          // and the offset is equalto the node length
        } else if (
          (this.#textNodeIterator.currentNode === startNode &&
            startOffset === 0) ||
          (this.#textNodeIterator.currentNode !== startNode &&
            this.#textNodeIterator.currentNode !== endNode) ||
          (this.#textNodeIterator.currentNode === endNode &&
            endOffset === endNode.nodeValue.length)
        ) {
          setTextSpanStyles(textSpan, newStyles);

          // If we're at end node
        } else if (
          this.#textNodeIterator.currentNode === endNode &&
          endOffset < endNode.nodeValue.length
        ) {
          const newTextSpan = splitTextSpan(textSpan, endOffset);
          setTextSpanStyles(textSpan, newStyles);
          textSpan.after(newTextSpan);
        }

        // We've reached the final node so we can return safely.
        if (this.#textNodeIterator.currentNode === expectedEndNode) return;

        this.#textNodeIterator.nextNode();
      } while (this.#textNodeIterator.currentNode);
    }

    return this.#notifyStyleChange();
  }

  /**
   * Applies styles to selection
   *
   * @param {Object.<string, *>} newStyles
   * @returns {void}
   */
  applyStyles(newStyles) {
    return this.#applyStylesTo(
      this.startContainer,
      this.startOffset,
      this.endContainer,
      this.endOffset,
      newStyles,
    );
  }

  /**
   * BROWSER FIXES
   */
  fixInsertCompositionText() {
    this.#fixInsertCompositionText = true;
  }
}

export default SelectionController;
