/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { createLineBreak, isLineBreak } from "../content/dom/LineBreak.js";
import {
  createInline,
  createInlineFrom,
  getInline,
  getInlineLength,
  isInline,
  isInlineStart,
  isInlineEnd,
  setInlineStyles,
  splitInline,
  createEmptyInline,
} from "../content/dom/Inline.js";
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
} from "../content/Text.js";
import { getTextNodeLength, getClosestTextNode, isTextNode } from "../content/dom/TextNode.js";
import TextNodeIterator from "../content/dom/TextNodeIterator.js";
import TextEditor from "../TextEditor.js";
import CommandMutations from "../commands/CommandMutations.js";
import { isRoot, setRootStyles } from "../content/dom/Root.js";
import { SelectionDirection } from "./SelectionDirection.js";
import SafeGuard from "./SafeGuard.js";

/**
 * Supported options for the SelectionController.
 *
 * @typedef {Object} SelectionControllerOptions
 * @property {Object} [debug] An object with references to DOM elements that will keep all the debugging values.
 */

/**
 * SelectionController uses the same concepts used by the Selection API but extending it to support
 * our own internal model based on paragraphs (in drafconst textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createInline(new Text("Hello, "))]),
      createEmptyParagraph(),
      createParagraph([createInline(new Text("World!"))]),
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
      "inline"
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.textContent).toBe("Hello, ");
    expect(textEditorMock.root.lastChild.textContent).toBe("World!");
  t.js they were called blocks) and inlines.
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
    this.#debug = options?.debug;
    this.#styleDefaults = options?.styleDefaults;
    this.#selection = selection;
    this.#textEditor = textEditor;
    this.#textNodeIterator = new TextNodeIterator(this.#textEditor.element);

    // Setups everything.
    this.#setup();
  }

  /**
   * Styles of the current inline.
   *
   * @type {CSSStyleDeclaration}
   */
  get currentStyle() {
    return this.#currentStyle;
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
          value + (name === "font-size" ? "px" : "")
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
      const styleValue = element.style.getPropertyValue(styleName);
      this.#currentStyle.setProperty(styleName, styleValue);
    }
  }

  /**
   * Updates current styles based on the currently selected inline.
   *
   * @param {HTMLSpanElement} inline
   * @returns {SelectionController}
   */
  #updateCurrentStyle(inline) {
    this.#applyDefaultStylesToCurrentStyle();
    const root = inline.parentElement.parentElement;
    this.#applyStylesToCurrentStyle(root);
    const paragraph = inline.parentElement;
    this.#applyStylesToCurrentStyle(paragraph);
    this.#applyStylesToCurrentStyle(inline);
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
    // styles of the current inline and dispatch an event
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
    const inline = this.focusInline;
    if (inline) {
      this.#updateCurrentStyle(inline);
      this.dispatchEvent(
        new CustomEvent("stylechange", {
          detail: this.#currentStyle,
        })
      );
    } else {
      const firstInline = this.#textEditor.root?.firstElementChild?.firstElementChild;
      if (firstInline) {
        this.#updateCurrentStyle(firstInline);
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
        this.#selection.setPosition(this.#savedSelection.focusNode, this.#savedSelection.focusOffset);
      } else {
        this.#selection.setBaseAndExtent(
          this.#savedSelection.anchorNode,
          this.#savedSelection.anchorOffset,
          this.#savedSelection.focusNode,
          this.#savedSelection.focusOffset
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
      return this
    }
    this.#selection.selectAllChildren(this.#textEditor.root);
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
    const nodeOffset = (node.nodeType === Node.TEXT_NODE && offset >= node.nodeValue.length)
      ? node.nodeValue.length
      : offset

    return this.setSelection(
      node,
      nodeOffset,
      node,
      nodeOffset
    );
  }

  /**
   * Sets base and extent.
   *
   * @param {Node} anchorNode
   * @param {number} anchorOffset
   * @param {Node} [focusNode=anchorNode]
   * @param {number} [focusOffset=anchorOffset]
   */
  setSelection(anchorNode, anchorOffset, focusNode = anchorNode, focusOffset = anchorOffset) {
    if (!anchorNode.isConnected) {
      throw new Error('Invalid anchorNode')
    }
    if (!focusNode.isConnected) {
      throw new Error('Invalid focusNode')
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
          focusOffset
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
    return this.anchorOffset === this.anchorNode.nodeValue.length;
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
    if (!this.#focusNode)
      console.trace("focusNode", this.#focusNode);
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
    return this.focusOffset === this.focusNode.nodeValue.length;
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
   * Returns the inline in the focus node
   * of the current selection.
   *
   * @type {HTMLElement|null}
   */
  get focusInline() {
    return getInline(this.focusNode);
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
   * Returns the current inline in the anchor
   * node of the current selection.
   *
   * @type {HTMLElement|null}
   */
  get anchorInline() {
    return getInline(this.anchorNode);
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
   * Start inline of the current page.
   *
   * @type {HTMLElement|null}
   */
  get startInline() {
    const startContainer = this.startContainer;
    if (!startContainer) return null;
    return getInline(startContainer);
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
   * Inline element of the `endContainer` of
   * the current range.
   *
   * @type {HTMLElement|null}
   */
  get endInline() {
    const endContainer = this.endContainer;
    if (!endContainer) return null;
    return getInline(endContainer);
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
   * Is true if the current focus node is a inline.
   *
   * @type {boolean}
   */
  get isInlineFocus() {
    return isInline(this.focusNode);
  }

  /**
   * Is true if the current anchor node is a inline.
   *
   * @type {boolean}
   */
  get isInlineAnchor() {
    return isInline(this.anchorNode);
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
      (isInline(this.focusNode) && isLineBreak(this.focusNode.firstChild))
    );
  }

  /**
   * Is true if the current focus node is a root.
   *
   * @type {boolean}
   */
  get isRootFocus() {
    return isRoot(this.focusNode)
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
   * inline elements.
   *
   * @type {boolean}
   */
  get isMultiInline() {
    return this.isMulti && this.focusInline !== this.anchorInline;
  }

  /**
   * Indicates that the caret (only the caret)
   * is at the start of an inline.
   *
   * @type {boolean}
   */
  get isInlineStart() {
    if (!this.isCollapsed) return false;
    return isInlineStart(this.focusNode, this.focusOffset);
  }

  /**
   * Indicates that the caret (only the caret)
   * is at the end of an inline. This value doesn't
   * matter when dealing with selections.
   *
   * @type {boolean}
   */
  get isInlineEnd() {
    if (!this.isCollapsed) return false;
    return isInlineEnd(this.focusNode, this.focusOffset);
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

  /**
   * Insert pasted fragment.
   *
   * @param {DocumentFragment} fragment
   */
  insertPaste(fragment) {
    if (fragment.children.length === 1
     && fragment.firstElementChild?.dataset?.inline === "force"
    ) {
      const collapseNode = fragment.lastElementChild.firstChild
      if (this.isInlineStart) {
        this.focusInline.before(...fragment.firstElementChild.children)
      } else if (this.isInlineEnd) {
        this.focusInline.after(...fragment.firstElementChild.children);
      } else {
        const newInline = splitInline(
          this.focusInline,
          this.focusOffset
        )
        this.focusInline.after(...fragment.firstElementChild.children, newInline)
      }
      return this.collapse(
        collapseNode,
        collapseNode.nodeValue.length
      );
    }
    const collapseNode = fragment.lastElementChild.lastElementChild.firstChild
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
      const newParagraph = splitParagraph(
        this.focusParagraph,
        this.focusInline,
        this.focusOffset
      );
      this.focusParagraph.after(fragment, newParagraph);
    }
    return this.collapse(collapseNode, collapseNode.nodeValue.length);
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
    this.focusInline.replaceChildren(newText);
    this.collapse(newText, text.length);
  }

  /**
   * Removes text forward from the current position.
   */
  removeForwardText() {
    this.#textNodeIterator.currentNode = this.focusNode;

    const removedData = removeForward(
      this.focusNode.nodeValue,
      this.focusOffset
    );

    if (this.focusNode.nodeValue !== removedData) {
      this.focusNode.nodeValue = removedData;
    }

    const paragraph = this.focusParagraph;
    if (!paragraph) throw new Error("Cannot find paragraph");
    const inline = this.focusInline;
    if (!inline) throw new Error("Cannot find inline");

    const nextTextNode = this.#textNodeIterator.nextNode();
    if (this.focusNode.nodeValue === "") {
      this.focusNode.remove();
    }

    if (paragraph.childNodes.length === 1 && inline.childNodes.length === 0) {
      const lineBreak = createLineBreak();
      inline.appendChild(lineBreak);
      return this.collapse(lineBreak, 0);
    } else if (
      paragraph.childNodes.length > 1 &&
      inline.childNodes.length === 0
    ) {
      inline.remove();
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
      this.focusOffset
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
    const inline = this.focusInline;
    if (!inline) throw new Error("Cannot find inline");

    const previousTextNode = this.#textNodeIterator.previousNode();
    if (this.focusNode.nodeValue === "") {
      this.focusNode.remove();
    }

    if (paragraph.children.length === 1 && inline.childNodes.length === 0) {
      const lineBreak = createLineBreak();
      inline.appendChild(lineBreak);
      return this.collapse(lineBreak, 0);
    } else if (
      paragraph.children.length > 1 &&
      inline.childNodes.length === 0
    ) {
      inline.remove();
      return this.collapse(previousTextNode, getTextNodeLength(previousTextNode));
    }

    return this.collapse(this.focusNode, this.focusOffset - 1);
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
      newText
    );
    this.#mutations.update(this.focusInline);
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
        newText
      );
    } else if (this.isLineBreakFocus) {
      const textNode = new Text(newText);
      this.focusNode.replaceWith(textNode);
      this.collapse(textNode, newText.length);
    } else {
      throw new Error('Unknown node type');
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
        newText
      );
    } else if (this.isLineBreakFocus) {
      this.focusNode.replaceWith(new Text(newText));
    } else if (this.isRootFocus) {
      const newTextNode = new Text(newText);
      const newInline = createInline(newTextNode, this.#currentStyle);
      const newParagraph = createParagraph([
        newInline
      ], this.#currentStyle)
      this.focusNode.replaceChildren(
        newParagraph
      );
      return this.collapse(newTextNode, newText.length + 1);
    } else {
      throw new Error('Unknown node type');
    }
    this.#mutations.update(this.focusInline);
    return this.collapse(this.focusNode, startOffset + newText.length);
  }

  /**
   * Replaces the selected inlines with new text.
   *
   * @param {string} newText
   */
  replaceInlines(newText) {
    const currentParagraph = this.focusParagraph;

    // This is the special (and fast) case where we're
    // removing everything inside a paragraph.
    if (
      this.startInline === currentParagraph.firstChild &&
      this.startOffset === 0 &&
      this.endInline === currentParagraph.lastChild &&
      this.endOffset === currentParagraph.lastChild.textContent.length
    ) {
      const newTextNode = new Text(newText);
      currentParagraph.replaceChildren(
        createInline(newTextNode, this.anchorInline.style)
      );
      return this.collapse(newTextNode, newTextNode.nodeValue.length);
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

    // FIXME: I'm not sure if we should merge inlines when they share the same styles.
    // For example: if we have > 2 inlines and the start inline and the end inline
    // share the same styles, maybe we should merge them?
    // mergeInlines(startInline, endInline);
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
      this.focusInline,
      this.#focusOffset
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
    const currentInline = this.focusInline;

    this.removeSelected();

    const newParagraph = splitParagraph(
      currentParagraph,
      currentInline,
      this.focusOffset
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
    const previousInline =
      previousParagraph.children.length > 1
        ? previousParagraph.lastElementChild
        : previousParagraph.firstChild;
    const previousOffset = isLineBreak(previousInline.firstChild)
      ? 0
      : previousInline.firstChild.nodeValue.length;
    this.#mutations.remove(paragraphToBeRemoved);
    return this.collapse(previousInline.firstChild, previousOffset);
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
    let previousInline = previousParagraph.lastChild;
    const previousOffset = getInlineLength(previousInline);
    if (isEmptyParagraph(previousParagraph)) {
      previousParagraph.replaceChildren(...currentParagraph.children);
      previousInline = previousParagraph.firstChild;
      currentParagraph.remove();
    } else {
      mergeParagraphs(previousParagraph, currentParagraph);
    }
    this.#mutations.remove(currentParagraph);
    this.#mutations.update(previousParagraph);
    return this.collapse(previousInline.firstChild, previousOffset);
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
    const nextInline = nextParagraph.firstChild;
    const nextOffset = this.focusOffset;
    this.#mutations.remove(paragraphToBeRemoved);
    return this.collapse(nextInline.firstChild, nextOffset);
  }

  /**
   * Cleans up all the affected paragraphs.
   *
   * @param {Set<HTMLDivElement>} affectedParagraphs
   * @param {Set<HTMLSpanElement>} affectedInlines
   */
  cleanUp(affectedParagraphs, affectedInlines) {
    // Remove empty inlines
    for (const inline of affectedInlines) {
      if (inline.textContent === "") {
        inline.remove();
        this.#mutations.remove(inline);
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

    const affectedInlines = new Set();
    const affectedParagraphs = new Set();

    const startNode = getClosestTextNode(this.#range.startContainer);
    const endNode = getClosestTextNode(this.#range.endContainer);
    const startOffset = this.#range.startOffset;
    const endOffset = this.#range.endOffset;

    let previousNode = null;
    let nextNode = null;

    // This is the simplest case, when the startNode and
    // the endNode are the same and they're textNodes.
    if (startNode === endNode) {
      this.#textNodeIterator.currentNode = startNode;
      previousNode = this.#textNodeIterator.previousNode();

      this.#textNodeIterator.currentNode = startNode;
      nextNode = this.#textNodeIterator.nextNode();

      const inline = getInline(startNode);
      const paragraph = getParagraph(startNode);
      affectedInlines.add(inline);
      affectedParagraphs.add(paragraph);

      const newNodeValue = removeSlice(
        startNode.nodeValue,
        startOffset,
        endOffset
      );
      if (newNodeValue === "") {
        const lineBreak = createLineBreak();
        inline.replaceChildren(lineBreak);
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

    const startInline = getInline(startNode);
    const startParagraph = getParagraph(startNode);
    const endInline = getInline(endNode);
    const endParagraph = getParagraph(endNode);

    SafeGuard.start();
    do {
      SafeGuard.update();

      const { currentNode } = this.#textNodeIterator;

      // We retrieve the inline and paragraph of the
      // current node.
      const inline = getInline(currentNode);
      const paragraph = getParagraph(currentNode);
      affectedInlines.add(inline);
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
        if (isLineBreak(endNode)
         || (isTextNode(endNode)
          && endOffset === endNode.nodeValue.length)) {
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

        if (inline.childNodes.length === 0) {
          inline.remove();
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
        const newEmptyInline = createEmptyInline(this.#currentStyle);
        mergedParagraph.appendChild(newEmptyInline);
        return this.collapse(newEmptyInline.firstChild, 0);
      }
    }

    if (startInline.childNodes.length === 0
     && endInline.childNodes.length > 0) {
      startInline.remove();
      return this.collapse(endNode, 0);
    } else if (startInline.childNodes.length > 0
     && endInline.childNodes.length === 0) {
      endInline.remove();
      return this.collapse(startNode, startOffset);
    } else if (startInline.childNodes.length === 0
     && endInline.childNodes.length === 0) {
      const previousInline = startInline.previousElementSibling;
      const nextInline = endInline.nextElementSibling;
      startInline.remove();
      endInline.remove();
      if (previousInline) {
        return this.collapse(previousInline.firstChild, previousInline.firstChild.nodeValue.length);
      }
      if (nextInline) {
        return this.collapse(nextInline.firstChild, 0);
      }
      const newEmptyInline = createEmptyInline(this.#currentStyle);
      startParagraph.appendChild(newEmptyInline);
      return this.collapse(newEmptyInline.firstChild, 0);
    }

    return this.collapse(startNode, startOffset);
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
      // The styles are applied to the node completelly.
      if (startOffset === 0 && endOffset === endNode.nodeValue.length) {
        const paragraph = this.startParagraph;
        const inline = this.startInline;
        setParagraphStyles(paragraph, newStyles);
        setInlineStyles(inline, newStyles);

        // The styles are applied to a part of the node.
      } else if (startOffset !== endOffset) {
        const paragraph = this.startParagraph;
        setParagraphStyles(paragraph, newStyles);
        const inline = this.startInline;
        const midText = startNode.splitText(startOffset);
        const endText = midText.splitText(endOffset - startOffset);
        const midInline = createInlineFrom(inline, midText, newStyles);
        inline.after(midInline);
        if (endText.length > 0) {
          const endInline = createInline(endText, inline.style);
          midInline.after(endInline);
        }

        // NOTE: This is necessary because sometimes
        // inlines are splitted from the beginning
        // to a mid offset and then the starting node
        // remains empty.
        if (inline.firstChild.nodeValue === "") {
          inline.remove();
        }

        // FIXME: This can change focus <-> anchor order.
        this.setSelection(midText, 0, midText, midText.nodeValue.length);

        // The styles are applied to the paragraph.
      } else {
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
        const inline = getInline(this.#textNodeIterator.currentNode);
        // If we're at the start node and offset is greater than 0
        // then we should split the inline and apply styles to that
        // new inline.
        if (
          this.#textNodeIterator.currentNode === startNode &&
          startOffset > 0
        ) {
          const newInline = splitInline(inline, startOffset);
          setInlineStyles(newInline, newStyles);
          inline.after(newInline);
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
          setInlineStyles(inline, newStyles);

          // If we're at end node
        } else if (
          this.#textNodeIterator.currentNode === endNode &&
          endOffset < endNode.nodeValue.length
        ) {
          const newInline = splitInline(inline, endOffset);
          setInlineStyles(inline, newStyles);
          inline.after(newInline);
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
      newStyles
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
