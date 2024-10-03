var __typeError = (msg) => {
  throw TypeError(msg);
};
var __accessCheck = (obj, member, msg) => member.has(obj) || __typeError("Cannot " + msg);
var __privateGet = (obj, member, getter) => (__accessCheck(obj, member, "read from private field"), getter ? getter.call(obj) : member.get(obj));
var __privateAdd = (obj, member, value) => member.has(obj) ? __typeError("Cannot add the same private member more than once") : member instanceof WeakSet ? member.add(obj) : member.set(obj, value);
var __privateSet = (obj, member, value, setter) => (__accessCheck(obj, member, "write to private field"), setter ? setter.call(obj, value) : member.set(obj, value), value);
var __privateMethod = (obj, member, method) => (__accessCheck(obj, member, "access private method"), method);
var _timeout, _time, _hasPendingChanges, _onTimeout, _rootNode, _currentNode, _added, _removed, _updated, _textEditor, _selection, _ranges, _range, _focusNode, _focusOffset, _anchorNode, _anchorOffset, _savedSelection, _textNodeIterator, _currentStyle, _inertElement, _debug, _mutations, _styleDefaults, _SelectionController_instances, applyDefaultStylesToCurrentStyle_fn, applyStylesToCurrentStyle_fn, updateCurrentStyle_fn, _onSelectionChange, notifyStyleChange_fn, setup_fn, getSavedRange_fn, applyStylesTo_fn, _element, _events, _root, _changeController, _selectionController, _selectionImposterElement, _styleDefaults2, _TextEditor_instances, setupElementProperties_fn, setupRoot_fn, _onChange, _onStyleChange, setup_fn2, createSelectionImposter_fn, _onBlur, _onFocus, _onPaste, _onCut, _onCopy, _onBeforeInput, _onInput, notifyLayout_fn;
function copy(event, editor) {
}
function cut(event, editor) {
}
let canvas = null;
let context = null;
function getContext() {
  if (!canvas) {
    canvas = createCanvas(1, 1);
  }
  if (!context) {
    context = canvas.getContext("2d");
  }
  return context;
}
function createCanvas(width, height) {
  if ("OffscreenCanvas" in globalThis) {
    return new OffscreenCanvas(width, height);
  }
  return document.createElement("canvas");
}
function getByteAsHex(byte) {
  return byte.toString(16).padStart(2, "0");
}
function getColor(fillStyle) {
  const context2 = getContext();
  context2.fillStyle = fillStyle;
  context2.fillRect(0, 0, 1, 1);
  const imageData = context2.getImageData(0, 0, 1, 1);
  const [r, g, b, a] = imageData.data;
  return [`#${getByteAsHex(r)}${getByteAsHex(g)}${getByteAsHex(b)}`, a / 255];
}
function getFills(fillStyle) {
  const [color, opacity] = getColor(fillStyle);
  return `[["^ ","~:fill-color","${color}","~:fill-opacity",${opacity}]]`;
}
function mergeStyleDeclarations(target, source) {
  for (let index = 0; index < source.length; index++) {
    const styleName = source.item(index);
    target.setProperty(styleName, source.getPropertyValue(styleName));
  }
  return target;
}
function getComputedStyle(element) {
  const inertElement = document.createElement("div");
  let currentElement = element;
  while (currentElement) {
    for (let index = 0; index < currentElement.style.length; index++) {
      const styleName = currentElement.style.item(index);
      const currentValue = inertElement.style.getPropertyValue(styleName);
      if (currentValue) {
        const priority = currentElement.style.getPropertyPriority(styleName);
        if (priority === "important") {
          const newValue = currentElement.style.getPropertyValue(styleName);
          inertElement.style.setProperty(styleName, newValue);
        }
      } else {
        inertElement.style.setProperty(
          styleName,
          currentElement.style.getPropertyValue(styleName)
        );
      }
    }
    currentElement = currentElement.parentElement;
  }
  return inertElement.style;
}
function normalizeStyles(styleDeclaration) {
  const color = styleDeclaration.getPropertyValue("color");
  if (color) {
    styleDeclaration.removeProperty("color");
    styleDeclaration.setProperty("--fills", getFills(color));
  }
  const fontFamily = styleDeclaration.getPropertyValue("font-family");
  const fontId = styleDeclaration.getPropertyPriority("--font-id");
  if (fontFamily && !fontId) {
    styleDeclaration.removeProperty("font-family");
  }
  return styleDeclaration;
}
function setStyle(element, styleName, styleValue, styleUnit) {
  if (styleName.startsWith("--") && typeof styleValue !== "string" && typeof styleValue !== "number") {
    if (styleName === "--fills" && styleValue === null) debugger;
    element.style.setProperty(styleName, JSON.stringify(styleValue));
  } else {
    element.style.setProperty(styleName, styleValue + (styleUnit ?? ""));
  }
  return element;
}
function getStyleFromDeclaration(style, styleName, styleUnit) {
  if (styleName.startsWith("--")) {
    return style.getPropertyValue(styleName);
  }
  const styleValue = style.getPropertyValue(styleName);
  if (styleValue.endsWith(styleUnit)) {
    return styleValue.slice(0, -styleUnit.length);
  }
  return styleValue;
}
function setStylesFromObject(element, allowedStyles, styleObject) {
  for (const [styleName, styleUnit] of allowedStyles) {
    if (!(styleName in styleObject)) {
      continue;
    }
    const styleValue = styleObject[styleName];
    if (styleValue) {
      setStyle(element, styleName, styleValue, styleUnit);
    }
  }
  return element;
}
function setStylesFromDeclaration(element, allowedStyles, styleDeclaration) {
  for (const [styleName, styleUnit] of allowedStyles) {
    const styleValue = getStyleFromDeclaration(styleDeclaration, styleName, styleUnit);
    if (styleValue) {
      setStyle(element, styleName, styleValue, styleUnit);
    }
  }
  return element;
}
function setStyles(element, allowedStyles, styleObjectOrDeclaration) {
  if (styleObjectOrDeclaration instanceof CSSStyleDeclaration) {
    return setStylesFromDeclaration(
      element,
      allowedStyles,
      styleObjectOrDeclaration
    );
  }
  return setStylesFromObject(element, allowedStyles, styleObjectOrDeclaration);
}
function mergeStyles(allowedStyles, styleDeclaration, newStyles) {
  const mergedStyles = {};
  for (const [styleName, styleUnit] of allowedStyles) {
    if (styleName in newStyles) {
      mergedStyles[styleName] = newStyles[styleName];
    } else {
      mergedStyles[styleName] = getStyleFromDeclaration(styleDeclaration, styleName, styleUnit);
    }
  }
  return mergedStyles;
}
function isDisplayBlock(style) {
  return style.display === "block";
}
function createRandomId() {
  return Math.floor(Math.random() * Number.MAX_SAFE_INTEGER).toString(36);
}
function createElement(tag, options) {
  const element = document.createElement(tag);
  if (options == null ? void 0 : options.attributes) {
    Object.entries(options.attributes).forEach(
      ([name, value]) => element.setAttribute(name, value)
    );
  }
  if (options == null ? void 0 : options.data) {
    Object.entries(options.data).forEach(
      ([name, value]) => element.dataset[name] = value
    );
  }
  if ((options == null ? void 0 : options.styles) && (options == null ? void 0 : options.allowedStyles)) {
    setStyles(element, options.allowedStyles, options.styles);
  }
  if (options == null ? void 0 : options.children) {
    if (Array.isArray(options.children)) {
      element.append(...options.children);
    } else {
      element.appendChild(options.children);
    }
  }
  return element;
}
function isElement(element, nodeName) {
  return element.nodeType === Node.ELEMENT_NODE && element.nodeName === nodeName.toUpperCase();
}
function isOffsetAtStart(node, offset) {
  return offset === 0;
}
function isOffsetAtEnd(node, offset) {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.nodeValue.length === offset;
  }
  return true;
}
const TAG$3 = "BR";
function createLineBreak() {
  return document.createElement(TAG$3);
}
function isLineBreak(node) {
  return node.nodeType === Node.ELEMENT_NODE && node.nodeName === TAG$3;
}
const TAG$2 = "SPAN";
const TYPE$2 = "inline";
const QUERY$1 = `[data-itype="${TYPE$2}"]`;
const STYLES$2 = [
  ["--typography-ref-id"],
  ["--typography-ref-file"],
  ["--font-id"],
  ["--font-variant-id"],
  ["--fills"],
  ["font-variant"],
  ["font-family"],
  ["font-size", "px"],
  ["font-weight"],
  ["font-style"],
  ["line-height"],
  ["letter-spacing", "px"],
  ["text-decoration"],
  ["text-transform"]
];
function isInline(node) {
  if (!node) return false;
  if (!isElement(node, TAG$2)) return false;
  if (node.dataset.itype !== TYPE$2) return false;
  return true;
}
function isLikeInline(element) {
  return element ? [
    "A",
    "ABBR",
    "ACRONYM",
    "B",
    "BDO",
    "BIG",
    "BR",
    "BUTTON",
    "CITE",
    "CODE",
    "DFN",
    "EM",
    "I",
    "IMG",
    "INPUT",
    "KBD",
    "LABEL",
    "MAP",
    "OBJECT",
    "OUTPUT",
    "Q",
    "SAMP",
    "SCRIPT",
    "SELECT",
    "SMALL",
    "SPAN",
    "STRONG",
    "SUB",
    "SUP",
    "TEXTAREA",
    "TIME",
    "TT",
    "VAR"
  ].includes(element.nodeName) : false;
}
function createInline(textOrLineBreak, styles, attrs) {
  if (!(textOrLineBreak instanceof HTMLBRElement) && !(textOrLineBreak instanceof Text)) {
    throw new TypeError("Invalid inline child");
  }
  if (textOrLineBreak instanceof Text && textOrLineBreak.nodeValue.length === 0) {
    console.trace("nodeValue", textOrLineBreak.nodeValue);
    throw new TypeError("Invalid inline child, cannot be an empty text");
  }
  return createElement(TAG$2, {
    attributes: { id: createRandomId(), ...attrs },
    data: { itype: TYPE$2 },
    styles,
    allowedStyles: STYLES$2,
    children: textOrLineBreak
  });
}
function createInlineFrom(inline, textOrLineBreak, styles, attrs) {
  return createInline(
    textOrLineBreak,
    mergeStyles(STYLES$2, inline.style, styles),
    attrs
  );
}
function createEmptyInline(styles) {
  return createInline(createLineBreak(), styles);
}
function setInlineStyles(element, styles) {
  return setStyles(element, STYLES$2, styles);
}
function getInline(node) {
  if (!node) return null;
  if (isInline(node)) return node;
  if (node.nodeType === Node.TEXT_NODE) {
    const inline = node == null ? void 0 : node.parentElement;
    if (!inline) return null;
    if (!isInline(inline)) return null;
    return inline;
  }
  return node.closest(QUERY$1);
}
function isInlineStart(node, offset) {
  const inline = getInline(node);
  if (!inline) return false;
  return isOffsetAtStart(inline, offset);
}
function isInlineEnd(node, offset) {
  const inline = getInline(node);
  if (!inline) return false;
  return isOffsetAtEnd(inline.firstChild, offset);
}
function splitInline(inline, offset) {
  const textNode = inline.firstChild;
  const style = inline.style;
  const newTextNode = textNode.splitText(offset);
  return createInline(newTextNode, style);
}
function getInlinesFrom(startInline) {
  const inlines = [];
  let currentInline = startInline;
  let index = 0;
  while (currentInline) {
    if (index > 0) inlines.push(currentInline);
    currentInline = currentInline.nextElementSibling;
    index++;
  }
  return inlines;
}
function getInlineLength(inline) {
  if (!isInline(inline)) throw new Error("Invalid inline");
  if (isLineBreak(inline.firstChild)) return 0;
  return inline.firstChild.nodeValue.length;
}
const TAG$1 = "DIV";
const TYPE$1 = "root";
const STYLES$1 = [["--vertical-align"]];
function isRoot(node) {
  if (!node) return false;
  if (!isElement(node, TAG$1)) return false;
  if (node.dataset.itype !== TYPE$1) return false;
  return true;
}
function createRoot(paragraphs, styles, attrs) {
  if (!Array.isArray(paragraphs) || !paragraphs.every(isParagraph))
    throw new TypeError("Invalid root children");
  return createElement(TAG$1, {
    attributes: { id: createRandomId(), ...attrs },
    data: { itype: TYPE$1 },
    styles,
    allowedStyles: STYLES$1,
    children: paragraphs
  });
}
function createEmptyRoot(styles) {
  return createRoot([createEmptyParagraph(styles)], styles);
}
function setRootStyles(element, styles) {
  return setStyles(element, STYLES$1, styles);
}
function isTextNode(node) {
  if (!node) throw new TypeError("Invalid text node");
  return node.nodeType === Node.TEXT_NODE || isLineBreak(node);
}
function getTextNodeLength(node) {
  if (!node) throw new TypeError("Invalid text node");
  if (isLineBreak(node)) return 0;
  return node.nodeValue.length;
}
function getClosestTextNode(node) {
  if (isTextNode(node)) return node;
  if (isInline(node)) return node.firstChild;
  if (isParagraph(node)) return node.firstChild.firstChild;
  if (isRoot(node)) return node.firstChild.firstChild.firstChild;
  throw new Error("Cannot find a text node");
}
const TAG = "DIV";
const TYPE = "paragraph";
const QUERY = `[data-itype="${TYPE}"]`;
const STYLES = [
  ["--typography-ref-id"],
  ["--typography-ref-file"],
  ["--font-id"],
  ["--font-variant-id"],
  ["--fills"],
  ["font-variant"],
  ["font-family"],
  ["font-size", "px"],
  ["font-weight"],
  ["font-style"],
  ["line-height"],
  ["letter-spacing", "px"],
  ["text-decoration"],
  ["text-transform"],
  ["text-align"],
  ["direction"]
];
function isLikeParagraph(element) {
  return !isLikeInline(element);
}
function isEmptyParagraph(element) {
  if (!isParagraph(element)) throw new TypeError("Invalid paragraph");
  const inline = element.firstChild;
  if (!isInline(inline)) throw new TypeError("Invalid inline");
  return isLineBreak(inline.firstChild);
}
function isParagraph(node) {
  if (!node) return false;
  if (!isElement(node, TAG)) return false;
  if (node.dataset.itype !== TYPE) return false;
  return true;
}
function createParagraph(inlines, styles, attrs) {
  if (inlines && (!Array.isArray(inlines) || !inlines.every(isInline)))
    throw new TypeError("Invalid paragraph children");
  return createElement(TAG, {
    attributes: { id: createRandomId(), ...attrs },
    data: { itype: TYPE },
    styles,
    allowedStyles: STYLES,
    children: inlines
  });
}
function createEmptyParagraph(styles) {
  return createParagraph([
    createEmptyInline(styles)
  ], styles);
}
function setParagraphStyles(element, styles) {
  return setStyles(element, STYLES, styles);
}
function getParagraph(node) {
  var _a;
  if (!node) return null;
  if (isParagraph(node)) return node;
  if (node.nodeType === Node.TEXT_NODE || isLineBreak(node)) {
    const paragraph = (_a = node == null ? void 0 : node.parentElement) == null ? void 0 : _a.parentElement;
    if (!paragraph) {
      return null;
    }
    if (!isParagraph(paragraph)) {
      return null;
    }
    return paragraph;
  }
  return node.closest(QUERY);
}
function isParagraphStart(node, offset) {
  const paragraph = getParagraph(node);
  if (!paragraph) throw new Error("Can't find the paragraph");
  const inline = getInline(node);
  if (!inline) throw new Error("Can't find the inline");
  return paragraph.firstElementChild === inline && isOffsetAtStart(inline.firstChild, offset);
}
function isParagraphEnd(node, offset) {
  const paragraph = getParagraph(node);
  if (!paragraph) throw new Error("Cannot find the paragraph");
  const inline = getInline(node);
  if (!inline) throw new Error("Cannot find the inline");
  return paragraph.lastElementChild === inline && isOffsetAtEnd(inline.firstChild, offset);
}
function splitParagraph(paragraph, inline, offset) {
  const style = paragraph.style;
  if (isInlineEnd(inline, offset)) {
    const newParagraph2 = createParagraph(getInlinesFrom(inline), style);
    return newParagraph2;
  }
  const newInline = splitInline(inline, offset);
  const newParagraph = createParagraph([newInline], style);
  return newParagraph;
}
function mergeParagraphs(a, b) {
  a.append(...b.children);
  b.remove();
  return a;
}
function mapContentFragmentFromDocument(document2, root, styleDefaults) {
  const nodeIterator = document2.createNodeIterator(root, NodeFilter.SHOW_TEXT);
  const fragment = document2.createDocumentFragment();
  let currentParagraph = null;
  let currentNode = nodeIterator.nextNode();
  while (currentNode) {
    const parentStyle = normalizeStyles(mergeStyleDeclarations(styleDefaults, getComputedStyle(currentNode.parentElement)));
    if (isDisplayBlock(currentNode.parentElement.style) || isDisplayBlock(parentStyle) || isLikeParagraph(currentNode.parentElement)) {
      if (currentParagraph) {
        fragment.appendChild(currentParagraph);
      }
      currentParagraph = createParagraph(void 0, parentStyle);
    } else {
      if (currentParagraph === null) {
        currentParagraph = createParagraph();
      }
    }
    currentParagraph.appendChild(
      createInline(new Text(currentNode.nodeValue), parentStyle)
    );
    currentNode = nodeIterator.nextNode();
  }
  fragment.appendChild(currentParagraph);
  return fragment;
}
function mapContentFragmentFromHTML(html, styleDefaults) {
  const parser = new DOMParser();
  const htmlDocument = parser.parseFromString(html, "text/html");
  return mapContentFragmentFromDocument(
    htmlDocument,
    htmlDocument.documentElement,
    styleDefaults
  );
}
function mapContentFragmentFromString(string, styleDefaults) {
  const lines = string.replace(/\r/g, "").split("\n");
  const fragment = document.createDocumentFragment();
  for (const line of lines) {
    if (line === "") {
      fragment.appendChild(createEmptyParagraph(styleDefaults));
    } else {
      fragment.appendChild(createParagraph([createInline(new Text(line), styleDefaults)], styleDefaults));
    }
  }
  return fragment;
}
function paste(event, editor, selectionController) {
  event.preventDefault();
  let fragment = null;
  if (event.clipboardData.types.includes("text/html")) {
    const html = event.clipboardData.getData("text/html");
    fragment = mapContentFragmentFromHTML(html, selectionController.currentStyle);
  } else if (event.clipboardData.types.includes("text/plain")) {
    const plain = event.clipboardData.getData("text/plain");
    fragment = mapContentFragmentFromString(plain, selectionController.currentStyle);
  }
  if (!fragment) {
    return;
  }
  if (selectionController.isCollapsed) {
    selectionController.insertPaste(fragment);
  } else {
    selectionController.replaceWithPaste(fragment);
  }
}
const clipboard = {
  copy,
  cut,
  paste
};
function insertText(event, editor, selectionController) {
  event.preventDefault();
  if (selectionController.isCollapsed) {
    if (selectionController.isTextFocus) {
      return selectionController.insertText(event.data);
    } else if (selectionController.isLineBreakFocus) {
      return selectionController.replaceLineBreak(event.data);
    }
  } else {
    if (selectionController.isMultiParagraph) {
      return selectionController.replaceParagraphs(event.data);
    } else if (selectionController.isMultiInline) {
      return selectionController.replaceInlines(event.data);
    } else if (selectionController.isTextSame) {
      return selectionController.replaceText(event.data);
    }
  }
}
function insertParagraph(event, editor, selectionController) {
  event.preventDefault();
  if (selectionController.isCollapsed) {
    return selectionController.insertParagraph();
  }
  return selectionController.replaceWithParagraph();
}
function deleteByCut(event, editor, selectionController) {
  event.preventDefault();
  if (selectionController.isCollapsed) {
    throw new Error("This should be impossible");
  }
  return selectionController.removeSelected();
}
function deleteContentBackward(event, editor, selectionController) {
  event.preventDefault();
  if (editor.isEmpty) return;
  if (!selectionController.isCollapsed) {
    return selectionController.removeSelected({ direction: "backward" });
  }
  if (selectionController.isTextFocus && selectionController.focusOffset > 0) {
    return selectionController.removeBackwardText();
  } else if (selectionController.isTextFocus && selectionController.focusAtStart) {
    return selectionController.mergeBackwardParagraph();
  } else if (selectionController.isInlineFocus || selectionController.isLineBreakFocus) {
    return selectionController.removeBackwardParagraph();
  }
}
function deleteContentForward(event, editor, selectionController) {
  event.preventDefault();
  if (editor.isEmpty) return;
  if (!selectionController.isCollapsed) {
    return selectionController.removeSelected({ direction: "forward" });
  }
  if (selectionController.isTextFocus && selectionController.focusOffset >= 0) {
    return selectionController.removeForwardText();
  } else if (selectionController.isTextFocus && selectionController.focusAtEnd) {
    return selectionController.mergeForwardParagraph();
  } else if ((selectionController.isInlineFocus || selectionController.isLineBreakFocus) && editor.numParagraphs > 1) {
    return selectionController.removeForwardParagraph();
  }
}
const commands = {
  insertText,
  insertParagraph,
  deleteByCut,
  deleteContentBackward,
  deleteContentForward
};
class ChangeController extends EventTarget {
  /**
   * Constructor
   *
   * @param {number} [time=500]
   */
  constructor(time = 500) {
    super();
    /**
     * Keeps the timeout id.
     *
     * @type {number}
     */
    __privateAdd(this, _timeout, null);
    /**
     * Keeps the time at which we're going to
     * call the debounced change calls.
     *
     * @type {number}
     */
    __privateAdd(this, _time, 1e3);
    /**
     * Keeps if we have some pending changes or not.
     *
     * @type {boolean}
     */
    __privateAdd(this, _hasPendingChanges, false);
    __privateAdd(this, _onTimeout, () => {
      this.dispatchEvent(new Event("change"));
    });
    if (typeof time === "number" && (!Number.isInteger(time) || time <= 0)) {
      throw new TypeError("Invalid time");
    }
    __privateSet(this, _time, time ?? 500);
  }
  /**
   * Indicates that there are some pending changes.
   *
   * @type {boolean}
   */
  get hasPendingChanges() {
    return __privateGet(this, _hasPendingChanges);
  }
  /**
   * Tells the ChangeController that a change has been made
   * but that you need to delay the notification (and debounce)
   * for sometime.
   */
  notifyDebounced() {
    __privateSet(this, _hasPendingChanges, true);
    clearTimeout(__privateGet(this, _timeout));
    __privateSet(this, _timeout, setTimeout(__privateGet(this, _onTimeout), __privateGet(this, _time)));
  }
  /**
   * Tells the ChangeController that a change should be notified
   * immediately.
   */
  notifyImmediately() {
    clearTimeout(__privateGet(this, _timeout));
    __privateGet(this, _onTimeout).call(this);
  }
  /**
   * Disposes the referenced resources.
   */
  dispose() {
    if (this.hasPendingChanges) {
      this.notifyImmediately();
    }
    clearTimeout(__privateGet(this, _timeout));
  }
}
_timeout = new WeakMap();
_time = new WeakMap();
_hasPendingChanges = new WeakMap();
_onTimeout = new WeakMap();
function tryOffset(offset) {
  if (!Number.isInteger(offset) || offset < 0)
    throw new TypeError("Invalid offset");
}
function tryString(str) {
  if (typeof str !== "string") throw new TypeError("Invalid string");
}
function insertInto(str, offset, text) {
  tryString(str);
  tryOffset(offset);
  tryString(text);
  return str.slice(0, offset) + text + str.slice(offset);
}
function replaceWith(str, startOffset, endOffset, text) {
  tryString(str);
  tryOffset(startOffset);
  tryOffset(endOffset);
  tryString(text);
  return str.slice(0, startOffset) + text + str.slice(endOffset);
}
function removeBackward(str, offset) {
  tryString(str);
  tryOffset(offset);
  if (offset === 0) {
    return str;
  }
  return str.slice(0, offset - 1) + str.slice(offset);
}
function removeForward(str, offset) {
  tryString(str);
  tryOffset(offset);
  return str.slice(0, offset) + str.slice(offset + 1);
}
function removeSlice(str, start2, end) {
  tryString(str);
  tryOffset(start2);
  tryOffset(end);
  return str.slice(0, start2) + str.slice(end);
}
const TextNodeIteratorDirection = {
  FORWARD: 1,
  BACKWARD: 0
};
const _TextNodeIterator = class _TextNodeIterator {
  /**
   * Constructor
   *
   * @param {HTMLElement} rootNode
   */
  constructor(rootNode) {
    /**
     * This is the root text node.
     *
     * @type {HTMLElement}
     */
    __privateAdd(this, _rootNode, null);
    /**
     * This is the current text node.
     *
     * @type {Text|null}
     */
    __privateAdd(this, _currentNode, null);
    if (!(rootNode instanceof HTMLElement)) {
      throw new TypeError("Invalid root node");
    }
    __privateSet(this, _rootNode, rootNode);
    __privateSet(this, _currentNode, _TextNodeIterator.findDown(rootNode, rootNode));
  }
  /**
   * Returns if a specific node is a text node.
   *
   * @param {Node} node
   * @returns {boolean}
   */
  static isTextNode(node) {
    return node.nodeType === Node.TEXT_NODE || node.nodeType === Node.ELEMENT_NODE && node.nodeName === "BR";
  }
  /**
   * Returns if a specific node is a container node.
   *
   * @param {Node} node
   * @returns {boolean}
   */
  static isContainerNode(node) {
    return node.nodeType === Node.ELEMENT_NODE && node.nodeName !== "BR";
  }
  /**
   * Finds a node from an initial node and down the tree.
   *
   * @param {Node} startNode
   * @param {Node} rootNode
   * @param {Set<Node>} skipNodes
   * @param {number} direction
   * @returns {Node}
   */
  static findDown(startNode, rootNode, skipNodes = /* @__PURE__ */ new Set(), direction = TextNodeIteratorDirection.FORWARD) {
    if (startNode === rootNode) {
      return _TextNodeIterator.findDown(
        direction === TextNodeIteratorDirection.FORWARD ? startNode.firstChild : startNode.lastChild,
        rootNode,
        skipNodes,
        direction
      );
    }
    let safeGuard = Date.now();
    let currentNode = startNode;
    while (currentNode) {
      if (Date.now() - safeGuard >= 1e3) {
        throw new Error("Iteration timeout");
      }
      if (skipNodes.has(currentNode)) {
        currentNode = direction === TextNodeIteratorDirection.FORWARD ? currentNode.nextSibling : currentNode.previousSibling;
        continue;
      }
      if (_TextNodeIterator.isTextNode(currentNode)) {
        return currentNode;
      } else if (_TextNodeIterator.isContainerNode(currentNode)) {
        return _TextNodeIterator.findDown(
          direction === TextNodeIteratorDirection.FORWARD ? currentNode.firstChild : currentNode.lastChild,
          rootNode,
          skipNodes,
          direction
        );
      }
      currentNode = direction === TextNodeIteratorDirection.FORWARD ? currentNode.nextSibling : currentNode.previousSibling;
    }
    return null;
  }
  /**
   * Finds a node from an initial node and up the tree.
   *
   * @param {Node} startNode
   * @param {Node} rootNode
   * @param {Set} backTrack
   * @param {number} direction
   * @returns {Node}
   */
  static findUp(startNode, rootNode, backTrack = /* @__PURE__ */ new Set(), direction = TextNodeIteratorDirection.FORWARD) {
    backTrack.add(startNode);
    if (_TextNodeIterator.isTextNode(startNode)) {
      return _TextNodeIterator.findUp(
        startNode.parentNode,
        rootNode,
        backTrack,
        direction
      );
    } else if (_TextNodeIterator.isContainerNode(startNode)) {
      const found = _TextNodeIterator.findDown(
        startNode,
        rootNode,
        backTrack,
        direction
      );
      if (found) {
        return found;
      }
      if (startNode !== rootNode) {
        return _TextNodeIterator.findUp(
          startNode.parentNode,
          rootNode,
          backTrack,
          direction
        );
      }
    }
    return null;
  }
  /**
   * Current node we're into.
   *
   * @type {TextNode|HTMLBRElement}
   */
  get currentNode() {
    return __privateGet(this, _currentNode);
  }
  set currentNode(newCurrentNode) {
    const isContained = (newCurrentNode.compareDocumentPosition(__privateGet(this, _rootNode)) & Node.DOCUMENT_POSITION_CONTAINS) === Node.DOCUMENT_POSITION_CONTAINS;
    if (!(newCurrentNode instanceof Node) || !_TextNodeIterator.isTextNode(newCurrentNode) || !isContained) {
      throw new TypeError("Invalid new current node");
    }
    __privateSet(this, _currentNode, newCurrentNode);
  }
  /**
   * Returns the next Text node or <br> element or null if there are.
   *
   * @returns {Text|HTMLBRElement}
   */
  nextNode() {
    if (!__privateGet(this, _currentNode)) return null;
    const nextNode = _TextNodeIterator.findUp(
      __privateGet(this, _currentNode),
      __privateGet(this, _rootNode),
      /* @__PURE__ */ new Set(),
      TextNodeIteratorDirection.FORWARD
    );
    if (!nextNode) {
      return null;
    }
    __privateSet(this, _currentNode, nextNode);
    return __privateGet(this, _currentNode);
  }
  /**
   * Returns the previous Text node or <br> element or null.
   *
   * @returns {Text|HTMLBRElement}
   */
  previousNode() {
    if (!__privateGet(this, _currentNode)) return null;
    const previousNode = _TextNodeIterator.findUp(
      __privateGet(this, _currentNode),
      __privateGet(this, _rootNode),
      /* @__PURE__ */ new Set(),
      TextNodeIteratorDirection.BACKWARD
    );
    if (!previousNode) {
      return null;
    }
    __privateSet(this, _currentNode, previousNode);
    return __privateGet(this, _currentNode);
  }
};
_rootNode = new WeakMap();
_currentNode = new WeakMap();
let TextNodeIterator = _TextNodeIterator;
class CommandMutations {
  constructor(added, updated, removed) {
    __privateAdd(this, _added, /* @__PURE__ */ new Set());
    __privateAdd(this, _removed, /* @__PURE__ */ new Set());
    __privateAdd(this, _updated, /* @__PURE__ */ new Set());
    if (added && Array.isArray(added)) __privateSet(this, _added, new Set(added));
    if (updated && Array.isArray(updated)) __privateSet(this, _updated, new Set(updated));
    if (removed && Array.isArray(removed)) __privateSet(this, _removed, new Set(removed));
  }
  get added() {
    return __privateGet(this, _added);
  }
  get removed() {
    return __privateGet(this, _removed);
  }
  get updated() {
    return __privateGet(this, _updated);
  }
  clear() {
    __privateGet(this, _added).clear();
    __privateGet(this, _removed).clear();
    __privateGet(this, _updated).clear();
  }
  dispose() {
    __privateGet(this, _added).clear();
    __privateSet(this, _added, null);
    __privateGet(this, _removed).clear();
    __privateSet(this, _removed, null);
    __privateGet(this, _updated).clear();
    __privateSet(this, _updated, null);
  }
  add(node) {
    __privateGet(this, _added).add(node);
    return this;
  }
  remove(node) {
    __privateGet(this, _removed).add(node);
    return this;
  }
  update(node) {
    __privateGet(this, _updated).add(node);
    return this;
  }
}
_added = new WeakMap();
_removed = new WeakMap();
_updated = new WeakMap();
const SelectionDirection = {
  /** The anchorNode is behind the focusNode  */
  FORWARD: 1,
  /** The focusNode and the anchorNode are collapsed */
  NONE: 0,
  /** The focusNode is behind the anchorNode */
  BACKWARD: -1
};
const SAFE_GUARD_TIME = 1e3;
let startTime = Date.now();
function start() {
  startTime = Date.now();
}
function update() {
  if (Date.now - startTime >= SAFE_GUARD_TIME) {
    throw new Error("Safe guard timeout");
  }
}
const SafeGuard = {
  start,
  update
};
class SelectionController extends EventTarget {
  /**
   * Constructor
   *
   * @param {TextEditor} textEditor
   * @param {Selection} selection
   * @param {SelectionControllerOptions} [options]
   */
  constructor(textEditor, selection, options) {
    super();
    __privateAdd(this, _SelectionController_instances);
    /**
     * Reference to the text editor.
     *
     * @type {TextEditor}
     */
    __privateAdd(this, _textEditor, null);
    /**
     * Selection.
     *
     * @type {Selection}
     */
    __privateAdd(this, _selection, null);
    /**
     * Set of ranges (this should always have one)
     *
     * @type {Set<Range>}
     */
    __privateAdd(this, _ranges, /* @__PURE__ */ new Set());
    /**
     * Current range (.rangeAt 0)
     *
     * @type {Range}
     */
    __privateAdd(this, _range, null);
    /**
     * @type {Node}
     */
    __privateAdd(this, _focusNode, null);
    /**
     * @type {number}
     */
    __privateAdd(this, _focusOffset, 0);
    /**
     * @type {Node}
     */
    __privateAdd(this, _anchorNode, null);
    /**
     * @type {number}
     */
    __privateAdd(this, _anchorOffset, 0);
    /**
     * Saved selection.
     *
     * @type {object}
     */
    __privateAdd(this, _savedSelection, null);
    /**
     * TextNodeIterator that allows us to move
     * around the root element but only through
     * <br> and #text nodes.
     *
     * @type {TextNodeIterator}
     */
    __privateAdd(this, _textNodeIterator, null);
    /**
     * CSSStyleDeclaration that we can mutate
     * to handle style changes.
     *
     * @type {CSSStyleDeclaration}
     */
    __privateAdd(this, _currentStyle, null);
    /**
     * Element used to have a custom CSSStyleDeclaration
     * that we can modify to handle style changes when the
     * selection is changed.
     *
     * @type {HTMLDivElement}
     */
    __privateAdd(this, _inertElement, null);
    /**
     * @type {SelectionControllerDebug}
     */
    __privateAdd(this, _debug, null);
    /**
     * Command Mutations.
     *
     * @type {CommandMutations}
     */
    __privateAdd(this, _mutations, new CommandMutations());
    /**
     * Style defaults.
     *
     * @type {Object.<string, *>}
     */
    __privateAdd(this, _styleDefaults, null);
    /**
     * This is called on every `selectionchange` because it is dispatched
     * only by the `document` object.
     *
     * @param {Event} e
     */
    __privateAdd(this, _onSelectionChange, (e) => {
      if (!this.hasFocus) return;
      let focusNodeChanges = false;
      if (__privateGet(this, _focusNode) !== __privateGet(this, _selection).focusNode) {
        __privateSet(this, _focusNode, __privateGet(this, _selection).focusNode);
        focusNodeChanges = true;
      }
      __privateSet(this, _focusOffset, __privateGet(this, _selection).focusOffset);
      if (__privateGet(this, _anchorNode) !== __privateGet(this, _selection).anchorNode) {
        __privateSet(this, _anchorNode, __privateGet(this, _selection).anchorNode);
      }
      __privateSet(this, _anchorOffset, __privateGet(this, _selection).anchorOffset);
      if (__privateGet(this, _selection).rangeCount > 1) {
        for (let index = 0; index < __privateGet(this, _selection).rangeCount; index++) {
          const range = __privateGet(this, _selection).getRangeAt(index);
          if (__privateGet(this, _ranges).has(range)) {
            __privateGet(this, _ranges).delete(range);
            __privateGet(this, _selection).removeRange(range);
          } else {
            __privateGet(this, _ranges).add(range);
            __privateSet(this, _range, range);
          }
        }
      } else if (__privateGet(this, _selection).rangeCount > 0) {
        const range = __privateGet(this, _selection).getRangeAt(0);
        __privateSet(this, _range, range);
        __privateGet(this, _ranges).clear();
        __privateGet(this, _ranges).add(range);
      } else {
        __privateSet(this, _range, null);
        __privateGet(this, _ranges).clear();
      }
      if (focusNodeChanges) {
        __privateMethod(this, _SelectionController_instances, notifyStyleChange_fn).call(this);
      }
      if (__privateGet(this, _debug)) {
        __privateGet(this, _debug).update(this);
      }
    });
    __privateSet(this, _debug, options == null ? void 0 : options.debug);
    __privateSet(this, _styleDefaults, options == null ? void 0 : options.styleDefaults);
    __privateSet(this, _selection, selection);
    __privateSet(this, _textEditor, textEditor);
    __privateSet(this, _textNodeIterator, new TextNodeIterator(__privateGet(this, _textEditor).element));
    __privateMethod(this, _SelectionController_instances, setup_fn).call(this);
  }
  /**
   * Styles of the current inline.
   *
   * @type {CSSStyleDeclaration}
   */
  get currentStyle() {
    return __privateGet(this, _currentStyle);
  }
  /**
   * Saves the current selection and returns the client rects.
   *
   * @returns {boolean}
   */
  saveSelection() {
    __privateSet(this, _savedSelection, {
      isCollapsed: __privateGet(this, _selection).isCollapsed,
      focusNode: __privateGet(this, _selection).focusNode,
      focusOffset: __privateGet(this, _selection).focusOffset,
      anchorNode: __privateGet(this, _selection).anchorNode,
      anchorOffset: __privateGet(this, _selection).anchorOffset,
      range: __privateMethod(this, _SelectionController_instances, getSavedRange_fn).call(this)
    });
    return true;
  }
  /**
   * Restores a saved selection if there's any.
   *
   * @returns {boolean}
   */
  restoreSelection() {
    if (!__privateGet(this, _savedSelection)) return false;
    if (__privateGet(this, _savedSelection).anchorNode && __privateGet(this, _savedSelection).focusNode) {
      if (__privateGet(this, _savedSelection).anchorNode === __privateGet(this, _savedSelection).focusNode) {
        __privateGet(this, _selection).setPosition(__privateGet(this, _savedSelection).focusNode, __privateGet(this, _savedSelection).focusOffset);
      } else {
        __privateGet(this, _selection).setBaseAndExtent(
          __privateGet(this, _savedSelection).anchorNode,
          __privateGet(this, _savedSelection).anchorOffset,
          __privateGet(this, _savedSelection).focusNode,
          __privateGet(this, _savedSelection).focusOffset
        );
      }
    }
    __privateSet(this, _savedSelection, null);
    return true;
  }
  /**
   * Marks the start of a mutation.
   *
   * Clears all the mutations kept in CommandMutations.
   */
  startMutation() {
    __privateGet(this, _mutations).clear();
    if (!__privateGet(this, _focusNode)) return false;
    return true;
  }
  /**
   * Marks the end of a mutation.
   *
   * @returns
   */
  endMutation() {
    return __privateGet(this, _mutations);
  }
  /**
   * Selects all content.
   */
  selectAll() {
    __privateGet(this, _selection).selectAllChildren(__privateGet(this, _textEditor).root);
    return this;
  }
  /**
   * Moves cursor to end.
   */
  cursorToEnd() {
    const range = document.createRange();
    range.selectNodeContents(__privateGet(this, _textEditor).element);
    range.collapse(false);
    __privateGet(this, _selection).removeAllRanges();
    __privateGet(this, _selection).addRange(range);
    return this;
  }
  /**
   * Collapses a selection.
   *
   * @param {Node} node
   * @param {number} offset
   */
  collapse(node, offset) {
    const nodeOffset = node.nodeType === Node.TEXT_NODE && offset >= node.nodeValue.length ? node.nodeValue.length : offset;
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
      throw new Error("Invalid anchorNode");
    }
    if (!focusNode.isConnected) {
      throw new Error("Invalid focusNode");
    }
    if (__privateGet(this, _savedSelection)) {
      __privateGet(this, _savedSelection).isCollapsed = focusNode === anchorNode && anchorOffset === focusOffset;
      __privateGet(this, _savedSelection).focusNode = focusNode;
      __privateGet(this, _savedSelection).focusOffset = focusOffset;
      __privateGet(this, _savedSelection).anchorNode = anchorNode;
      __privateGet(this, _savedSelection).anchorOffset = anchorOffset;
      __privateGet(this, _savedSelection).range.collapsed = __privateGet(this, _savedSelection).isCollapsed;
      const position = focusNode.compareDocumentPosition(anchorNode);
      if (position & Node.DOCUMENT_POSITION_FOLLOWING) {
        __privateGet(this, _savedSelection).range.startContainer = focusNode;
        __privateGet(this, _savedSelection).range.startOffset = focusOffset;
        __privateGet(this, _savedSelection).range.endContainer = anchorNode;
        __privateGet(this, _savedSelection).range.endOffset = anchorOffset;
      } else {
        __privateGet(this, _savedSelection).range.startContainer = anchorNode;
        __privateGet(this, _savedSelection).range.startOffset = anchorOffset;
        __privateGet(this, _savedSelection).range.endContainer = focusNode;
        __privateGet(this, _savedSelection).range.endOffset = focusOffset;
      }
    } else {
      __privateSet(this, _anchorNode, anchorNode);
      __privateSet(this, _anchorOffset, anchorOffset);
      if (anchorNode === focusNode) {
        __privateSet(this, _focusNode, __privateGet(this, _anchorNode));
        __privateSet(this, _focusOffset, __privateGet(this, _anchorOffset));
        __privateGet(this, _selection).setPosition(anchorNode, anchorOffset);
      } else {
        __privateSet(this, _focusNode, focusNode);
        __privateSet(this, _focusOffset, focusOffset);
        __privateGet(this, _selection).setBaseAndExtent(
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
    document.removeEventListener("selectionchange", __privateGet(this, _onSelectionChange));
    __privateSet(this, _textEditor, null);
    __privateGet(this, _ranges).clear();
    __privateSet(this, _ranges, null);
    __privateSet(this, _range, null);
    __privateSet(this, _selection, null);
    __privateSet(this, _focusNode, null);
    __privateSet(this, _anchorNode, null);
    __privateGet(this, _mutations).dispose();
    __privateSet(this, _mutations, null);
  }
  /**
   * Returns the current selection.
   *
   * @type {Selection}
   */
  get selection() {
    return __privateGet(this, _selection);
  }
  /**
   * Returns the current range.
   *
   * @type {Range}
   */
  get range() {
    return __privateGet(this, _range);
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
      return this.startContainer === this.focusNode ? SelectionDirection.BACKWARD : SelectionDirection.FORWARD;
    }
    return this.focusOffset < this.anchorOffset ? SelectionDirection.BACKWARD : SelectionDirection.FORWARD;
  }
  /**
   * Indicates that the editor element has the
   * focus.
   *
   * @type {boolean}
   */
  get hasFocus() {
    return document.activeElement === __privateGet(this, _textEditor).element;
  }
  /**
   * Returns true if the selection is collapsed (caret)
   * or false otherwise.
   *
   * @type {boolean}
   */
  get isCollapsed() {
    if (__privateGet(this, _savedSelection)) {
      return __privateGet(this, _savedSelection).isCollapsed;
    }
    return __privateGet(this, _selection).isCollapsed;
  }
  /**
   * Current or saved anchor node.
   *
   * @type {Node}
   */
  get anchorNode() {
    if (__privateGet(this, _savedSelection)) {
      return __privateGet(this, _savedSelection).anchorNode;
    }
    return __privateGet(this, _anchorNode);
  }
  /**
   * Current or saved anchor offset.
   *
   * @type {number}
   */
  get anchorOffset() {
    if (__privateGet(this, _savedSelection)) {
      return __privateGet(this, _savedSelection).anchorOffset;
    }
    return __privateGet(this, _selection).anchorOffset;
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
    if (__privateGet(this, _savedSelection)) {
      return __privateGet(this, _savedSelection).focusNode;
    }
    if (!__privateGet(this, _focusNode))
      console.trace("focusNode", __privateGet(this, _focusNode));
    return __privateGet(this, _focusNode);
  }
  /**
   * Current or saved focus offset.
   *
   * @type {number}
   */
  get focusOffset() {
    if (__privateGet(this, _savedSelection)) {
      return __privateGet(this, _savedSelection).focusOffset;
    }
    return __privateGet(this, _focusOffset);
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
    var _a, _b, _c;
    if (__privateGet(this, _savedSelection)) {
      return (_b = (_a = __privateGet(this, _savedSelection)) == null ? void 0 : _a.range) == null ? void 0 : _b.startContainer;
    }
    return (_c = __privateGet(this, _range)) == null ? void 0 : _c.startContainer;
  }
  /**
   * `startOffset` of the current range.
   *
   * @type {number|null}
   */
  get startOffset() {
    var _a, _b, _c;
    if (__privateGet(this, _savedSelection)) {
      return (_b = (_a = __privateGet(this, _savedSelection)) == null ? void 0 : _a.range) == null ? void 0 : _b.startOffset;
    }
    return (_c = __privateGet(this, _range)) == null ? void 0 : _c.startOffset;
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
    var _a, _b, _c;
    if (__privateGet(this, _savedSelection)) {
      return (_b = (_a = __privateGet(this, _savedSelection)) == null ? void 0 : _a.range) == null ? void 0 : _b.endContainer;
    }
    return (_c = __privateGet(this, _range)) == null ? void 0 : _c.endContainer;
  }
  /**
   * `endOffset` of the current range
   *
   * @type {HTMLElement|null}
   */
  get endOffset() {
    var _a, _b, _c;
    if (__privateGet(this, _savedSelection)) {
      return (_b = (_a = __privateGet(this, _savedSelection)) == null ? void 0 : _a.range) == null ? void 0 : _b.endOffset;
    }
    return (_c = __privateGet(this, _range)) == null ? void 0 : _c.endOffset;
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
    return this.isTextFocus === this.isTextAnchor && this.focusNode === this.anchorNode;
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
    return isLineBreak(this.focusNode) || isInline(this.focusNode) && isLineBreak(this.focusNode.firstChild);
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
    fragment.children.length;
    if (this.isParagraphStart) {
      this.focusParagraph.before(fragment);
    } else if (this.isParagraphEnd) {
      this.focusParagraph.after(fragment);
    } else {
      const newParagraph = splitParagraph(
        this.focusParagraph,
        this.focusInline,
        this.focusOffset
      );
      this.focusParagraph.after(fragment, newParagraph);
    }
  }
  /**
   * Replaces data with pasted fragment
   *
   * @param {DocumentFragment} fragment
   */
  replaceWithPaste(fragment) {
    fragment.children.length;
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
    __privateGet(this, _textNodeIterator).currentNode = this.focusNode;
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
    const nextTextNode = __privateGet(this, _textNodeIterator).nextNode();
    if (this.focusNode.nodeValue === "") {
      this.focusNode.remove();
    }
    if (paragraph.childNodes.length === 1 && inline.childNodes.length === 0) {
      const lineBreak = createLineBreak();
      inline.appendChild(lineBreak);
      return this.collapse(lineBreak, 0);
    } else if (paragraph.childNodes.length > 1 && inline.childNodes.length === 0) {
      inline.remove();
      return this.collapse(nextTextNode, 0);
    }
    return this.collapse(this.focusNode, this.focusOffset);
  }
  /**
   * Removes text backward from the current caret position.
   */
  removeBackwardText() {
    __privateGet(this, _textNodeIterator).currentNode = this.focusNode;
    const removedData = removeBackward(
      this.focusNode.nodeValue,
      this.focusOffset
    );
    if (this.focusNode.nodeValue !== removedData) {
      this.focusNode.nodeValue = removedData;
    }
    if (this.focusOffset - 1 > 0) {
      return this.collapse(this.focusNode, this.focusOffset - 1);
    }
    const paragraph = this.focusParagraph;
    if (!paragraph) throw new Error("Cannot find paragraph");
    const inline = this.focusInline;
    if (!inline) throw new Error("Cannot find inline");
    const previousTextNode = __privateGet(this, _textNodeIterator).previousNode();
    if (this.focusNode.nodeValue === "") {
      this.focusNode.remove();
    }
    if (paragraph.children.length === 1 && inline.childNodes.length === 0) {
      const lineBreak = createLineBreak();
      inline.appendChild(lineBreak);
      return this.collapse(lineBreak, 0);
    } else if (paragraph.children.length > 1 && inline.childNodes.length === 0) {
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
    __privateGet(this, _mutations).update(this.focusInline);
    return this.collapse(this.focusNode, this.focusOffset + newText.length);
  }
  /**
   * Replaces currently selected text.
   *
   * @param {string} newText
   */
  replaceText(newText) {
    const startOffset = Math.min(this.anchorOffset, this.focusOffset);
    const endOffset = Math.max(this.anchorOffset, this.focusOffset);
    this.focusNode.nodeValue = replaceWith(
      this.focusNode.nodeValue,
      startOffset,
      endOffset,
      newText
    );
    __privateGet(this, _mutations).update(this.focusInline);
    return this.collapse(this.focusNode, startOffset + newText.length);
  }
  /**
   * Replaces the selected inlines with new text.
   *
   * @param {string} newText
   */
  replaceInlines(newText) {
    const currentParagraph = this.focusParagraph;
    if (this.startInline === currentParagraph.firstChild && this.startOffset === 0 && this.endInline === currentParagraph.lastChild && this.endOffset === currentParagraph.lastChild.textContent.length) {
      const newTextNode = new Text(newText);
      currentParagraph.replaceChildren(
        createInline(newTextNode, this.anchorInline.style)
      );
      return this.collapse(newTextNode, newTextNode.nodeValue.length);
    }
    this.removeSelected();
    this.focusNode.nodeValue = insertInto(
      this.focusNode.nodeValue,
      this.focusOffset,
      newText
    );
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
    this.focusNode.nodeValue = insertInto(
      this.focusNode.nodeValue,
      this.focusOffset,
      newText
    );
    for (const child of currentParagraph.children) {
      if (child.textContent === "") {
        child.remove();
      }
    }
  }
  /**
   * Inserts a new paragraph after the current paragraph.
   */
  insertParagraphAfter() {
    const currentParagraph = this.focusParagraph;
    const newParagraph = createEmptyParagraph(__privateGet(this, _currentStyle));
    currentParagraph.after(newParagraph);
    __privateGet(this, _mutations).update(currentParagraph);
    __privateGet(this, _mutations).add(newParagraph);
    return this.collapse(newParagraph.firstChild.firstChild, 0);
  }
  /**
   * Inserts a new paragraph before the current paragraph.
   */
  insertParagraphBefore() {
    const currentParagraph = this.focusParagraph;
    const newParagraph = createEmptyParagraph(__privateGet(this, _currentStyle));
    currentParagraph.before(newParagraph);
    __privateGet(this, _mutations).update(currentParagraph);
    __privateGet(this, _mutations).add(newParagraph);
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
      __privateGet(this, _focusOffset)
    );
    this.focusParagraph.after(newParagraph);
    __privateGet(this, _mutations).update(currentParagraph);
    __privateGet(this, _mutations).add(newParagraph);
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
    __privateGet(this, _mutations).update(currentParagraph);
    __privateGet(this, _mutations).add(newParagraph);
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
    const previousInline = previousParagraph.children.length > 1 ? previousParagraph.lastElementChild : previousParagraph.firstChild;
    const previousOffset = isLineBreak(previousInline.firstChild) ? 0 : previousInline.firstChild.nodeValue.length;
    __privateGet(this, _mutations).remove(paragraphToBeRemoved);
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
    __privateGet(this, _mutations).remove(currentParagraph);
    __privateGet(this, _mutations).update(previousParagraph);
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
    __privateGet(this, _mutations).update(currentParagraph);
    __privateGet(this, _mutations).remove(nextParagraph);
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
    __privateGet(this, _mutations).remove(paragraphToBeRemoved);
    return this.collapse(nextInline.firstChild, nextOffset);
  }
  /**
   * Cleans up all the affected paragraphs.
   *
   * @param {Set<HTMLDivElement>} affectedParagraphs
   * @param {Set<HTMLSpanElement>} affectedInlines
   */
  cleanUp(affectedParagraphs, affectedInlines) {
    for (const inline of affectedInlines) {
      if (inline.textContent === "") {
        inline.remove();
        __privateGet(this, _mutations).remove(inline);
      }
    }
    for (const paragraph of affectedParagraphs) {
      if (paragraph.children.length === 0) {
        paragraph.remove();
        __privateGet(this, _mutations).remove(paragraph);
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
    const affectedInlines = /* @__PURE__ */ new Set();
    const affectedParagraphs = /* @__PURE__ */ new Set();
    const startNode = getClosestTextNode(__privateGet(this, _range).startContainer);
    const endNode = getClosestTextNode(__privateGet(this, _range).endContainer);
    const startOffset = __privateGet(this, _range).startOffset;
    const endOffset = __privateGet(this, _range).endOffset;
    if (startNode === endNode) {
      __privateGet(this, _textNodeIterator).currentNode = startNode;
      __privateGet(this, _textNodeIterator).previousNode();
      __privateGet(this, _textNodeIterator).currentNode = startNode;
      __privateGet(this, _textNodeIterator).nextNode();
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
    __privateGet(this, _textNodeIterator).currentNode = startNode;
    const startInline = getInline(startNode);
    const startParagraph = getParagraph(startNode);
    const endInline = getInline(endNode);
    const endParagraph = getParagraph(endNode);
    SafeGuard.start();
    do {
      SafeGuard.update();
      const currentNode = __privateGet(this, _textNodeIterator).currentNode;
      const inline = getInline(__privateGet(this, _textNodeIterator).currentNode);
      const paragraph = getParagraph(__privateGet(this, _textNodeIterator).currentNode);
      let shouldRemoveNodeCompletely = false;
      if (__privateGet(this, _textNodeIterator).currentNode === startNode) {
        if (startOffset === 0) {
          shouldRemoveNodeCompletely = true;
        } else {
          currentNode.nodeValue = currentNode.nodeValue.slice(0, startOffset);
        }
      } else if (__privateGet(this, _textNodeIterator).currentNode === endNode) {
        if (isLineBreak(endNode) || isTextNode(endNode) && endOffset === endNode.nodeValue.length) {
          shouldRemoveNodeCompletely = true;
        } else {
          currentNode.nodeValue = currentNode.nodeValue.slice(endOffset);
        }
      } else {
        shouldRemoveNodeCompletely = true;
      }
      __privateGet(this, _textNodeIterator).nextNode();
      if (shouldRemoveNodeCompletely) {
        currentNode.remove();
        if (currentNode === startNode) {
          continue;
        }
        if (currentNode === endNode) {
          break;
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
    } while (__privateGet(this, _textNodeIterator).currentNode);
    if (startParagraph !== endParagraph) {
      const mergedParagraph = mergeParagraphs(startParagraph, endParagraph);
      if (mergedParagraph.children.length === 0) {
        const newEmptyInline = createEmptyInline(__privateGet(this, _currentStyle));
        mergedParagraph.appendChild(newEmptyInline);
        return this.collapse(newEmptyInline.firstChild, 0);
      }
    }
    if (startInline.childNodes.length === 0 && endInline.childNodes.length > 0) {
      startInline.remove();
      return this.collapse(endNode, 0);
    } else if (startInline.childNodes.length > 0 && endInline.childNodes.length === 0) {
      endInline.remove();
      return this.collapse(startNode, startOffset);
    } else if (startInline.childNodes.length === 0 && endInline.childNodes.length === 0) {
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
      const newEmptyInline = createEmptyInline(__privateGet(this, _currentStyle));
      startParagraph.appendChild(newEmptyInline);
      return this.collapse(newEmptyInline.firstChild, 0);
    }
    return this.collapse(startNode, startOffset);
  }
  /**
   * Applies styles to selection
   *
   * @param {Object.<string, *>} newStyles
   * @returns {void}
   */
  applyStyles(newStyles) {
    return __privateMethod(this, _SelectionController_instances, applyStylesTo_fn).call(this, this.startContainer, this.startOffset, this.endContainer, this.endOffset, newStyles);
  }
}
_textEditor = new WeakMap();
_selection = new WeakMap();
_ranges = new WeakMap();
_range = new WeakMap();
_focusNode = new WeakMap();
_focusOffset = new WeakMap();
_anchorNode = new WeakMap();
_anchorOffset = new WeakMap();
_savedSelection = new WeakMap();
_textNodeIterator = new WeakMap();
_currentStyle = new WeakMap();
_inertElement = new WeakMap();
_debug = new WeakMap();
_mutations = new WeakMap();
_styleDefaults = new WeakMap();
_SelectionController_instances = new WeakSet();
/**
 * Applies the default styles to the currentStyle
 * CSSStyleDeclaration.
 */
applyDefaultStylesToCurrentStyle_fn = function() {
  if (__privateGet(this, _styleDefaults)) {
    for (const [name, value] of Object.entries(__privateGet(this, _styleDefaults))) {
      __privateGet(this, _currentStyle).setProperty(
        name,
        value + (name === "font-size" ? "px" : "")
      );
    }
  }
};
/**
 * Applies some styles to the currentStyle
 * CSSStyleDeclaration
 *
 * @param {HTMLElement} element
 */
applyStylesToCurrentStyle_fn = function(element) {
  for (let index = 0; index < element.style.length; index++) {
    const styleName = element.style.item(index);
    const styleValue = element.style.getPropertyValue(styleName);
    __privateGet(this, _currentStyle).setProperty(styleName, styleValue);
  }
};
/**
 * Updates current styles based on the currently selected inline.
 *
 * @param {HTMLSpanElement} inline
 * @returns {SelectionController}
 */
updateCurrentStyle_fn = function(inline) {
  __privateMethod(this, _SelectionController_instances, applyDefaultStylesToCurrentStyle_fn).call(this);
  const root = inline.parentElement.parentElement;
  __privateMethod(this, _SelectionController_instances, applyStylesToCurrentStyle_fn).call(this, root);
  const paragraph = inline.parentElement;
  __privateMethod(this, _SelectionController_instances, applyStylesToCurrentStyle_fn).call(this, paragraph);
  __privateMethod(this, _SelectionController_instances, applyStylesToCurrentStyle_fn).call(this, inline);
  return this;
};
_onSelectionChange = new WeakMap();
/**
 * Notifies that the styles have changed.
 */
notifyStyleChange_fn = function() {
  const inline = this.focusInline;
  if (inline) {
    __privateMethod(this, _SelectionController_instances, updateCurrentStyle_fn).call(this, inline);
    this.dispatchEvent(
      new CustomEvent("stylechange", {
        detail: __privateGet(this, _currentStyle)
      })
    );
  }
};
/**
 * Setups
 */
setup_fn = function() {
  __privateSet(this, _inertElement, document.createElement("div"));
  __privateSet(this, _currentStyle, __privateGet(this, _inertElement).style);
  __privateMethod(this, _SelectionController_instances, applyDefaultStylesToCurrentStyle_fn).call(this);
  if (__privateGet(this, _selection).rangeCount > 0) {
    const range = __privateGet(this, _selection).getRangeAt(0);
    __privateSet(this, _range, range);
    __privateGet(this, _ranges).add(range);
  }
  if (__privateGet(this, _selection).rangeCount > 1) {
    for (let index = 1; index < __privateGet(this, _selection).rangeCount; index++) {
      __privateGet(this, _selection).removeRange(index);
    }
  }
  document.addEventListener("selectionchange", __privateGet(this, _onSelectionChange));
};
/**
 * Returns a Range-like object.
 *
 * @returns {RangeLike}
 */
getSavedRange_fn = function() {
  if (!__privateGet(this, _range)) {
    return {
      collapsed: true,
      commonAncestorContainer: null,
      startContainer: null,
      startOffset: 0,
      endContainer: null,
      endOffset: 0
    };
  }
  return {
    collapsed: __privateGet(this, _range).collapsed,
    commonAncestorContainer: __privateGet(this, _range).commonAncestorContainer,
    startContainer: __privateGet(this, _range).startContainer,
    startOffset: __privateGet(this, _range).startOffset,
    endContainer: __privateGet(this, _range).endContainer,
    endOffset: __privateGet(this, _range).endOffset
  };
};
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
applyStylesTo_fn = function(startNode, startOffset, endNode, endOffset, newStyles) {
  const root = __privateGet(this, _textEditor).root;
  setRootStyles(root, newStyles);
  if (startNode === endNode && startNode.nodeType === Node.TEXT_NODE) {
    if (startOffset === 0 && endOffset === endNode.nodeValue.length) {
      const paragraph = this.startParagraph;
      const inline = this.startInline;
      setParagraphStyles(paragraph, newStyles);
      setInlineStyles(inline, newStyles);
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
      this.setSelection(midText, 0, midText, midText.nodeValue.length);
    } else {
      const paragraph = this.startParagraph;
      setParagraphStyles(paragraph, newStyles);
    }
    return __privateMethod(this, _SelectionController_instances, notifyStyleChange_fn).call(this);
  } else if (startNode !== endNode) {
    SafeGuard.start();
    const expectedEndNode = getClosestTextNode(endNode);
    __privateGet(this, _textNodeIterator).currentNode = getClosestTextNode(startNode);
    do {
      SafeGuard.update();
      const paragraph = getParagraph(__privateGet(this, _textNodeIterator).currentNode);
      setParagraphStyles(paragraph, newStyles);
      const inline = getInline(__privateGet(this, _textNodeIterator).currentNode);
      if (__privateGet(this, _textNodeIterator).currentNode === startNode && startOffset > 0) {
        const newInline = splitInline(inline, startOffset);
        setInlineStyles(newInline, newStyles);
        inline.after(newInline);
      } else if (__privateGet(this, _textNodeIterator).currentNode === startNode && startOffset === 0 || __privateGet(this, _textNodeIterator).currentNode !== startNode && __privateGet(this, _textNodeIterator).currentNode !== endNode || __privateGet(this, _textNodeIterator).currentNode === endNode && endOffset === endNode.nodeValue.length) {
        setInlineStyles(inline, newStyles);
      } else if (__privateGet(this, _textNodeIterator).currentNode === endNode && endOffset < endNode.nodeValue.length) {
        const newInline = splitInline(inline, endOffset);
        setInlineStyles(inline, newStyles);
        inline.after(newInline);
      }
      if (__privateGet(this, _textNodeIterator).currentNode === expectedEndNode) return;
      __privateGet(this, _textNodeIterator).nextNode();
    } while (__privateGet(this, _textNodeIterator).currentNode);
  }
  return __privateMethod(this, _SelectionController_instances, notifyStyleChange_fn).call(this);
};
function createSelectionImposterFromClientRects(referenceRect, clientRects) {
  const fragment = document.createDocumentFragment();
  for (const rect of clientRects) {
    const rectElement = document.createElement("div");
    rectElement.className = "selection-imposter-rect";
    rectElement.style.left = `${rect.x - referenceRect.x}px`;
    rectElement.style.top = `${rect.y - referenceRect.y}px`;
    rectElement.style.width = `${rect.width}px`;
    rectElement.style.height = `${rect.height}px`;
    fragment.appendChild(rectElement);
  }
  return fragment;
}
function addEventListeners(target, object, options) {
  Object.entries(object).forEach(
    ([type, listener]) => target.addEventListener(type, listener, options)
  );
}
function removeEventListeners(target, object) {
  Object.entries(object).forEach(
    ([type, listener]) => target.removeEventListener(type, listener)
  );
}
const LayoutType = {
  FULL: "full",
  PARTIAL: "partial"
};
class TextEditor extends EventTarget {
  /**
   * Constructor.
   *
   * @param {HTMLElement} element
   */
  constructor(element, options) {
    super();
    __privateAdd(this, _TextEditor_instances);
    /**
     * Element content editable to be used by the TextEditor
     *
     * @type {HTMLElement}
     */
    __privateAdd(this, _element, null);
    /**
     * Map/Dictionary of events.
     *
     * @type {Object.<string, Function>}
     */
    __privateAdd(this, _events, null);
    /**
     * Root element that will contain the content.
     *
     * @type {HTMLElement}
     */
    __privateAdd(this, _root, null);
    /**
     * Change controller controls when we should notify changes.
     *
     * @type {ChangeController}
     */
    __privateAdd(this, _changeController, null);
    /**
     * Selection controller controls the current/saved selection.
     *
     * @type {SelectionController}
     */
    __privateAdd(this, _selectionController, null);
    /**
     * Selection imposter keeps selection elements.
     *
     * @type {HTMLElement}
     */
    __privateAdd(this, _selectionImposterElement, null);
    /**
     * Style defaults.
     *
     * @type {Object.<string, *>}
     */
    __privateAdd(this, _styleDefaults2, null);
    /**
     * Dispatchs a `change` event.
     *
     * @param {CustomEvent} e
     * @returns {void}
     */
    __privateAdd(this, _onChange, (e) => this.dispatchEvent(new e.constructor(e.type, e)));
    /**
     * Dispatchs a `stylechange` event.
     *
     * @param {CustomEvent} e
     * @returns {void}
     */
    __privateAdd(this, _onStyleChange, (e) => {
      if (__privateGet(this, _selectionImposterElement).children.length > 0) {
        __privateMethod(this, _TextEditor_instances, createSelectionImposter_fn).call(this);
      }
      this.dispatchEvent(new e.constructor(e.type, e));
    });
    /**
     * On blur we create a new FakeSelection if there's any.
     *
     * @param {FocusEvent} e
     */
    __privateAdd(this, _onBlur, (e) => {
      __privateGet(this, _changeController).notifyImmediately();
      __privateGet(this, _selectionController).saveSelection();
      __privateMethod(this, _TextEditor_instances, createSelectionImposter_fn).call(this);
      this.dispatchEvent(new FocusEvent(e.type, e));
    });
    /**
     * On focus we should restore the FakeSelection from the current
     * selection.
     *
     * @param {FocusEvent} e
     */
    __privateAdd(this, _onFocus, (e) => {
      __privateGet(this, _selectionController).restoreSelection();
      if (__privateGet(this, _selectionImposterElement)) {
        __privateGet(this, _selectionImposterElement).replaceChildren();
      }
      this.dispatchEvent(new FocusEvent(e.type, e));
    });
    /**
     * Event called when the user pastes some text into the
     * editor.
     *
     * @param {ClipboardEvent} e
     */
    __privateAdd(this, _onPaste, (e) => clipboard.paste(e, this, __privateGet(this, _selectionController)));
    /**
     * Event called when the user cuts some text from the
     * editor.
     *
     * @param {ClipboardEvent} e
     */
    __privateAdd(this, _onCut, (e) => clipboard.cut(e, this, __privateGet(this, _selectionController)));
    /**
     * Event called when the user copies some text from the
     * editor.
     *
     * @param {ClipboardEvent} e
     */
    __privateAdd(this, _onCopy, (e) => clipboard.copy(e, this, __privateGet(this, _selectionController)));
    /**
     * Event called before the DOM is modified.
     *
     * @param {InputEvent} e
     */
    __privateAdd(this, _onBeforeInput, (e) => {
      if (e.inputType === "historyUndo" || e.inputType === "historyRedo") {
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
        if (!__privateGet(this, _selectionController).startMutation())
          return;
        command(e, this, __privateGet(this, _selectionController));
        const mutations = __privateGet(this, _selectionController).endMutation();
        __privateMethod(this, _TextEditor_instances, notifyLayout_fn).call(this, LayoutType.FULL, mutations);
      }
    });
    /**
     * Event called after the DOM is modified.
     *
     * @param {InputEvent} e
     */
    __privateAdd(this, _onInput, (e) => {
      if (e.inputType === "historyUndo" || e.inputType === "historyRedo") {
        return;
      }
      if (e.inputType === "insertCompositionText") {
        __privateMethod(this, _TextEditor_instances, notifyLayout_fn).call(this, LayoutType.FULL, null);
      }
    });
    if (!(element instanceof HTMLElement))
      throw new TypeError("Invalid text editor element");
    __privateSet(this, _element, element);
    __privateSet(this, _selectionImposterElement, options == null ? void 0 : options.selectionImposterElement);
    __privateSet(this, _events, {
      blur: __privateGet(this, _onBlur),
      focus: __privateGet(this, _onFocus),
      paste: __privateGet(this, _onPaste),
      cut: __privateGet(this, _onCut),
      copy: __privateGet(this, _onCopy),
      beforeinput: __privateGet(this, _onBeforeInput),
      input: __privateGet(this, _onInput)
    });
    __privateSet(this, _styleDefaults2, options == null ? void 0 : options.styleDefaults);
    __privateMethod(this, _TextEditor_instances, setup_fn2).call(this, options);
  }
  /**
   * Root element that contains all the paragraphs.
   *
   * @type {HTMLDivElement}
   */
  get root() {
    return __privateGet(this, _root);
  }
  set root(newRoot) {
    const previousRoot = __privateGet(this, _root);
    __privateSet(this, _root, newRoot);
    previousRoot.replaceWith(newRoot);
  }
  /**
   * Element that contains the root and that has the
   * contenteditable attribute.
   *
   * @type {HTMLElement}
   */
  get element() {
    return __privateGet(this, _element);
  }
  /**
   * Returns true if the content is in an empty state.
   *
   * @type {boolean}
   */
  get isEmpty() {
    return __privateGet(this, _root).children.length === 1 && __privateGet(this, _root).firstElementChild.children.length === 1 && isLineBreak(__privateGet(this, _root).firstElementChild.firstElementChild.firstChild);
  }
  /**
   * Indicates the amount of paragraphs in the current content.
   *
   * @type {number}
   */
  get numParagraphs() {
    return __privateGet(this, _root).children.length;
  }
  /**
   * CSS Style declaration for the current inline. From here we
   * can infer root, paragraph and inline declarations.
   *
   * @type {CSSStyleDeclaration}
   */
  get currentStyle() {
    return __privateGet(this, _selectionController).currentStyle;
  }
  /**
   * Focus the element
   */
  focus() {
    return __privateGet(this, _element).focus();
  }
  /**
   * Blurs the element
   */
  blur() {
    return __privateGet(this, _element).blur();
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
    __privateGet(this, _selectionController).startMutation();
    __privateGet(this, _selectionController).applyStyles(styles);
    const mutations = __privateGet(this, _selectionController).endMutation();
    __privateMethod(this, _TextEditor_instances, notifyLayout_fn).call(this, LayoutType.FULL, mutations);
    __privateGet(this, _changeController).notifyImmediately();
    return this;
  }
  /**
   * Selects all content.
   */
  selectAll() {
    __privateGet(this, _selectionController).selectAll();
    return this;
  }
  /**
   * Moves cursor to end.
   *
   * @returns
   */
  cursorToEnd() {
    __privateGet(this, _selectionController).cursorToEnd();
    return this;
  }
  /**
   * Disposes everything.
   */
  dispose() {
    __privateGet(this, _changeController).removeEventListener("change", __privateGet(this, _onChange));
    __privateGet(this, _changeController).dispose();
    __privateSet(this, _changeController, null);
    __privateGet(this, _selectionController).removeEventListener(
      "stylechange",
      __privateGet(this, _onStyleChange)
    );
    __privateGet(this, _selectionController).dispose();
    __privateSet(this, _selectionController, null);
    removeEventListeners(__privateGet(this, _element), __privateGet(this, _events));
    __privateSet(this, _element, null);
    __privateSet(this, _root, null);
  }
}
_element = new WeakMap();
_events = new WeakMap();
_root = new WeakMap();
_changeController = new WeakMap();
_selectionController = new WeakMap();
_selectionImposterElement = new WeakMap();
_styleDefaults2 = new WeakMap();
_TextEditor_instances = new WeakSet();
/**
 * Setups editor properties.
 */
setupElementProperties_fn = function() {
  if (!__privateGet(this, _element).isContentEditable) {
    __privateGet(this, _element).contentEditable = "true";
    if (!__privateGet(this, _element).isContentEditable) {
      __privateGet(this, _element).setAttribute("contenteditable", "true");
    }
  }
  if (__privateGet(this, _element).spellcheck) __privateGet(this, _element).spellcheck = false;
  if (__privateGet(this, _element).autocapitalize) __privateGet(this, _element).autocapitalize = false;
  if (!__privateGet(this, _element).autofocus) __privateGet(this, _element).autofocus = true;
  if (!__privateGet(this, _element).role || __privateGet(this, _element).role !== "textbox")
    __privateGet(this, _element).role = "textbox";
  if (__privateGet(this, _element).ariaAutoComplete) __privateGet(this, _element).ariaAutoComplete = false;
  if (!__privateGet(this, _element).ariaMultiLine) __privateGet(this, _element).ariaMultiLine = true;
  __privateGet(this, _element).dataset.itype = "editor";
};
/**
 * Setups the root element.
 */
setupRoot_fn = function() {
  __privateSet(this, _root, createEmptyRoot(__privateGet(this, _styleDefaults2)));
  __privateGet(this, _element).appendChild(__privateGet(this, _root));
};
_onChange = new WeakMap();
_onStyleChange = new WeakMap();
/**
 * Setups the elements, the properties and the
 * initial content.
 */
setup_fn2 = function(options) {
  __privateMethod(this, _TextEditor_instances, setupElementProperties_fn).call(this);
  __privateMethod(this, _TextEditor_instances, setupRoot_fn).call(this);
  __privateSet(this, _changeController, new ChangeController(this));
  __privateGet(this, _changeController).addEventListener("change", __privateGet(this, _onChange));
  __privateSet(this, _selectionController, new SelectionController(
    this,
    document.getSelection(),
    options
  ));
  __privateGet(this, _selectionController).addEventListener(
    "stylechange",
    __privateGet(this, _onStyleChange)
  );
  addEventListeners(__privateGet(this, _element), __privateGet(this, _events), {
    capture: true
  });
};
/**
 * Creates the selection imposter.
 */
createSelectionImposter_fn = function() {
  var _a;
  if (__privateGet(this, _selectionImposterElement) && !__privateGet(this, _selectionController).isCollapsed) {
    const rects = (_a = __privateGet(this, _selectionController).range) == null ? void 0 : _a.getClientRects();
    if (rects) {
      const rect = __privateGet(this, _selectionImposterElement).getBoundingClientRect();
      __privateGet(this, _selectionImposterElement).replaceChildren(
        createSelectionImposterFromClientRects(rect, rects)
      );
    }
  }
};
_onBlur = new WeakMap();
_onFocus = new WeakMap();
_onPaste = new WeakMap();
_onCut = new WeakMap();
_onCopy = new WeakMap();
_onBeforeInput = new WeakMap();
_onInput = new WeakMap();
/**
 * Notifies that the edited texts needs layout.
 *
 * @param {'full'|'partial'} type
 * @param {CommandMutations} mutations
 */
notifyLayout_fn = function(type = LayoutType.FULL, mutations) {
  this.dispatchEvent(
    new CustomEvent("needslayout", {
      detail: {
        type,
        mutations
      }
    })
  );
};
function isEditor(instance) {
  return instance instanceof TextEditor;
}
function getRoot(instance) {
  if (isEditor(instance)) {
    return instance.root;
  } else {
    return null;
  }
}
function setRoot(instance, root) {
  if (isEditor(instance)) {
    instance.root = root;
  }
  return instance;
}
function create(element, options) {
  return new TextEditor(element, { ...options });
}
function getCurrentStyle(instance) {
  if (isEditor(instance)) {
    return instance.currentStyle;
  }
}
function applyStylesToSelection(instance, styles) {
  if (isEditor(instance)) {
    return instance.applyStylesToSelection(styles);
  }
}
function dispose(instance) {
  if (isEditor(instance)) {
    instance.dispose();
  }
}
export {
  TextEditor,
  applyStylesToSelection,
  create,
  TextEditor as default,
  dispose,
  getCurrentStyle,
  getRoot,
  isEditor,
  setRoot
};
//# sourceMappingURL=TextEditor.js.map
