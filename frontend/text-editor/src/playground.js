import "./style.css";
import "./fonts.css";
import "./editor/TextEditor.css";
import initWasmModule from "./wasm/render_wasm.js";
import { UUID } from "./playground/uuid.js";
import { Rect, Point } from "./playground/geom.js";
import { WASMModuleWrapper } from "./playground/wasm.js";
import { FontManager } from "./playground/font.js";
import { TextContent, TextParagraph, TextSpan } from "./playground/text.js";
import { Viewport } from "./playground/viewport.js";
import { Fill } from "./playground/fill.js";
import { Shape } from "./playground/shape.js";
import { Color } from "./playground/color.js";
import { TextEditor } from "./editor/TextEditor.js";
import { SelectionControllerDebug } from "./editor/debug/SelectionControllerDebug.js";

function debounce(fn, delay) {
  let timeout;
  return (...args) => {
    clearTimeout(timeout);
    timeout = setTimeout(() => fn(...args), delay);
  };
}

class TextEditorPlayground {
  #module;
  #canvas;
  #textEditor;
  #fontManager;
  #viewport = new Viewport();
  #isPanning = false;
  #shapes = new Map();
  #ui;
  #resizeObserver;

  constructor(module, canvas) {
    this.#canvas = canvas;
    this.#ui = {
      appElement: document.getElementById("app"),
      textEditorElement: document.querySelector(".text-editor-content"),
      shapePositionXElement: document.getElementById("position-x"),
      shapePositionYElement: document.getElementById("position-y"),
      shapeRotationElement: document.getElementById("rotation"),

      fontFamilyElement: document.getElementById("font-family"),
      fontSizeElement: document.getElementById("font-size"),
      fontWeightElement: document.getElementById("font-weight"),
      fontStyleElement: document.getElementById("font-style"),

      directionLTRElement: document.getElementById("direction-ltr"),
      directionRTLElement: document.getElementById("direction-rtl"),

      lineHeightElement: document.getElementById("line-height"),
      letterSpacingElement: document.getElementById("letter-spacing"),

      textAlignLeftElement: document.getElementById("text-align-left"),
      textAlignCenterElement: document.getElementById("text-align-center"),
      textAlignRightElement: document.getElementById("text-align-right"),
      textAlignJustifyElement: document.getElementById("text-align-justify"),
    };
    this.#module = new WASMModuleWrapper(module);
    this.#fontManager = new FontManager(this.#module);
    this.#textEditor = new TextEditor(this.#ui.textEditorElement, canvas, {
      styleDefaults: {
        "font-family": "MontserratAlternates",
        "font-size": "14",
        "font-weight": "500",
        "font-style": "normal",
        "line-height": "1.2",
        "letter-spacing": "0",
        direction: "ltr",
        "text-align": "left",
        "text-transform": "none",
        "text-decoration": "none",
        "--typography-ref-id": '["~#\'",null]',
        "--typography-ref-file": '["~#\'",null]',
        "--font-id": '["~#\'","MontserratAlternates"]',
        "--fills": '[["^ ","~:fill-color","#000000","~:fill-opacity",1]]',
      },
      debug: new SelectionControllerDebug({
        direction: document.getElementById("direction"),
        multiElement: document.getElementById("multi"),
        multiTextSpanElement: document.getElementById("multi-textspan"),
        multiParagraphElement: document.getElementById("multi-paragraph"),
        isParagraphStart: document.getElementById("is-paragraph-start"),
        isParagraphEnd: document.getElementById("is-paragraph-end"),
        isTextSpanStart: document.getElementById("is-textspan-start"),
        isTextSpanEnd: document.getElementById("is-textspan-end"),
        isTextAnchor: document.getElementById("is-text-anchor"),
        isTextFocus: document.getElementById("is-text-focus"),
        focusNode: document.getElementById("focus-node"),
        focusOffset: document.getElementById("focus-offset"),
        focusTextSpan: document.getElementById("focus-textspan"),
        focusParagraph: document.getElementById("focus-paragraph"),
        anchorNode: document.getElementById("anchor-node"),
        anchorOffset: document.getElementById("anchor-offset"),
        anchorTextSpan: document.getElementById("anchor-textspan"),
        anchorParagraph: document.getElementById("anchor-paragraph"),
        startContainer: document.getElementById("start-container"),
        startOffset: document.getElementById("start-offset"),
        endContainer: document.getElementById("end-container"),
        endOffset: document.getElementById("end-offset"),
      }),
    });
  }

  get canvas() {
    return this.#canvas;
  }

  #onWheel = (e) => {
    e.preventDefault();
    const textShape = this.#shapes.get("text");

    if (!textShape) {
      console.warn("Text shape not found");
      return;
    }
    const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9;
    this.#viewport.zoom *= zoomFactor;
    this.#viewport.pan(e.movementX, e.movementY);
    this.#textEditor.updatePositionWithViewportAndShape(
      this.#viewport,
      textShape,
    );
    this.render();
  };

  #onPointer = (e) => {
    switch (e.type) {
      case "pointermove":
        if (this.#isPanning) {
          this.#viewport.pan(e.movementX, e.movementY);
          const textShape = this.#shapes.get("text");
          if (!textShape) {
            console.warn("Text shape not found");
            return;
          }
          this.#textEditor.updatePositionWithViewportAndShape(
            this.#viewport,
            textShape,
          );
          this.render();
        }
        break;
      case "pointerdown":
        this.#isPanning = true;
        break;
      case "pointerleave":
      case "pointerup":
        this.#isPanning = false;
        break;
    }
  };

  #onClick = (e) => {
    console.log("click", e.type, e);
    const textShape = this.#shapes.get("text");
    if (!textShape) {
      console.warn("Text shape not found");
      return;
    }

    this.#module.call("use_shape", ...textShape.id);
    const caretPosition = this.#module.call(
      "get_caret_position_at",
      e.offsetX,
      e.offsetY,
    );
    console.log("caretPosition", caretPosition);
  };

  #onResize = (_entries) => {
    this.#resizeCanvas();
    this.#module.call(
      "resize_viewbox",
      this.#canvas.width,
      this.#canvas.height,
    );
    this.render();
  };

  #onNeedsLayout = (_e) => {
    const textShape = this.#shapes.get("text");
    if (!textShape) {
      console.warn("Text shape not found");
      return;
    }
    textShape.textContent.updateFromDOM(
      this.#textEditor.root,
      this.#fontManager,
    );
    this.#setShape(textShape);
    this.render();
  };

  #onStyleChange = (e) => {
    const fontSize = parseInt(e.detail.getPropertyValue("font-size"), 10);
    const fontWeight = e.detail.getPropertyValue("font-weight");
    const fontStyle = e.detail.getPropertyValue("font-style");
    const fontFamily = e.detail.getPropertyValue("font-family");

    this.#ui.fontFamilyElement.value = fontFamily;
    this.#ui.fontSizeElement.value = fontSize;
    this.#ui.fontStyleElement.value = fontStyle;
    this.#ui.fontWeightElement.value = fontWeight;

    const textAlign = e.detail.getPropertyValue("text-align");
    this.#ui.textAlignLeftElement.checked = textAlign === "left";
    this.#ui.textAlignCenterElement.checked = textAlign === "center";
    this.#ui.textAlignRightElement.checked = textAlign === "right";
    this.#ui.textAlignJustifyElement.checked = textAlign === "justify";

    const direction = e.detail.getPropertyValue("direction");
    this.#ui.directionLTRElement.checked = direction === "ltr";
    this.#ui.directionRTLElement.checked = direction === "rtl";
  };

  #resizeCanvas(
    width = Math.floor(this.#canvas.clientWidth),
    height = Math.floor(this.#canvas.clientHeight),
  ) {
    let resized = false;
    if (this.#canvas.width !== width) {
      this.#canvas.width = width;
      resized = true;
    }
    if (this.#canvas.height !== height) {
      this.#canvas.height = height;
      resized = true;
    }
    return resized;
  }

  #setupCanvasContext() {
    this.#module.registerContext(this.#canvas, "webgl2", {
      antialias: true,
      depth: true,
      alpha: false,
      stencil: true,
      preserveDrawingBuffer: true,
    });
    this.#resizeCanvas();
    this.#module.call("init", this.#canvas.width, this.#canvas.height);
    this.#module.call("set_render_options", 0, 1);
  }

  #setupCanvas() {
    this.#resizeObserver = new ResizeObserver(this.#onResize);
    this.#resizeObserver.observe(this.#canvas);
    this.#module.call("set_canvas_background", Color.parse("#FABADA").argb32);
    this.#module.call("set_view", 1, 0, 0);
    this.#module.call("init_shapes_pool", 1);
  }

  #setupInteraction() {
    this.#canvas.addEventListener("wheel", this.#onWheel);
    this.#canvas.addEventListener("pointerdown", this.#onPointer);
    this.#canvas.addEventListener("pointermove", this.#onPointer);
    this.#canvas.addEventListener("pointerup", this.#onPointer);
    this.#canvas.addEventListener("pointerleave", this.#onPointer);
    this.#canvas.addEventListener("click", this.#onClick);
  }

  async #setupFonts() {
    await this.#fontManager.load();
  }

  #onDirectionChange = (e) => {
    if (e.target.checked) {
      this.#textEditor.applyStylesToSelection({
        direction: e.target.value,
      });
    }
  };

  #onTextAlignChange = (e) => {
    if (e.target.checked) {
      this.#textEditor.applyStylesToSelection({
        "text-align": e.target.value,
      });
    }
  };

  #onFontFamilyChange = (e) => {
    const fontStyles = this.#fontManager.fonts.get(e.target.value);
    for (const fontStyle of fontStyles) {
      console.log("fontStyle", fontStyle);
    }
    this.#textEditor.applyStylesToSelection({
      "font-family": e.target.value,
    });
  };

  #onFontWeightChange = (e) => {
    this.#textEditor.applyStylesToSelection({
      "font-weight": e.target.value,
    });
  };

  #onFontSizeChange = (e) => {
    this.#textEditor.applyStylesToSelection({
      "font-size": e.target.value,
    });
  };

  #onFontStyleChange = (e) => {
    this.#textEditor.applyStylesToSelection({
      "font-style": e.target.value,
    });
  };

  #onLineHeightChange = (e) => {
    this.#textEditor.applyStylesToSelection({
      "line-height": e.target.value,
    });
  };

  #onLetterSpacingChange = (e) => {
    this.#textEditor.applyStylesToSelection({
      "letter-spacing": e.target.value,
    });
  };

  #onShapePositionChange = (_e) => {
    const textShape = this.#shapes.get("text");
    if (!textShape) {
      console.warn("Text shape not found");
      return;
    }
    textShape.selrect.left = this.#ui.shapePositionXElement.valueAsNumber;
    textShape.selrect.top = this.#ui.shapePositionYElement.valueAsNumber;
    this.#module.call(
      "set_shape_selrect",
      textShape.selrect.left,
      textShape.selrect.top,
      textShape.selrect.right,
      textShape.selrect.bottom,
    );
    this.#textEditor.updatePositionWithViewportAndShape(
      this.#viewport,
      textShape,
    );
    this.render();
  };

  #onShapeRotationChange = (e) => {
    const textShape = this.#shapes.get("text");
    if (!textShape) {
      console.warn("Text shape not found");
      return;
    }
    textShape.rotation = e.target.valueAsNumber;
    this.#module.call("set_shape_rotation", textShape.rotation);
    this.#textEditor.updatePositionWithViewportAndShape(
      this.#viewport,
      textShape,
    );
    this.render();
  };

  #setupUI() {
    const fontFamiliesFragment = document.createDocumentFragment();
    for (const [font, fontData] of this.#fontManager.fonts) {
      const fontFamilyOptionElement = document.createElement("option");
      fontFamilyOptionElement.value = font;
      fontFamilyOptionElement.textContent = font;
      fontFamiliesFragment.appendChild(fontFamilyOptionElement);
    }
    this.#ui.fontFamilyElement.replaceChildren(fontFamiliesFragment);

    this.#ui.shapePositionXElement.addEventListener(
      "change",
      this.#onShapePositionChange,
    );
    this.#ui.shapePositionYElement.addEventListener(
      "change",
      this.#onShapePositionChange,
    );
    this.#ui.shapeRotationElement.addEventListener(
      "change",
      this.#onShapeRotationChange,
    );

    this.#ui.directionLTRElement.addEventListener(
      "change",
      this.#onDirectionChange,
    );
    this.#ui.directionRTLElement.addEventListener(
      "change",
      this.#onDirectionChange,
    );

    this.#ui.textAlignLeftElement.addEventListener(
      "change",
      this.#onTextAlignChange,
    );
    this.#ui.textAlignCenterElement.addEventListener(
      "change",
      this.#onTextAlignChange,
    );
    this.#ui.textAlignRightElement.addEventListener(
      "change",
      this.#onTextAlignChange,
    );
    this.#ui.textAlignJustifyElement.addEventListener(
      "change",
      this.#onTextAlignChange,
    );

    this.#ui.fontFamilyElement.addEventListener(
      "change",
      this.#onFontFamilyChange,
    );
    this.#ui.fontWeightElement.addEventListener(
      "change",
      this.#onFontWeightChange,
    );
    this.#ui.fontSizeElement.addEventListener("change", this.#onFontSizeChange);
    this.#ui.fontStyleElement.addEventListener(
      "change",
      this.#onFontStyleChange,
    );
    this.#ui.lineHeightElement.addEventListener(
      "change",
      this.#onLineHeightChange,
    );
    this.#ui.letterSpacingElement.addEventListener(
      "change",
      this.#onLetterSpacingChange,
    );
  }

  #setShape(shape) {
    this.#module.call("use_shape", ...shape.id);
    this.#module.call("set_parent", ...shape.parentId);
    this.#module.call("set_shape_type", shape.type);
    this.#module.call("set_shape_rotation", shape.rotation);
    this.#module.call(
      "set_shape_selrect",
      shape.selrect.left,
      shape.selrect.top,
      shape.selrect.right,
      shape.selrect.bottom,
    );
    if (shape.childrenIds.length > 0 && Array.isArray(shape.childrenIds)) {
      let ptr = this.#module.call(
        "alloc_bytes",
        shape.childrenIds.length * UUID.BYTE_LENGTH,
      );
      for (const childrenId of shape.childrenIds) {
        this.#module.set(ptr, UUID.BYTE_LENGTH, childrenId);
        ptr += UUID.BYTE_LENGTH;
      }
      this.#module.call("set_children");
    }
    if (shape.textContent && shape.textContent instanceof TextContent) {
      this.#module.call("clear_shape_text");
      for (const paragraph of shape.textContent.paragraphs) {
        const ptr = this.#module.call("alloc_bytes", paragraph.byteLength);
        const view = this.#module.viewOf(ptr, paragraph.byteLength);
        // Number of text leaves in the paragraph.
        view.setUint32(0, paragraph.leaves.length, true);

        console.log("lineHeight", paragraph.lineHeight);

        // Serialize paragraph attributes
        view.setUint8(4, paragraph.textAlign, true); // text-align: left
        view.setUint8(5, paragraph.textDirection, true); // text-direction: LTR
        view.setUint8(6, paragraph.textDecoration, true); // text-decoration: none
        view.setUint8(7, paragraph.textTransform, true); // text-transform: none
        view.setFloat32(8, paragraph.lineHeight, true); // line-height: 1.2
        view.setFloat32(12, paragraph.letterSpacing, true); // letter-spacing: 0.0
        view.setUint32(16, paragraph.typographyRefFile[0], true); // typography-ref-file (UUID part 1)
        view.setUint32(20, paragraph.typographyRefFile[1], true); // typography-ref-file (UUID part 2)
        view.setUint32(24, paragraph.typographyRefFile[2], true); // typography-ref-file (UUID part 3)
        view.setUint32(28, paragraph.typographyRefFile[3], true); // typography-ref-file (UUID part 4)
        view.setUint32(32, paragraph.typographyRefId[0], true); // typography-ref-id (UUID part 1)
        view.setUint32(36, paragraph.typographyRefId[1], true); // typography-ref-id (UUID part 2)
        view.setUint32(40, paragraph.typographyRefId[2], true); // typography-ref-id (UUID part 3)
        view.setUint32(44, paragraph.typographyRefId[3], true); // typography-ref-id (UUID part 4)

        let offset = TextParagraph.BYTE_LENGTH;
        for (const leaf of paragraph.leaves) {
          // Serialize leaf attributes
          view.setUint8(offset + 0, leaf.fontStyle, true); // font-style: normal
          view.setUint8(offset + 1, leaf.textDecoration, true); // text-decoration: none
          view.setUint8(offset + 2, leaf.textTransform, true); // text-transform: none
          view.setUint8(offset + 3, leaf.textDirection, true); // text-direction: ltr
          view.setFloat32(offset + 4, leaf.fontSize, true); // font-size
          view.setFloat32(offset + 8, leaf.letterSpacing, true); // letter-spacing
          view.setInt32(offset + 12, leaf.fontWeight, true); // font-weight: normal
          view.setUint32(offset + 16, leaf.fontId[0], true); // font-id (UUID part 1)
          view.setUint32(offset + 20, leaf.fontId[1], true); // font-id (UUID part 2)
          view.setUint32(offset + 24, leaf.fontId[2], true); // font-id (UUID part 3)
          view.setUint32(offset + 28, leaf.fontId[3], true); // font-id (UUID part 4)
          view.setUint32(offset + 32, leaf.fontFamilyHash, true); // font-family hash
          view.setUint32(offset + 36, leaf.fontVariantId[0], true); // font-variant-id (UUID part 1)
          view.setUint32(offset + 40, leaf.fontVariantId[1], true); // font-variant-id (UUID part 2)
          view.setUint32(offset + 44, leaf.fontVariantId[2], true); // font-variant-id (UUID part 3)
          view.setUint32(offset + 48, leaf.fontVariantId[3], true); // font-variant-id (UUID part 4)
          view.setUint32(offset + 52, leaf.textByteLength, true); // text-length
          view.setUint32(offset + 56, leaf.fills.length, true); // total fills count

          leaf.fills.forEach((fill, index) => {
            const fillOffset = offset + 60 + index * Fill.BYTE_LENGTH;
            if (fill.type === Fill.Type.SOLID) {
              view.setUint8(fillOffset + 0, fill.type, true);
              view.setUint32(fillOffset + 4, fill.solid.color.argb32, true);
            }
          });

          offset += leaf.leafByteLength;
        }

        const textBuffer = paragraph.textBuffer;
        this.#module.set(ptr + offset, textBuffer.byteLength, textBuffer);
        this.#module.call("set_shape_text_content");
      }
      this.#module.call("update_shape_text_layout");
    }
  }

  #setupShapes() {
    const textShape = new Shape({
      type: Shape.Type.Text,
      selrect: new Rect(
        new Point(canvas.width, canvas.height),
        new Point(0, 0),
      ),
      textContent: TextContent.fromDOM(
        this.#textEditor.root,
        this.#fontManager,
      ),
    });
    const rootShape = new Shape({
      id: UUID.ZERO,
      childrenIds: [textShape.id],
    });
    this.#shapes.set("root", rootShape);
    this.#setShape(rootShape);

    this.#shapes.set("text", textShape);
    this.#setShape(textShape);
  }

  #setupTextEditor() {
    this.#textEditor.addEventListener("needslayout", this.#onNeedsLayout);
    this.#textEditor.addEventListener("stylechange", this.#onStyleChange);
  }

  async setup() {
    this.#setupCanvasContext();
    this.#setupCanvas();
    this.#setupInteraction();
    this.#setupTextEditor();
    await this.#setupFonts();
    this.#setupShapes();
    this.#setupUI();
  }

  #debouncedRender = debounce(
    () => this.#module.call("render", Date.now()),
    16,
  );

  render() {
    this.#module.call(
      "set_view",
      this.#viewport.zoom,
      this.#viewport.x,
      this.#viewport.y,
    );
    this.#module.call("render_from_cache");
    this.#debouncedRender();
  }
}

const module = await initWasmModule();
const canvas = document.getElementById("canvas");

const textEditorPlayground = new TextEditorPlayground(module, canvas, {
  shouldUpdatePositionOnScroll: true,
});
await textEditorPlayground.setup();
textEditorPlayground.render();
