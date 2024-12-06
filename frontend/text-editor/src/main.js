/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import "./style.css";
import "./editor/TextEditor.css";
import { TextEditor } from "./editor/TextEditor";
import { SelectionControllerDebug } from "./editor/debug/SelectionControllerDebug";

const searchParams = new URLSearchParams(location.search);
const debug = searchParams.has("debug")
  ? searchParams.get("debug").split(",")
  : [];

const textEditorSelectionImposterElement = document.getElementById(
  "text-editor-selection-imposter"
);

const textEditorElement = document.querySelector(".text-editor-content");
const textEditor = new TextEditor(textEditorElement, {
  styleDefaults: {
    "font-family": "sourcesanspro",
    "font-size": "14",
    "font-weight": "400",
    "font-style": "normal",
    "line-height": "1.2",
    "letter-spacing": "0",
    "direction": "ltr",
    "text-align": "left",
    "text-transform": "none",
    "text-decoration": "none",
    "--typography-ref-id": '["~#\'",null]',
    "--typography-ref-file": '["~#\'",null]',
    "--font-id": '["~#\'","sourcesanspro"]',
    "--fills": '[["^ ","~:fill-color","#000000","~:fill-opacity",1]]'
  },
  selectionImposterElement: textEditorSelectionImposterElement,
  debug: new SelectionControllerDebug({
    direction: document.getElementById("direction"),
    multiElement: document.getElementById("multi"),
    multiInlineElement: document.getElementById("multi-inline"),
    multiParagraphElement: document.getElementById("multi-paragraph"),
    isParagraphStart: document.getElementById("is-paragraph-start"),
    isParagraphEnd: document.getElementById("is-paragraph-end"),
    isInlineStart: document.getElementById("is-inline-start"),
    isInlineEnd: document.getElementById("is-inline-end"),
    isTextAnchor: document.getElementById("is-text-anchor"),
    isTextFocus: document.getElementById("is-text-focus"),
    focusNode: document.getElementById("focus-node"),
    focusOffset: document.getElementById("focus-offset"),
    focusInline: document.getElementById("focus-inline"),
    focusParagraph: document.getElementById("focus-paragraph"),
    anchorNode: document.getElementById("anchor-node"),
    anchorOffset: document.getElementById("anchor-offset"),
    anchorInline: document.getElementById("anchor-inline"),
    anchorParagraph: document.getElementById("anchor-paragraph"),
    startContainer: document.getElementById("start-container"),
    startOffset: document.getElementById("start-offset"),
    endContainer: document.getElementById("end-container"),
    endOffset: document.getElementById("end-offset"),
  }),
});

const fontFamilyElement = document.getElementById("font-family");
const fontSizeElement = document.getElementById("font-size");
const fontWeightElement = document.getElementById("font-weight");
const fontStyleElement = document.getElementById("font-style");

const directionLTRElement = document.getElementById("direction-ltr");
const directionRTLElement = document.getElementById("direction-rtl");

const lineHeightElement = document.getElementById("line-height");
const letterSpacingElement = document.getElementById("letter-spacing");

const textAlignLeftElement = document.getElementById("text-align-left");
const textAlignCenterElement = document.getElementById("text-align-center");
const textAlignRightElement = document.getElementById("text-align-right");
const textAlignJustifyElement = document.getElementById("text-align-justify");

function onDirectionChange(e) {
  if (debug.includes("events")) {
    console.log(e);
  }
  if (e.target.checked) {
    textEditor.applyStylesToSelection({
      "direction": e.target.value
    });
  }
}

directionLTRElement.addEventListener("change", onDirectionChange);
directionRTLElement.addEventListener("change", onDirectionChange);

function onTextAlignChange(e) {
  if (debug.includes("events")) {
    console.log(e);
  }
  if (e.target.checked) {
    textEditor.applyStylesToSelection({
      "text-align": e.target.value
    });
  }
}

textAlignLeftElement.addEventListener("change", onTextAlignChange);
textAlignCenterElement.addEventListener("change", onTextAlignChange);
textAlignRightElement.addEventListener("change", onTextAlignChange);
textAlignJustifyElement.addEventListener("change", onTextAlignChange);

fontFamilyElement.addEventListener("change", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  textEditor.applyStylesToSelection({
    "font-family": e.target.value,
  });
});

fontWeightElement.addEventListener("change", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  textEditor.applyStylesToSelection({
    "font-weight": e.target.value,
  });
});

fontSizeElement.addEventListener("change", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  textEditor.applyStylesToSelection({
    "font-size": e.target.value,
  });
});

lineHeightElement.addEventListener("change", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  textEditor.applyStylesToSelection({
    "line-height": e.target.value
  })
})

letterSpacingElement.addEventListener("change", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  textEditor.applyStylesToSelection({
    "letter-spacing": e.target.value
  })
})

fontStyleElement.addEventListener("change", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  textEditor.applyStylesToSelection({
    "font-style": e.target.value,
  });
});

function formatHTML(html, options) {
  const spaces = options?.spaces ?? 4;
  let indent = 0;
  return html.replace(/<\/?(.*?)>/g, (fullMatch) => {
    let str = fullMatch + "\n";
    if (fullMatch.startsWith("</")) {
      --indent;
      str = " ".repeat(indent * spaces) + str;
    } else {
      str = " ".repeat(indent * spaces) + str;
      ++indent;
      if (fullMatch === "<br>") --indent;
    }
    return str;
  });
}

const outputElement = document.getElementById("output");
textEditorElement.addEventListener("input", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  outputElement.textContent = formatHTML(textEditor.element.innerHTML);
});

textEditor.addEventListener("stylechange", (e) => {
  if (debug.includes("events")) {
    console.log(e);
  }
  const fontSize = parseInt(e.detail.getPropertyValue("font-size"), 10);
  const fontWeight = e.detail.getPropertyValue("font-weight");
  const fontStyle = e.detail.getPropertyValue("font-style");
  const fontFamily = e.detail.getPropertyValue("font-family");

  fontFamilyElement.value = fontFamily;
  fontSizeElement.value = fontSize;
  fontStyleElement.value = fontStyle;
  fontWeightElement.value = fontWeight;

  const textAlign = e.detail.getPropertyValue("text-align");
  textAlignLeftElement.checked = textAlign === "left";
  textAlignCenterElement.checked = textAlign === "center";
  textAlignRightElement.checked = textAlign === "right";
  textAlignJustifyElement.checked = textAlign === "justify";

  const direction = e.detail.getPropertyValue("direction");
  directionLTRElement.checked = direction === "ltr";
  directionRTLElement.checked = direction === "rtl";
});
