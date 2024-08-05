/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import PositionData from "./layout/PositionData.js";

/**
 * Class responsible for doing the layout of the text.
 */
export class TextLayout {
  /**
   * @type {Range}
   */
  $layoutRange = null;

  /**
   * @type {HTMLDivElement}
   */
  $layoutElement = null; // <div>

  /**
   * @type {SVGForeignObjectElement}
   */
  $layoutContainerElement = null; // <foreignObject>

  /**
   * @type {SVGSVGElement}
   */
  $layoutRootElement = null; // <svg>

  /**
   * @type {number}
   */
  $x = 0;

  /**
   * @type {number}
   */
  $y = 0;

  /**
   * Constructor
   */
  constructor() {
    this.$layoutRange = document.createRange();
    this.$layoutRootElement = document.querySelector('[data-layout-root]');
    this.$layoutContainerElement = document.querySelector("[data-layout-container]");
    this.$layoutElement = this.$getOrCreate("[data-layout]", () => this.$createLayoutElement());
    this.$setupLayout();
  }

  /**
   * Creates the layout root where all the text is going to
   * be layout.
   *
   * @returns {SVGSVGElement}
   */
  $createLayoutRoot() {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.style.position = "fixed";
    svg.style.top = "0px";
    svg.style.left = "0px";
    svg.style.pointerEvents = "none";
    svg.dataset.layoutRoot = true;
    return svg;
  }

  /**
   * Creates the layout container
   *
   * @returns {SVGForeignObjectElement}
   */
  $createLayoutContainer() {
    const ns = "http://www.w3.org/2000/svg";
    const foreignObject = document.createElementNS(ns, "foreignObject");
    foreignObject.dataset.layoutContainer = true;
    return foreignObject;
  }

  /**
   * Creates the layout element that it is going to be used.
   *
   * @returns {HTMLDivElement}
   */
  $createLayoutElement() {
    const element = document.createElement("div");
    element.dataset.layout = true;
    return element;
  }

  /**
   * If the element exists, then it returns the current element
   * otherwise it returns a new element.
   *
   * @param {string} selector
   * @param {Function} factory
   * @param {*} options
   * @returns {HTMLOrSVGElement}
   */
  $getOrCreate(selector, factory, options) {
    const parentElement = options?.parentElement ?? document;
    const element = parentElement.querySelector(selector);
    if (!element) {
      return factory();
    }
    return element;
  }

  $setLayoutPositionFromElement(element) {
    // FIXME: No me gusta esta forma de convertir las coordenadas.
    this.$x = parseFloat(element.dataset.x);
    this.$y = parseFloat(element.dataset.y);
    return this;
  }

  /**
   * Sets the layout element size.
   *
   * @param {HTMLElement} element
   * @returns {TextLayout}
   */
  $setLayoutFromElement(element) {
    return this
      .$setLayoutPositionFromElement(element)
      .$setLayoutRootSize(element.parentElement.clientWidth, element.parentElement.clientHeight)
      .$setLayoutSize(element.parentElement.clientWidth, element.parentElement.clientHeight);
  }

  /**
   * Sets the layout root size.
   *
   * @param {number} width
   * @param {number} height
   * @returns {TextLayout}
   */
  $setLayoutRootSize(width, height) {
    this.$layoutRootElement.setAttribute('width', `${width}px`);
    this.$layoutRootElement.setAttribute('height', `${height}px`);
    return this;
  }

  /**
   * Sets the layout size.
   *
   * @param {number} width
   * @param {number} height
   * @returns {TextLayout}
   */
  $setLayoutSize(width, height) {
    this.$layoutElement.style.width = `${width}px`;
    this.$layoutElement.style.height = `${height}px`;
    return this;
  }

  /**
   * Setups the layout.
   */
  $setupLayout() {
    this.$layoutElement.dataset.layout = true;

    if (!this.$layoutRootElement) {
      this.$layoutRootElement = this.$createLayoutRoot();
      document.body.appendChild(this.$layoutRootElement);
    }

    // If the [data-layout-container] element doesn't exists
    // then we create it and append it to the document.body
    if (!this.$layoutContainerElement) {
      this.$layoutContainerElement = this.$createLayoutContainer();
      this.$layoutRootElement.appendChild(this.$layoutContainerElement);
    }

    // Replaces every children inside the layout container element.
    this.$layoutContainerElement.replaceChildren(this.$layoutElement);
  }

  /**
   * Returns all the range rects.
   *
   * @param {Node} node
   * @param {number} start
   * @param {number} end
   * @returns {DOMRect[]}
   */
  $getRangeRects(node, start, end) {
    const range = this.$layoutRange;
    range.setStart(node, start);
    range.setEnd(node, end);
    return [...range.getClientRects()].filter((clientRect) => clientRect.width > 0);
  }

  /**
   * Fixes the rect position to have the same coordinates as
   * the element container.
   *
   * @param {DOMRect} rect
   * @returns {DOMRect}
   */
  $fixRectPosition = (rect) => {
    if (rect) {
      rect.x += this.$x;
      rect.y += this.$y + rect.height;
      rect.x1 += this.$x;
      rect.y1 += this.$y + rect.height;
      rect.x2 += this.$x;
      rect.y2 += this.$y + rect.height;
    }
    return rect;
  }

  /**
   * Layouts a text node.
   *
   * NOTA: Esto debería ser una operación asíncrona porque ahora mismo si tenemos
   *       mucho texto, ésta operación bloquea la UI.
   *
   * NOTA: Esto puede seguir siendo síncrono siempre y cuando hagamos layout sólo
   *       de aquellos elementos que han cambiado.
   *
   * @param {HTMLElement} parent
   * @param {Node} textNode
   * @param {string} textAlign
   * @returns {LayoutNode}
   */
  $layoutTextNode(parent, textNode, textAlign) {
    if (!textNode) debugger;

    const content = textNode.textContent;
    const textSize = content.length;

    let from = 0;
    let to = 0;
    let current = "";
    let result = [];
    let prevRect = null;

    // This variable is to make sure there are not infinite loops
    // when we don't advance `to` we store true and then force to
    // advance `to` on the next iteration if the condition is true again
    let safeGuard = false;

    while (to < textSize) {
      const rects = this.$getRangeRects(textNode, from, to + 1);
      const splitByWords = textAlign == "justify" && content[to].trim() == "";

      if (rects.length > 1 && safeGuard) {
        from++;
        to++;
        safeGuard = false;
      } else if (rects.length > 1 || splitByWords) {
        const rect = prevRect;
        if (rect) {
          result.push({
            node: parent,
            rect: this.$fixRectPosition(rect),
            text: current,
          });
        }

        if (splitByWords) {
          to++;
        }

        from = to;
        current = "";
        safeGuard = true;
      } else {
        prevRect = rects[0];
        current += content[to];
        to = to + 1;
        safeGuard = false;
      }
    }

    // to == textSize
    const rects = this.$getRangeRects(textNode, from, to);
    const [rect] = rects
    if (rect) {
      result.push({
        node: parent,
        rect: this.$fixRectPosition(rect),
        text: current,
      });
    }
    return result;
  }

  /**
   * Returns a list of positional data.
   *
   * @param {ArrayLike.<HTMLElement>} elements
   * @returns {cljs.PersistentVector.<cljs.PersistentHashMap>}
   */
  $getPositionData(elements) {
    return Array
      .from(elements)
      .flatMap((inlineNode) => {
        const style = window.getComputedStyle(inlineNode.parentElement);
        const textAlign = style.textAlign || "left";
        const textNode = inlineNode.firstChild;
        return this.$layoutTextNode(inlineNode, textNode, textAlign)
                   .map(PositionData.mapLayoutNode);
      });
  }

  /**
   * Performs text layout only on those elements that
   * change and not in every element.
   *
   * @param {HTMLElement} element
   * @param {CommandMutations} mutations
   * @returns {cljs.PersistentHashMap.<cljs.keyword, *>}
   */
  partialLayoutFromElement(element, mutations) {
    // Updates layout position and size.
    this.$setLayoutFromElement(element);
    console.log('partial layout', mutations.added, mutations.removed, mutations.updated);

    // FIXME: Vale, ahora que tenemos identificadores en los nodos
    // y que podemos recorrer esos nodos uno por uno, lo que podemos hacer
    // es:
    //
    // 1. A través del Set de nodos que nos pasará el editor, buscar esos
    //    nodos en el árbol.
    // 2. Si no existen subimos en el árbol una posición y buscamos dónde
    //    debería insertarse ese árbol.
    //
    // TODO: Para optimizar esta función, en vez de clonar todo el layout
    // utilizando `element.cloneNode(true)` creo que voy a necesitar atravesar
    // el árbol (¿usando un TreeWalker?) y clonar a mano todo para poder
    // mantener la relación entre los nodos del editor y los nodos del layout.
    this.$layoutElement.replaceChildren(
      element.cloneNode(true)
    );
    const subelements = this.$layoutElement.querySelectorAll(
      '[data-itype="inline"]',
    );
    return this.$getPositionData(subelements);
  }

  /**
   * Layouts the text.
   *
   * NOTA: Podría tener dos métodos, uno más directo, que utilice
   *       el propio editor de texto, algo como `fastLayout` y otro
   *       más lento que haga el layout a partir de un Content de texto.
   *
   * @param {HTMLElement}
   * @returns {cljs.PersistentHashMap.<cljs.keyword, *>}
   */
  layoutFromElement(element) {
    // Updates layout position and size.
    this.$setLayoutFromElement(element);
    this.$layoutElement.replaceChildren(element.cloneNode(true));
    const subelements = this.$layoutElement.querySelectorAll(
      '[data-itype="inline"]',
    );
    return this.$getPositionData(subelements);
  }
}

/**
 * Instance of the TextLayout.
 *
 * @type {TextLayout}
 */
export const textLayout = new TextLayout();

export default textLayout;
