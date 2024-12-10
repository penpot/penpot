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
import { createSelectionImposterFromClientRects } from "./selection/Imposter.js";
import { addEventListeners, removeEventListeners } from "./Event.js";
import { mapContentFragmentFromHTML, mapContentFragmentFromString } from "./content/dom/Content.js";
import { createRoot, createEmptyRoot } from "./content/dom/Root.js";
import { createParagraph } from "./content/dom/Paragraph.js";
import { createEmptyInline, createInline } from "./content/dom/Inline.js";
import { isLineBreak } from "./content/dom/LineBreak.js";
import LayoutType from "./layout/LayoutType.js";

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
   * Selection imposter keeps selection elements.
   *
   * @type {HTMLElement}
   */
  #selectionImposterElement = null;

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
   */
  #fixInsertCompositionText = false;

  /**
   * Constructor.
   *
   * @param {HTMLElement} element
   */
  constructor(element, options) {
    super();
    if (!(element instanceof HTMLElement))
      throw new TypeError("Invalid text editor element");

    this.#element = element;
    this.#selectionImposterElement = options?.selectionImposterElement;
    this.#events = {
      blur: this.#onBlur,
      focus: this.#onFocus,

      paste: this.#onPaste,
      cut: this.#onCut,
      copy: this.#onCopy,

      beforeinput: this.#onBeforeInput,
      input: this.#onInput,
    };
    this.#styleDefaults = options?.styleDefaults;
    this.#setup(options);
  }

  /**
   * Setups editor properties.
   */
  #setupElementProperties() {
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
  }

  /**
   * Setups the root element.
   */
  #setupRoot() {
    this.#root = createEmptyRoot(this.#styleDefaults);
    this.#element.appendChild(this.#root);
  }

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
    if (this.#selectionImposterElement.children.length > 0) {
      // We need to recreate the selection imposter when we've
      // already have one.
      this.#createSelectionImposter();
    }
    this.dispatchEvent(new e.constructor(e.type, e));
  };

  /**
   * Setups the elements, the properties and the
   * initial content.
   */
  #setup(options) {
    this.#setupElementProperties();
    this.#setupRoot();
    this.#changeController = new ChangeController(this);
    this.#changeController.addEventListener("change", this.#onChange);
    this.#selectionController = new SelectionController(
      this,
      document.getSelection(),
      options
    );
    this.#selectionController.addEventListener(
      "stylechange",
      this.#onStyleChange
    );
    addEventListeners(this.#element, this.#events, {
      capture: true,
    });
  }

  /**
   * Creates the selection imposter.
   */
  #createSelectionImposter() {
    // We only create a selection imposter if there's any selection
    // and if there is a selection imposter element to attach the
    // rects.
    if (
      this.#selectionImposterElement &&
      !this.#selectionController.isCollapsed
    ) {
      const rects = this.#selectionController.range?.getClientRects();
      if (rects) {
        const rect = this.#selectionImposterElement.getBoundingClientRect();
        this.#selectionImposterElement.replaceChildren(
          createSelectionImposterFromClientRects(rect, rects)
        );
      }
    }
  }

  /**
   * On blur we create a new FakeSelection if there's any.
   *
   * @param {FocusEvent} e
   */
  #onBlur = (e) => {
    this.#changeController.notifyImmediately();
    this.#selectionController.saveSelection();
    this.#createSelectionImposter();
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
    if (this.#selectionImposterElement) {
      this.#selectionImposterElement.replaceChildren();
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
  #onCopy = (e) => clipboard.copy(e, this, this.#selectionController);

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

    if (e.inputType === "insertCompositionText" && this.#fixInsertCompositionText) {
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
      })
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
   * CSS Style declaration for the current inline. From here we
   * can infer root, paragraph and inline declarations.
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
   * Creates a new inline from a string.
   *
   * @param {string} text
   * @param {Object.<string,*>|CSSStyleDeclaration} styles
   * @returns {HTMLSpanElement}
   */
  createInlineFromString(text, styles) {
    if (text === "") {
      return createEmptyInline(styles);
    }
    return createInline(new Text(text), styles);
  }

  /**
   * Creates a new inline.
   *
   * @param  {...any} args
   * @returns {HTMLSpanElement}
   */
  createInline(...args) {
    return createInline(...args);
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
      this.#onStyleChange
    );
    this.#selectionController.dispose();
    this.#selectionController = null;
    removeEventListeners(this.#element, this.#events);
    this.#element = null;
    this.#root = null;
  }
}

export function createRootFromHTML(html) {
  const fragment = mapContentFragmentFromHTML(html);
  const root = createRoot([]);
  root.replaceChildren(fragment);
  return root;
}

export function createRootFromString(string) {
  const fragment = mapContentFragmentFromString(string);
  const root = createRoot([]);
  root.replaceChild(fragment);
  return root;
}

export function isEditor(instance) {
  return (instance instanceof TextEditor);
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

export function create(element, options) {
  return new TextEditor(element, {...options});
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
