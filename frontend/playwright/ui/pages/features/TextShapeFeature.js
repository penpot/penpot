export class TextShapeFeature {
  #page = null

  constructor(page) {
    this.#page = page

    // This is the button located on the toolbar
    this.textShapeButton = page.getByRole("button", { name: "Text (T)" })
    this.textEditorContainer = page.getByTestId("text-editor-container")
    this.textEditorContent = page.getByTestId("text-editor-content")
  }

  get page() {
    return this.#page
  }

  async createNewFromCoordiantes(sx, sy, ex, ey) {
    await this.#page.mouse.move(sx, sy);
    await this.#page.mouse.down();
    await this.#page.mouse.move(ex, ey);
    await this.#page.mouse.up();
  }

  async insertText(textToInsert) {
    await this.#page.type(textToInsert);
  }

  async paste(format, data) {
    await this.#page.evaluate(
      (format, data) => {
        const clipboardData = new DataTransfer();
        clipboardData.setData(format, data);
        const event = new ClipboardEvent("paste", {
          clipboardData,
        });
        window.dispatchEvent(event);
      },
      [format, data],
    );
  }

  async pasteText(textToPaste) {
    await this.paste('text/plain', textToPaste);
  }

  async pasteHTML(htmlToPaste) {
    await this.paste('text/html', htmlToPaste);
  }


}

export default TextShapeFeature
