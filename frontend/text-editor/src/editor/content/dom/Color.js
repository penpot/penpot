/**
 * Canvas used to retrieve colors as CSS hexadecimals.
 *
 * @type {OffscreenCanvas|HTMLCanvasElement}
 */
let canvas = null; // createCanvas(1, 1);

/**
 * Context used to retrieve colors as CSS hexadecimals.
 *
 * @type {CanvasRenderingContext2D}
 */
let context = null; // canvas.getContext("2d");

/**
 * Returns the canvas context.
 *
 * @returns {CanvasRenderingContext2D}
 */
function getContext() {
  if (!canvas) {
    canvas = createCanvas(1, 1);
  }
  if (!context) {
    context = canvas.getContext("2d");
  }
  return context
}

/**
 * Creates a new canvas element.
 *
 * @param {number} width
 * @param {number} height
 * @returns {OffscreenCanvas|HTMLCanvasElement}
 */
function createCanvas(width, height) {
  if ("OffscreenCanvas" in globalThis) {
    return new OffscreenCanvas(width, height);
  }
  return document.createElement("canvas");
}

/**
 * Returns a byte representation as an hex.
 *
 * @param {number} byte
 * @returns {string}
 */
export function getByteAsHex(byte) {
  return byte.toString(16).padStart(2, "0");
}

/**
 * Returns a color definition from a fillStyle color.
 *
 * @param {string} fillStyle
 * @returns {[string, number]}
 */
export function getColor(fillStyle) {
  const context = getContext();
  context.fillStyle = fillStyle;
  context.fillRect(0, 0, 1, 1);
  const imageData = context.getImageData(0, 0, 1, 1);
  const [r, g, b, a] = imageData.data;
  return [`#${getByteAsHex(r)}${getByteAsHex(g)}${getByteAsHex(b)}`, a / 255.0];
}

/**
 * Returns a fill from a fillStyle color.
 *
 * @param {string} fillStyle
 * @returns {string}
 */
export function getFills(fillStyle) {
  const [color, opacity] = getColor(fillStyle);
  return `[["^ ","~:fill-color","${color}","~:fill-opacity",${opacity}]]`;
}
