/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { getFills } from "./Color.js";

const DEFAULT_FONT_SIZE = "16px";
const DEFAULT_FONT_SIZE_VALUE = parseFloat(DEFAULT_FONT_SIZE);
const DEFAULT_LINE_HEIGHT = "1.2";
const DEFAULT_FONT_WEIGHT = "400";

/** Sanitizes font-family values to be quoted, so it handles multi-word font names
 * with numbers like "Font Awesome 7 Free"
 *
 * @param {string} value
 */
export function sanitizeFontFamily(value) {
  // NOTE: This is a fix for a bug introduced earlier that have might modified the font-family in the model
  // adding extra double quotes.
  if (value && value.startsWith('""')) {
    //remove the first and last quotes
    value = value.slice(1).replace(/"([^"]*)$/, "$1");

    // remove quotes from font-family in 1-word font-families
    // and repeated values
    value = [
      ...new Set(
        value
          .split(", ")
          .map((x) => (x.includes(" ") ? x : x.replace(/"/g, ""))),
      ),
    ].join(", ");
  }

  if (!value || value === "") {
    return "var(--fallback-families)";
  } else if (value.endsWith(" var(--fallback-families)")) {
    return value;
  } else if (value.startsWith('"')) {
    return `${value}, var(--fallback-families)`;
  } else {
    return `"${value}", var(--fallback-families)`;
  }
}

/**
 * Merges two style declarations. `source` -> `target`.
 *
 * @param {CSSStyleDeclaration} target
 * @param {CSSStyleDeclaration} source
 * @returns {CSSStyleDeclaration}
 */
export function mergeStyleDeclarations(target, source) {
  // This is better but it doesn't work in JSDOM
  // for (const styleName of source) {
  for (let index = 0; index < source.length; index++) {
    const styleName = source.item(index);
    let styleValue = source.getPropertyValue(styleName);
    target.setProperty(styleName, styleValue);
  }
  return target;
}

/**
 * Resets the properties of a style declaration.
 *
 * @param {CSSStyleDeclaration} styleDeclaration
 * @returns {CSSStyleDeclaration}
 */
function resetStyleDeclaration(styleDeclaration) {
  for (let index = 0; index < styleDeclaration.length; index++) {
    const styleName = styleDeclaration.item(index);
    styleDeclaration.removeProperty(styleName);
  }
  return styleDeclaration;
}

/**
 * Resets the style declaration of the inert
 * element.
 */
export function resetInertElement() {
  const inertElement = getInertElement();
  resetStyleDeclaration(inertElement.style);
  return inertElement;
}

/**
 * An inert element that only keeps the style
 * declaration used for merging other styleDeclarations.
 *
 * @type {HTMLDivElement|null}
 */
let globalInertElement = null;

/**
 * Returns an instance of a <div> element used
 * to keep style declarations.
 *
 * @returns {HTMLDivElement}
 */
function getInertElement() {
  if (!globalInertElement) {
    globalInertElement = document.createElement("div");
    return globalInertElement;
  }
  return globalInertElement;
}

/**
 * Returns a default declaration.
 *
 * @returns {CSSStyleDeclaration}
 */
function getStyleDefaultsDeclaration() {
  const inertElement = getInertElement();
  resetInertElement();
  return inertElement.style;
}

/**
 * Computes the styles of an element the same way `window.getComputedStyle` does.
 *
 * @param {Element} element
 * @returns {CSSStyleDeclaration}
 */
export function getComputedStyle(element) {
  if (typeof window !== "undefined" && window.getComputedStyle) {
    const inertElement = getInertElement();
    resetInertElement(element);
    const computedStyle = window.getComputedStyle(element);
    inertElement.style = computedStyle;
    return inertElement.style;
  }
  return getComputedStylePolyfill(element);
}

/**
 * Returns a polyfilled version of a computed style.
 *
 * @param {Element} element
 * @returns {CSSStyleDeclaration}
 */
export function getComputedStylePolyfill(element) {
  const inertElement = getInertElement();
  resetInertElement(element);
  let currentElement = element;
  while (currentElement) {
    for (let index = 0; index < currentElement.style.length; index++) {
      const styleName = currentElement.style.item(index);
      const currentValue = inertElement.style.getPropertyValue(styleName);
      if (currentValue) {
        const priority = currentElement.style.getPropertyPriority(styleName);
        if (priority === "important") {
          let newValue = currentElement.style.getPropertyValue(styleName);
          inertElement.style.setProperty(styleName, newValue);
        }
      } else {
        let newValue = currentElement.style.getPropertyValue(styleName);
        if (styleName === "font-family") {
          newValue = sanitizeFontFamily(newValue);
        }
        inertElement.style.setProperty(styleName, newValue);
      }
    }
    currentElement = currentElement.parentElement;
  }
  return inertElement.style;
}

/**
 * Normalizes style declaration.
 *
 * TODO: I think that this also needs to remove some "conflicting"
 *       CSS properties like `font-family` or some CSS variables.
 *
 * @param {Node} node
 * @param {CSSStyleDeclaration} [styleDefaults]
 * @returns {CSSStyleDeclaration}
 */
export function normalizeStyles(
  node,
  styleDefaults = getStyleDefaultsDeclaration(),
) {
  const computedStyle = getComputedStyle(node.parentElement);
  const styleDeclaration = mergeStyleDeclarations(styleDefaults, computedStyle);

  // If there's a color property, we should convert it to
  // a --fills CSS variable property.
  const fills = styleDeclaration.getPropertyValue("--fills");
  const color = styleDeclaration.getPropertyValue("color");
  if (color && !fills) {
    styleDeclaration.removeProperty("color");
    styleDeclaration.setProperty("--fills", getFills(color));
  } else {
    styleDeclaration.setProperty("--fills", fills);
  }

  // If there's a font-family property and not a --font-id, then
  // we remove the font-family because it will not work.
  const fontFamily = styleDeclaration.getPropertyValue("font-family");
  const fontId = styleDeclaration.getPropertyValue("--font-id");
  if (fontFamily && !fontId) {
    styleDeclaration.removeProperty("font-family");
  }

  const fontSize = styleDeclaration.getPropertyValue("font-size");
  if (!fontSize || fontSize === "0px") {
    styleDeclaration.setProperty("font-size", DEFAULT_FONT_SIZE);
  }

  const fontWeight = styleDeclaration.getPropertyValue("font-weight");
  if (!fontWeight || fontWeight === "0") {
    styleDeclaration.setProperty("font-weight", DEFAULT_FONT_WEIGHT);
  }

  const lineHeight = styleDeclaration.getPropertyValue("line-height");
  if (!lineHeight || lineHeight === "" || !lineHeight.endsWith("px")) {
    // TODO: Podríamos convertir unidades en decimales.
    styleDeclaration.setProperty("line-height", DEFAULT_LINE_HEIGHT);
  } else if (lineHeight.endsWith("px")) {
    const fontSize = styleDeclaration.getPropertyValue("font-size");
    styleDeclaration.setProperty(
      "line-height",
      parseFloat(lineHeight) / parseFloat(fontSize),
    );
  }
  return styleDeclaration;
}

/**
 * Sets a single style property value of an element.
 *
 * @param {HTMLElement} element
 * @param {string} styleName
 * @param {*} styleValue
 * @param {string} [styleUnit]
 * @returns {HTMLElement}
 */
export function setStyle(element, styleName, styleValue, styleUnit) {
  if (
    styleName.startsWith("--") &&
    typeof styleValue !== "string" &&
    typeof styleValue !== "number"
  ) {
    element.style.setProperty(styleName, JSON.stringify(styleValue));
  } else {
    if (styleName === "font-family") {
      styleValue = sanitizeFontFamily(styleValue);
    }

    element.style.setProperty(
      styleName,
      styleValue + (styleUnit ? styleUnit : ""),
    );
  }
  return element;
}

/**
 * Returns the value of the font size
 *
 * @param {number} styleValueAsNumber
 * @param {string} styleValue
 * @returns {string}
 */
function getStyleFontSize(styleValueAsNumber, styleValue) {
  if (styleValue.endsWith("pt")) {
    const baseSize = 1.3333;
    return (styleValueAsNumber * baseSize).toFixed();
  } else if (styleValue.endsWith("em")) {
    const baseSize = DEFAULT_FONT_SIZE_VALUE;
    return (styleValueAsNumber * baseSize).toFixed();
  } else if (styleValue.endsWith("%")) {
    const baseSize = DEFAULT_FONT_SIZE_VALUE;
    return ((styleValueAsNumber / 100) * baseSize).toFixed();
  }
  return styleValueAsNumber.toFixed();
}

/**
 * Returns the value of a style from a declaration.
 *
 * @param {CSSStyleDeclaration} style
 * @param {string} styleName
 * @param {string|undefined} [styleUnit]
 * @returns {string}
 */
export function getStyleFromDeclaration(style, styleName, styleUnit) {
  if (styleName.startsWith("--")) {
    return style.getPropertyValue(styleName);
  }
  const styleValue = style.getPropertyValue(styleName);
  if (styleValue.endsWith(styleUnit)) {
    return styleValue.slice(0, -styleUnit.length);
  }
  const styleValueAsNumber = parseFloat(styleValue);
  if (styleName === "font-size") {
    return getStyleFontSize(styleValueAsNumber, styleValue);
  } else if (styleName === "line-height") {
    return styleValue;
  }
  if (Number.isNaN(styleValueAsNumber)) {
    return styleValue;
  }
  return styleValueAsNumber.toFixed();
}

/**
 * Returns the value of a style.
 *
 * @param {HTMLElement} element
 * @param {string} styleName
 * @param {string|undefined} [styleUnit]
 * @returns {*}
 */
export function getStyle(element, styleName, styleUnit) {
  return getStyleFromDeclaration(element.style, styleName, styleUnit);
}

/**
 * Sets the styles of an element using an object and a list of
 * allowed styles.
 *
 * @param {HTMLElement} element
 * @param {Array<[string,?string]>} allowedStyles
 * @param {Object.<string, *>} styleObject
 * @returns {HTMLElement}
 */
export function setStylesFromObject(element, allowedStyles, styleObject) {
  if (element.tagName === "SPAN")
    for (const [styleName, styleUnit] of allowedStyles) {
      if (!(styleName in styleObject)) {
        continue;
      }
      let styleValue = styleObject[styleName];
      if (styleName === "font-family") {
        styleValue = sanitizeFontFamily(styleValue);
      }

      if (styleValue) {
        setStyle(element, styleName, styleValue, styleUnit);
      }
    }
  return element;
}

/**
 * Sets the styles of an element using a CSS Style Declaration and a list
 * of allowed styles.
 *
 * @param {HTMLElement} element
 * @param {Array<[string,?string]>} allowedStyles
 * @param {CSSStyleDeclaration} styleDeclaration
 * @returns {HTMLElement}
 */
export function setStylesFromDeclaration(
  element,
  allowedStyles,
  styleDeclaration,
) {
  for (const [styleName, styleUnit] of allowedStyles) {
    const styleValue = getStyleFromDeclaration(
      styleDeclaration,
      styleName,
      styleUnit,
    );
    if (styleValue) {
      setStyle(element, styleName, styleValue, styleUnit);
    }
  }
  return element;
}

/**
 * Sets the styles of an element using an Object or a CSS Style Declaration and
 * a list of allowed styles.
 *
 * @param {HTMLElement} element
 * @param {Array<[string,?string]} allowedStyles
 * @param {Object.<string,*>|CSSStyleDeclaration} styleObjectOrDeclaration
 * @returns {HTMLElement}
 */
export function setStyles(element, allowedStyles, styleObjectOrDeclaration) {
  if (styleObjectOrDeclaration instanceof CSSStyleDeclaration) {
    return setStylesFromDeclaration(
      element,
      allowedStyles,
      styleObjectOrDeclaration,
    );
  }
  return setStylesFromObject(element, allowedStyles, styleObjectOrDeclaration);
}

/**
 * Gets the styles of an element using a list of allowed styles.
 *
 * @param {HTMLElement} element
 * @param {Array<[string,?string]} allowedStyles
 * @returns {Object.<string, *>}
 */
export function getStyles(element, allowedStyles) {
  const styleObject = {};
  for (const [styleName, styleUnit] of allowedStyles) {
    const styleValue = getStyle(element, styleName, styleUnit);
    if (styleValue) {
      styleObject[styleName] = styleValue;
    }
  }
  return styleObject;
}

/**
 * Returns a series of merged styles.
 *
 * @param {Array<[string,?string]} allowedStyles
 * @param {CSSStyleDeclaration} styleDeclaration
 * @param {Object.<string,*>} newStyles
 * @returns {Object.<string,*>}
 */
export function mergeStyles(allowedStyles, styleDeclaration, newStyles) {
  const mergedStyles = {};
  for (const [styleName, styleUnit] of allowedStyles) {
    if (styleName in newStyles) {
      mergedStyles[styleName] = newStyles[styleName];
    } else {
      mergedStyles[styleName] = getStyleFromDeclaration(
        styleDeclaration,
        styleName,
        styleUnit,
      );
    }
  }
  return mergedStyles;
}

/**
 * Returns true if the specified style declaration has a display block.
 *
 * @param {CSSStyleDeclaration} style
 * @returns {boolean}
 */
export function isDisplayBlock(style) {
  return style.display === "block";
}

/**
 * Returns true if the specified style declaration has a display inline
 * or inline-block.
 *
 * @param {CSSStyleDeclaration} style
 * @returns {boolean}
 */
export function isDisplayInline(style) {
  return style.display === "inline" || style.display === "inline-block";
}
