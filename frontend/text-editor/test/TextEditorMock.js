import { createRoot } from "~/editor/content/dom/Root";
import { createParagraph } from "~/editor/content/dom/Paragraph";
import { createEmptyInline, createInline } from "~/editor/content/dom/Inline";
import { createLineBreak } from "~/editor/content/dom/LineBreak";

export class TextEditorMock extends EventTarget {
  /**
   * Returns the template used for the text editor mock.
   *
   * @returns {HTMLDivElement}
   */
  static getTemplate() {
    const container = document.createElement("div");
    container.id = "test";
    container.innerHTML = `<div class="text-editor-container align-top">
    <div
      id="text-editor-selection-imposter"
      class="text-editor-selection-imposter"></div>
    <div
      class="text-editor-content"
      contenteditable="true"
      role="textbox"
      aria-multiline="true"
      aria-autocomplete="none"
      spellcheck="false"
      autocapitalize="false"></div>
  </div>`;
    document.body.appendChild(container);
    return container;
  }

  /**
   * Creates an editor with a custom root.
   *
   * @param {HTMLDivElement} root
   * @returns {HTMLDivElement}
   */
  static createTextEditorMockWithRoot(root) {
    const container = TextEditorMock.getTemplate();
    const selectionImposterElement = container.querySelector(
      ".text-editor-selection-imposter"
    );
    const textEditorMock = new TextEditorMock(
      container.querySelector(".text-editor-content"),
      {
        root,
        selectionImposterElement,
      }
    );
    return textEditorMock;
  }

  /**
   * Creates a TextEditor mock with paragraphs.
   *
   * @param {Array<HTMLDivElement>} paragraphs
   * @returns
   */
  static createTextEditorMockWithParagraphs(paragraphs) {
    const root = createRoot(paragraphs);
    return this.createTextEditorMockWithRoot(root);
  }

  /**
   * Creates an empty TextEditor mock.
   *
   * @returns
   */
  static createTextEditorMockEmpty() {
    const root = createRoot([
      createParagraph([createInline(createLineBreak())]),
    ]);
    return this.createTextEditorMockWithRoot(root);
  }

  /**
   * Creates a TextEditor mock with some text.
   *
   * NOTE: If the text is empty an empty inline will be
   * created.
   *
   * @param {string} text
   * @returns
   */
  static createTextEditorMockWithText(text) {
    return this.createTextEditorMockWithParagraphs([
      createParagraph([
        text.length === 0
        ? createEmptyInline()
        : createInline(new Text(text))
      ]),
    ]);
  }

  /**
   * Creates a TextEditor mock with some inlines and
   * only one paragraph.
   *
   * @param {Array<HTMLSpanElement>} inlines
   * @returns
   */
  static createTextEditorMockWithParagraph(inlines) {
    return this.createTextEditorMockWithParagraphs([createParagraph(inlines)]);
  }

  #element = null;
  #root = null;
  #selectionImposterElement = null;

  constructor(element, options) {
    super();
    this.#element = element;
    this.#root = options?.root;
    this.#selectionImposterElement = options?.selectionImposterElement;
    this.#element.appendChild(options?.root);
  }

  get element() {
    return this.#element;
  }

  get root() {
    return this.#root;
  }
}

export default TextEditorMock;
