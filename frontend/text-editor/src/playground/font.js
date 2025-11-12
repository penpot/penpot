import { UUID } from "./uuid.js";
import { fromStyle } from "./style.js";

export const FontStyle = {
  NORMAL: 0,
  REGULAR: 0,
  ITALIC: 1,
  OBLIQUE: 1,

  fromStyle,
};

export class FontData {
  #id;
  #url;
  #font;
  #name;
  #styleName;
  #extension;
  #weight;
  #style;
  #arrayBuffer;

  constructor(init) {
    this.#id = new UUID();
    this.#font = init.font;
    this.#url = `fonts/${this.#font}`;
    const [name, styleNameAndExtension] = this.#font.split("-");
    const [styleName, extension] = styleNameAndExtension.split(".");
    const isItalic = styleName.endsWith("Italic");
    this.#styleName = styleName;
    this.#extension = extension;
    this.#name = name;
    this.#weight = styleName.replaceAll("Italic", "");
    this.#style = isItalic ? "italic" : "normal";
    this.#arrayBuffer = init.arrayBuffer;
  }

  get id() {
    return this.#id;
  }
  get url() {
    return this.#url;
  }
  get extension() {
    return this.#extension;
  }
  get name() {
    return this.#name;
  }
  get weight() {
    return this.#weight;
  }
  get style() {
    return this.#style;
  }
  get arrayBuffer() {
    return this.#arrayBuffer;
  }

  get styleAsNumber() {
    return FontStyle.fromStyle(this.#style) ?? 0;
  }

  get weightAsNumber() {
    if (this.#styleName.startsWith("Thin")) {
      return 100;
    } else if (this.#styleName.startsWith("ExtraLight")) {
      return 200;
    } else if (this.#styleName.startsWith("Light")) {
      return 300;
    } else if (this.#styleName.startsWith("Regular")) {
      return 400;
    } else if (this.#styleName.startsWith("Medium")) {
      return 500;
    } else if (this.#styleName.startsWith("SemiBold")) {
      return 600;
    } else if (this.#styleName.startsWith("Bold")) {
      return 700;
    } else if (this.#styleName.startsWith("ExtraBold")) {
      return 800;
    } else if (this.#styleName.startsWith("Black")) {
      return 900;
    } else {
      return 400;
    }
  }
}

export class FontManager {
  #module;
  #fonts;

  /**
   *
   * @param {WASMModule} module
   */
  constructor(module) {
    this.#module = module;
  }

  get fonts() {
    return this.#fonts;
  }

  async #loadFontList() {
    const response = await fetch("fonts/fonts.txt");
    const text = await response.text();
    const fonts = text.split("\n").filter((l) => !!l);
    return fonts;
  }

  async #loadFonts() {
    const fontData = new Map();
    const fonts = await this.#loadFontList();
    for (const font of fonts) {
      const response = await fetch(`fonts/${font}`);
      const arrayBuffer = await response.arrayBuffer();
      const newFontData = new FontData({
        font: font,
        arrayBuffer,
      });
      if (!fontData.has(newFontData.name)) {
        fontData.set(newFontData.name, []);
      }
      const currentFontData = fontData.get(newFontData.name);
      currentFontData.push(newFontData);
    }
    return fontData;
  }

  #store(fonts) {
    for (const [fontName, fontStyles] of fonts) {
      for (const font of fontStyles) {
        const shapeId = UUID.ZERO;
        const fontId = font.id;
        const weight = font.weightAsNumber;
        const style = font.styleAsNumber;
        const size = font.arrayBuffer.byteLength;
        const ptr = this.#module.call("alloc_bytes", size);
        this.#module.set(ptr, size, new Uint8Array(font.arrayBuffer));
        const emoji = false;
        const fallback = false;
        this.#module.call(
          "store_font",
          shapeId[0],
          shapeId[1],
          shapeId[2],
          shapeId[3],
          fontId[0],
          fontId[1],
          fontId[2],
          fontId[3],
          weight,
          style,
          emoji,
          fallback,
        );
        this.#module.call(
          "is_font_uploaded",
          fontId[0],
          fontId[1],
          fontId[2],
          fontId[3],
          weight,
          style,
          emoji,
        );
      }
    }
  }

  async load() {
    this.#fonts = await this.#loadFonts();
    this.#store(this.#fonts);
  }
}
