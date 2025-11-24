/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import clipboard from "./clipboard/index.js";
import commands from "./commands/index.js";
import ChangeController from "./controllers/ChangeController.js";
import SelectionController from "./controllers/SelectionController.js";
import { addEventListeners, removeEventListeners } from "./Event.js";
import {
  mapContentFragmentFromHTML,
  mapContentFragmentFromString,
} from "./content/dom/Content.js";
import { resetInertElement } from "./content/dom/Style.js";
import { createRoot, createEmptyRoot } from "./content/dom/Root.js";
import { createParagraph } from "./content/dom/Paragraph.js";
import { createEmptyTextSpan, createTextSpan } from "./content/dom/TextSpan.js";
import { isLineBreak } from "./content/dom/LineBreak.js";
import LayoutType from "./layout/LayoutType.js";

/**
 * @typedef {Object} TextEditorOptions
 * @property {CSSStyleDeclaration|Object.<string,*>} [styleDefaults]
 * @property {SelectionControllerDebug} [debug]
 * @property {boolean} [allowHTMLPaste=false]
 */

/**
 * Text Editor.
 */
export class TextEditor extends EventTarget {
  /**
   * Element content editable to be used by the TextEditor
   *
   * @type {HTMLElement}
   */
  #element = null;

  /**
   * Map/Dictionary of events.
   *
   * @type {Object.<string, Function>}
   */
  #events = null;

  /**
   * Root element that will contain the content.
   *
   * @type {HTMLElement}
   */
  #root = null;

  /**
   * Change controller controls when we should notify changes.
   *
   * @type {ChangeController}
   */
  #changeController = null;

  /**
   * Selection controller controls the current/saved selection.
   *
   * @type {SelectionController}
   */
  #selectionController = null;

  /**
   * Style defaults.
   *
   * @type {Object.<string, *>}
   */
  #styleDefaults = null;

  /**
   * FIXME: There is a weird case where the events
   * `beforeinput` and `input` have different `data` when
   * characters are deleted when the input type is
   * `insertCompositionText`.
   *
   * @type {boolean}
   */
  #fixInsertCompositionText = false;

  /**
   * Canvas element that renders text.
   *
   * @type {HTMLCanvasElement}
   */
  #canvas = null;

  /**
   * Constructor.
   *
   * @param {HTMLElement} element
   * @param {HTMLCanvasElement} canvas
   * @param {TextEditorOptions} [options]
   */
  constructor(element, canvas, options) {
    super();
    if (!(element instanceof HTMLElement))
      throw new TypeError("Invalid text editor element");

    this.#element = element;
    this.#canvas = canvas;
    this.#events = {
      blur: this.#onBlur,
      focus: this.#onFocus,

      paste: this.#onPaste,
      cut: this.#onCut,
      copy: this.#onCopy,

      beforeinput: this.#onBeforeInput,
      input: this.#onInput,
      keydown: this.#onKeyDown,
    };
    this.#styleDefaults = options?.styleDefaults;
    this.#setup(options);
  }

  /**
   * Setups editor properties.
   */
  #setupElementProperties(options) {
    if (!this.#element.isContentEditable) {
      this.#element.contentEditable = "true";
      // In `jsdom` it isn't enough to set the attribute 'contentEditable'
      // to `true` to work.
      // FIXME: Remove this when `jsdom` implements this interface.
      if (!this.#element.isContentEditable) {
        this.#element.setAttribute("contenteditable", "true");
      }
    }
    if (this.#element.spellcheck) this.#element.spellcheck = false;
    if (this.#element.autocapitalize) this.#element.autocapitalize = false;
    if (!this.#element.autofocus) this.#element.autofocus = true;
    if (!this.#element.role || this.#element.role !== "textbox")
      this.#element.role = "textbox";
    if (this.#element.ariaAutoComplete) this.#element.ariaAutoComplete = false;
    if (!this.#element.ariaMultiLine) this.#element.ariaMultiLine = true;
    this.#element.dataset.itype = "editor";
    if (options.shouldUpdatePositionOnScroll) {
      this.#updatePositionFromCanvas();
    }
  }

  /**
   * Setups the root element.
   */
  #setupRoot() {
    this.#root = createEmptyRoot(this.#styleDefaults);
    this.#element.appendChild(this.#root);
  }

  /**
   * Setups event listeners.
   */
  #setupListeners(options) {
    this.#changeController.addEventListener("change", this.#onChange);
    this.#selectionController.addEventListener(
      "stylechange",
      this.#onStyleChange,
    );
    if (options.shouldUpdatePositionOnScroll) {
      window.addEventListener("scroll", this.#onScroll);
    }
    addEventListeners(this.#element, this.#events, {
      capture: true,
    });
  }

  /**
   * Setups the elements, the properties and the
   * initial content.
   */
  #setup(options) {
    this.#setupElementProperties(options);
    this.#setupRoot(options);
    this.#changeController = new ChangeController(this);
    this.#selectionController = new SelectionController(
      this,
      document.getSelection(),
      options,
    );
    this.#setupListeners(options);
  }

  /**
   * Updates position from canvas.
   */
  #updatePositionFromCanvas() {
    const boundingClientRect = this.#canvas.getBoundingClientRect();
    this.#element.parentElement.style.top = boundingClientRect.top + "px";
    this.#element.parentElement.style.left = boundingClientRect.left + "px";
  }

  /**
   * Updates caret position using a transform object.
   *
   * @param {*} transform
   */
  updatePositionWithTransform(transform) {
    const x = transform?.x ?? 0.0;
    const y = transform?.y ?? 0.0;
    const rotation = transform?.rotation ?? 0.0;
    const scale = transform?.scale ?? 1.0;
    this.#updatePositionFromCanvas();
    this.#element.style.transformOrigin = "top left";
    this.#element.style.transform = `scale(${scale}) translate(${x}px, ${y}px) rotate(${rotation}deg)`;
  }

  /**
   * Updates caret position using viewport and shape.
   *
   * @param {Viewport} viewport
   * @param {Shape} shape
   */
  updatePositionWithViewportAndShape(viewport, shape) {
    this.updatePositionWithTransform({
      x: viewport.x + shape.selrect.x,
      y: viewport.y + shape.selrect.y,
      rotation: shape.rotation,
      scale: viewport.zoom,
    });
  }

  /**
   * Updates editor position when the page dispatches
   * a scroll event.
   *
   * @returns
   */
  #onScroll = () => this.#updatePositionFromCanvas();

  /**
   * Dispatchs a `change` event.
   *
   * @param {CustomEvent} e
   * @returns {void}
   */
  #onChange = (e) => this.dispatchEvent(new e.constructor(e.type, e));

  /**
   * Dispatchs a `stylechange` event.
   *
   * @param {CustomEvent} e
   * @returns {void}
   */
  #onStyleChange = (e) => {
    this.dispatchEvent(new e.constructor(e.type, e));
  };

  /**
   * On blur we create a new FakeSelection if there's any.
   *
   * @param {FocusEvent} e
   */
  #onBlur = (e) => {
    this.#changeController.notifyImmediately();
    this.#selectionController.saveSelection();
    this.dispatchEvent(new FocusEvent(e.type, e));
  };

  /**
   * On focus we should restore the FakeSelection from the current
   * selection.
   *
   * @param {FocusEvent} e
   */
  #onFocus = (e) => {
    if (!this.#selectionController.restoreSelection()) {
      this.selectAll();
    }
    this.dispatchEvent(new FocusEvent(e.type, e));
  };

  /**
   * Event called when the user pastes some text into the
   * editor.
   *
   * @param {ClipboardEvent} e
   */
  #onPaste = (e) => {
    clipboard.paste(e, this, this.#selectionController);
    this.#notifyLayout(LayoutType.FULL, null);
  };

  /**
   * Event called when the user cuts some text from the
   * editor.
   *
   * @param {ClipboardEvent} e
   */
  #onCut = (e) => clipboard.cut(e, this, this.#selectionController);

  /**
   * Event called when the user copies some text from the
   * editor.
   *
   * @param {ClipboardEvent} e
   */
  #onCopy = (e) => {
    this.dispatchEvent(
      new CustomEvent("clipboardchange", {
        detail: this.currentStyle,
      }),
    );

    clipboard.copy(e, this, this.#selectionController);
  };

  /**
   * Event called before the DOM is modified.
   *
   * @param {InputEvent} e
   */
  #onBeforeInput = (e) => {
    if (e.inputType === "historyUndo" || e.inputType === "historyRedo") {
      return;
    }

    if (e.inputType === "insertCompositionText" && !e.data) {
      e.preventDefault();
      this.#fixInsertCompositionText = true;
      return;
    }

    if (!(e.inputType in commands)) {
      if (e.inputType !== "insertCompositionText") {
        e.preventDefault();
      }
      return;
    }

    if (e.inputType in commands) {
      const command = commands[e.inputType];
      if (!this.#selectionController.startMutation()) {
        return;
      }
      command(e, this, this.#selectionController);
      const mutations = this.#selectionController.endMutation();
      this.#notifyLayout(LayoutType.FULL, mutations);
    }
  };

  /**
   * Event called after the DOM is modified.
   *
   * @param {InputEvent} e
   */
  #onInput = (e) => {
    if (e.inputType === "historyUndo" || e.inputType === "historyRedo") {
      return;
    }

    if (
      e.inputType === "insertCompositionText" &&
      this.#fixInsertCompositionText
    ) {
      e.preventDefault();
      this.#fixInsertCompositionText = false;
      if (e.data) {
        this.#selectionController.fixInsertCompositionText();
      }
      return;
    }

    if (e.inputType === "insertCompositionText" && e.data) {
      this.#notifyLayout(LayoutType.FULL, null);
    }
  };

  /**
   * Handles keydown events
   *
   * @param {KeyboardEvent} e
   */
  #onKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "a") {
      e.preventDefault();
      this.selectAll();
      return;
    }

    if ((e.ctrlKey || e.metaKey) && e.key === "Backspace") {
      e.preventDefault();

      if (!this.#selectionController.startMutation()) {
        return;
      }

      if (this.#selectionController.isCollapsed) {
        this.#selectionController.removeWordBackward();
      } else {
        this.#selectionController.removeSelected();
      }

      const mutations = this.#selectionController.endMutation();
      this.#notifyLayout(LayoutType.FULL, mutations);
    }
  };

  /**
   * Notifies that the edited texts needs layout.
   *
   * @param {'full'|'partial'} type
   * @param {CommandMutations} mutations
   */
  #notifyLayout(type = LayoutType.FULL, mutations) {
    this.dispatchEvent(
      new CustomEvent("needslayout", {
        detail: {
          type: type,
          mutations: mutations,
        },
      }),
    );
  }

  /**
   * Root element that contains all the paragraphs.
   *
   * @type {HTMLDivElement}
   */
  get root() {
    return this.#root;
  }

  set root(newRoot) {
    const previousRoot = this.#root;
    this.#root = newRoot;
    previousRoot.replaceWith(newRoot);
  }

  /**
   * Element that contains the root and that has the
   * contenteditable attribute.
   *
   * @type {HTMLElement}
   */
  get element() {
    return this.#element;
  }

  /**
   * Returns true if the content is in an empty state.
   *
   * @type {boolean}
   */
  get isEmpty() {
    return (
      this.#root.children.length === 1 &&
      this.#root.firstElementChild.children.length === 1 &&
      isLineBreak(this.#root.firstElementChild.firstElementChild.firstChild)
    );
  }

  /**
   * Indicates the amount of paragraphs in the current content.
   *
   * @type {number}
   */
  get numParagraphs() {
    return this.#root.children.length;
  }

  /**
   * CSS Style declaration for the current text span. From here we
   * can infer root, paragraph and text span declarations.
   *
   * @type {CSSStyleDeclaration}
   */
  get currentStyle() {
    return this.#selectionController.currentStyle;
  }

  /**
   * Focus the element
   */
  focus() {
    return this.#element.focus();
  }

  /**
   * Blurs the element
   */
  blur() {
    return this.#element.blur();
  }

  /**
   * Creates a new root.
   *
   * @param  {...any} args
   * @returns {HTMLDivElement}
   */
  createRoot(...args) {
    return createRoot(...args);
  }

  /**
   * Creates a new paragraph.
   *
   * @param  {...any} args
   * @returns {HTMLDivElement}
   */
  createParagraph(...args) {
    return createParagraph(...args);
  }

  /**
   * Creates a new text span from a string.
   *
   * @param {string} text
   * @param {Object.<string,*>|CSSStyleDeclaration} styles
   * @returns {HTMLSpanElement}
   */
  createTextSpanFromString(text, styles) {
    if (text === "") {
      return createEmptyTextSpan(styles);
    }
    return createTextSpan(new Text(text), styles);
  }

  /**
   * Creates a new text span.
   *
   * @param  {...any} args
   * @returns {HTMLSpanElement}
   */
  createTextSpan(...args) {
    return createTextSpan(...args);
  }

  /**
   * Applies the current styles to the selection or
   * the current DOM node at the caret.
   *
   * @param {*} styles
   */
  applyStylesToSelection(styles) {
    this.#selectionController.startMutation();
    this.#selectionController.applyStyles(styles);
    const mutations = this.#selectionController.endMutation();
    this.#notifyLayout(LayoutType.FULL, mutations);
    this.#changeController.notifyImmediately();
    return this;
  }

  /**
   * Selects all content.
   */
  selectAll() {
    this.#selectionController.selectAll();
    return this;
  }

  /**
   * Moves cursor to end.
   *
   * @returns
   */
  cursorToEnd() {
    this.#selectionController.cursorToEnd();
    return this;
  }

  /**
   * Disposes everything.
   */
  dispose() {
    this.#changeController.removeEventListener("change", this.#onChange);
    this.#changeController.dispose();
    this.#changeController = null;
    this.#selectionController.removeEventListener(
      "stylechange",
      this.#onStyleChange,
    );
    this.#selectionController.dispose();
    this.#selectionController = null;
    removeEventListeners(this.#element, this.#events);
    this.#element = null;
    this.#root = null;
  }
}

/**
 *
 * @param {string} html
 * @param {*} style
 * @param {boolean} allowHTMLPaste
 * @returns {Root}
 */
export function createRootFromHTML(html, style = undefined, allowHTMLPaste = undefined) {
  const fragment = mapContentFragmentFromHTML(html, style || undefined, allowHTMLPaste || undefined);
  const root = createRoot([], style);
  root.replaceChildren(fragment);
  resetInertElement();
  return root;
}

/**
 *
 * @param {string} string
 * @returns {Root}
 */
export function createRootFromString(string) {
  const fragment = mapContentFragmentFromString(string);
  const root = createRoot([]);
  root.replaceChild(fragment);
  return root;
}

export function isEditor(instance) {
  return instance instanceof TextEditor;
}

/* Convenience function based API for Text Editor */
export function getRoot(instance) {
  if (isEditor(instance)) {
    return instance.root;
  } else {
    return null;
  }
}

export function setRoot(instance, root) {
  if (isEditor(instance)) {
    instance.root = root;
  }

  return instance;
}

export function create(element, canvas, options) {
  return new TextEditor(element, canvas, { ...options });
}

export function getCurrentStyle(instance) {
  if (isEditor(instance)) {
    return instance.currentStyle;
  }
}

export function applyStylesToSelection(instance, styles) {
  if (isEditor(instance)) {
    return instance.applyStylesToSelection(styles);
  }
}

export function dispose(instance) {
  if (isEditor(instance)) {
    instance.dispose();
  }
}

export default TextEditor;
