import { UUID } from "./uuid.js";
import { fromStyle } from "./style.js";
import { FontStyle } from "./font.js";
import { Fill } from "./fill.js";

export const TextAlign = {
  LEFT: 0,
  CENTER: 1,
  RIGHT: 2,
  JUSTIFY: 3,

  fromStyle,
};

export const TextDirection = {
  LTR: 0,
  RTL: 1,

  fromStyle,
};

export const TextDecoration = {
  NONE: 0,
  UNDERLINE: 1,
  LINE_THROUGH: 2,
  OVERLINE: 3,

  fromStyle,
};

export const TextTransform = {
  NONE: 0,
  UPPERCASE: 1,
  LOWERCASE: 2,
  CAPITALIZE: 3,

  fromStyle,
};

export class TextSpan {
  static BYTE_LENGTH = 1340;

  static fromDOM(spanElement, fontManager) {
    const elementStyle = spanElement.style; //window.getComputedStyle(leafElement);
    const fontSize = parseFloat(elementStyle.getPropertyValue("font-size"));
    const fontStyle =
      FontStyle.fromStyle(elementStyle.getPropertyValue("font-style")) ??
      FontStyle.NORMAL;
    const fontWeight = parseInt(elementStyle.getPropertyValue("font-weight"));
    const letterSpacing = parseFloat(
      elementStyle.getPropertyValue("letter-spacing"),
    );
    const fontFamily = elementStyle.getPropertyValue("font-family");
    console.log("fontFamily", fontFamily);
    const fontStyles = fontManager.fonts.get(fontFamily);
    const textDecoration = TextDecoration.fromStyle(
      elementStyle.getPropertyValue("text-decoration"),
    );
    const textTransform = TextTransform.fromStyle(
      elementStyle.getPropertyValue("text-transform"),
    );
    const textDirection = TextDirection.fromStyle(
      elementStyle.getPropertyValue("text-direction"),
    );
    console.log(fontWeight, fontStyle);
    const font = fontStyles.find(
      (currentFontStyle) =>
        currentFontStyle.weightAsNumber === fontWeight &&
        currentFontStyle.styleAsNumber === fontStyle,
    );
    if (!font) {
      throw new Error(`Invalid font "${fontFamily}"`);
    }

    return new TextSpan({
      fontId: font.id, // leafElement.style.getPropertyValue("--font-id"),
      fontFamilyHash: 0,
      fontVariantId: UUID.ZERO, // leafElement.style.getPropertyValue("--font-variant-id"),
      fontStyle,
      fontSize,
      fontWeight,
      letterSpacing,
      textDecoration,
      textTransform,
      textDirection,
      text: spanElement.textContent,
    });
  }

  fontId = UUID.ZERO;
  fontFamilyHash = 0;
  fontVariantId = UUID.ZERO;
  fontStyle = 0;
  fontSize = 16;
  fontWeight = 400;
  letterSpacing = 0.0;
  textDecoration = 0;
  textTransform = 0;
  textDirection = 0;
  #text = "";
  fills = [new Fill()];

  constructor(init) {
    this.fontId = init?.fontId ?? UUID.ZERO;
    this.fontStyle = init?.fontStyle ?? 0;
    this.fontSize = init?.fontSize ?? 16;
    this.fontWeight = init?.fontWeight ?? 400;
    this.letterSpacing = init?.letterSpacing ?? 0;
    this.textDecoration = init?.textDecoration ?? 0;
    this.textTransform = init?.textTransform ?? 0;
    this.textDirection = init?.textDirection ?? 0;
    this.#text = init?.text ?? "";
    this.fills = init?.fills ?? [new Fill()];
  }

  get text() {
    return this.#text;
  }

  set text(newText) {
    this.#text = newText;
  }

  get textByteLength() {
    const text = this.text;
    const textEncoder = new TextEncoder();
    const textBuffer = textEncoder.encode(text);
    return textBuffer.byteLength;
  }

  get leafByteLength() {
    return TextLeaf.BYTE_LENGTH;
  }
}

export class TextParagraph {
  static BYTE_LENGTH = 48;

  static fromDOM(paragraphElement, fontManager) {
    return new TextParagraph({
      textAlign: TextAlign.fromStyle(
        paragraphElement.style.getPropertyValue("text-align"),
      ),
      textDecoration: TextDecoration.fromStyle(
        paragraphElement.style.getPropertyValue("text-decoration"),
      ),
      textTransform: TextTransform.fromStyle(
        paragraphElement.style.getPropertyValue("text-transform"),
      ),
      textDirection: TextDirection.fromStyle(
        paragraphElement.style.getPropertyValue("text-direction"),
      ),
      lineHeight: parseFloat(
        paragraphElement.style.getPropertyValue("line-height"),
      ),
      letterSpacing: parseFloat(
        paragraphElement.style.getPropertyValue("letter-spacing"),
      ),
      leaves: Array.from(paragraphElement.children, (leafElement) =>
        TextSpan.fromDOM(leafElement, fontManager),
      ),
    });
  }

  #leaves = [];
  textAlign = 0;
  textDecoration = 0;
  textTransform = 0;
  textDirection = 0;
  lineHeight = 1.2;
  letterSpacing = 0;

  constructor(init) {
    this.textAlign = init?.textAlign ?? TextAlign.LEFT;
    this.textDecoration = init?.textDecoration ?? TextDecoration.NONE;
    this.textTransform = init?.textTransform ?? TextTransform.NONE;
    this.textDirection = init?.textDirection ?? TextDirection.LTR;
    this.lineHeight = init?.lineHeight ?? 1.2;
    this.letterSpacing = init?.letterSpacing ?? 0.0;
    this.#leaves = init?.leaves ?? [];
    if (
      !Array.isArray(this.#leaves) ||
      !this.#leaves.every((leaf) => leaf instanceof TextSpan)
    ) {
      throw new TypeError("Invalid text leaves");
    }
  }

  get leaves() {
    return this.#leaves;
  }

  get text() {
    return this.#leaves.reduce((acc, leaf) => acc + leaf.text, "");
  }

  get textBuffer() {
    const textEncoder = new TextEncoder();
    const textBuffer = textEncoder.encode(this.text);
    return textBuffer;
  }

  get byteLength() {
    return (
      this.#leaves.reduce((acc, leaf) => acc + leaf.leafByteLength, 0) +
      this.textBuffer.byteLength +
      TextParagraph.BYTE_LENGTH
    );
  }
}

export class TextContent {
  static fromDOM(rootElement, fontManager) {
    const paragraphs = Array.from(rootElement.children, (paragraph) =>
      TextParagraph.fromDOM(paragraph, fontManager),
    );
    return new TextContent({ paragraphs });
  }

  #paragraphs = [];

  constructor(init) {
    this.#paragraphs = init?.paragraphs ?? [];
    if (
      !Array.isArray(this.#paragraphs) ||
      !this.#paragraphs.every((paragraph) => paragraph instanceof TextParagraph)
    ) {
      throw new TypeError("Invalid text paragraphs");
    }
  }

  get paragraphs() {
    return this.#paragraphs;
  }

  updateFromDOM(rootElement, fontManager) {
    this.#paragraphs = Array.from(rootElement.children, (paragraph) =>
      TextParagraph.fromDOM(paragraph, fontManager),
    );
  }
}
