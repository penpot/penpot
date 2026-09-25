/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC Sucursal en España SL
 */
import pkg from "draft-js";

export const {
  BlockMapBuilder,
  CharacterMetadata,
  CompositeDecorator,
  EditorState,
  Modifier,
  RichTextEditorUtil,
  SelectionState,
  convertFromRaw,
  convertToRaw,
  EditorBlock,
  Editor
} = pkg;

import DraftPasteProcessor from 'draft-js/lib/DraftPasteProcessor.js';
import Immutable from "immutable";
const {Map, OrderedSet} = Immutable;

function isDefined(v) {
  return v !== undefined && v !== null;
}

// A selection can outlive the content it points to: blurring the editor
// rebuilds the EditorState with fresh block keys while a delayed on-change
// still carries the old keys. Applying an entity with such a stale selection
// crashes inside Draft.js (`getCharacterList` of undefined), taking down the
// whole workspace. Validate before every `Modifier.applyEntity` call and
// skip silently: the blur highlight is cosmetic.
function isSelectionInContent(content, selection) {
  if (!content || !selection) return false;
  const blockMap = content.getBlockMap();
  if (!blockMap || blockMap.isEmpty()) return false;

  const startKey = selection.getStartKey();
  const endKey = selection.getEndKey();
  const startBlock = blockMap.get(startKey);
  const endBlock = blockMap.get(endKey);
  if (!startBlock || !endBlock) return false;

  const startOffset = selection.getStartOffset();
  const endOffset = selection.getEndOffset();
  if (startOffset < 0 || endOffset < 0) return false;
  if (startOffset > startBlock.getLength()) return false;
  if (endOffset > endBlock.getLength()) return false;
  return true;
}

function mergeBlockData(block, newData) {
  if (!block) return undefined;
  let data = block.getData();

  for (let key of Object.keys(newData)) {
    const oldVal = data.get(key);
    if (oldVal === newData[key]) {
      data = data.delete(key);
    } else {
      data = data.set(key, newData[key]);
    }
  }

  return block.mergeDeep({
    data: data
  });
}

export function createEditorState(content, decorator) {
  if (content === null) {
    return EditorState.createEmpty(decorator);
  } else {
    return EditorState.createWithContent(content, decorator);
  }
}

export function createDecorator(type, component) {
  const strategy = (block, callback, content) => {
    return block.findEntityRanges((cmeta) => {
      const entityKey = cmeta.getEntity();
      return isDefined(entityKey) && (type === content.getEntity(entityKey).getType());
    }, callback);
  };

  return new CompositeDecorator([
    {"strategy": strategy, "component": component}
  ]);
}

function getSelectAllSelection(state) {
  const content = state.getCurrentContent();
  const blockMap = content.getBlockMap();
  if (!blockMap || blockMap.isEmpty()) return null;
  const firstBlock = blockMap.first();
  const lastBlock = blockMap.last();

  return new SelectionState({
    "anchorKey": firstBlock.getKey(),
    "anchorOffset": 0,
    "focusKey": lastBlock.getKey(),
    "focusOffset": lastBlock.getLength()
  });
}

function getCursorInEndPosition(state) {
  const content = state.getCurrentContent();
  const blockMap = content.getBlockMap();
  if (!blockMap || blockMap.isEmpty()) return null;
  const lastBlock = blockMap.last();

  return new SelectionState({
    "anchorKey": lastBlock.getKey(),
    "anchorOffset": lastBlock.getLength(),
    "focusKey": lastBlock.getKey(),
    "focusOffset": lastBlock.getLength()
  });
}

export function selectAll(state) {
  const selection = getSelectAllSelection(state);
  if (!selection) return state;
  return EditorState.forceSelection(state, selection);
}

function modifySelectedBlocks(contentState, selectionState, operation) {
  var startKey = selectionState.getStartKey();
  var endKey = selectionState.getEndKey();
  var blockMap = contentState.getBlockMap();

  var newBlocks = blockMap.toSeq().skipUntil(function (_, k) {
    return k === startKey;
  }).takeUntil(function (_, k) {
    return k === endKey;
  }).concat(Map([[endKey, blockMap.get(endKey)]])).map(operation);

  return contentState.merge({
    "blockMap": blockMap.merge(newBlocks),
    "selectionBefore": selectionState,
    "selectionAfter": selectionState
  });
}

export function updateCurrentBlockData(state, attrs) {
  const selection = state.getSelection();
  let content = state.getCurrentContent();

  content = modifySelectedBlocks(content, selection, (block) => {
    return mergeBlockData(block, attrs);
  });

  return EditorState.push(state, content, "change-block-data");
}

function addStylesToOverride(styles, other) {
  let result = styles;

  for (let style of other) {
    const [p, k, v] = style.split("$$$");
    const prefix = [p, k, ""].join("$$$");

    const curValue = result.find((it) => it.startsWith(prefix))
    if (curValue) {
      result = result.remove(curValue);
    }
    result = result.add(style);
  }
  return result
}

export function applyInlineStyle(state, styles) {
  const userSelection = state.getSelection();
  let selection = userSelection;
  let result = state;

  if (selection.isCollapsed()) {
    const currentOverride = state.getCurrentInlineStyle() || new OrderedSet();
    const styleOverride = addStylesToOverride(currentOverride, styles)
    return EditorState.setInlineStyleOverride(state, styleOverride);
  }

  let content = null;

  for (let style of styles) {
    const [p, k, v] = style.split("$$$");
    const prefix = [p, k, ""].join("$$$");

    content = result.getCurrentContent();
    content = removeInlineStylePrefix(content, selection, prefix);

    if (v !== "z:null") {
      content = Modifier.applyInlineStyle(content, selection, style);
    }

    result = EditorState.push(result, content, "change-inline-style");
  }

  return EditorState.acceptSelection(result, userSelection);
}

export function splitBlockPreservingData(state) {
  let content = state.getCurrentContent();
  const selection = state.getSelection();

  content = Modifier.splitBlock(content, selection);

  const startKey = content.selectionBefore.getStartKey();
  const block = content.blockMap.get(startKey);
  const blockData = (block && block.getData()) || new Map();
  const blockKey = content.selectionAfter.getStartKey();
  const blockMap = content.blockMap.update(blockKey, (b) => {
    return b.set("data", blockData);
  });

  content = content.set("blockMap", blockMap);

  return EditorState.push(state, content, "split-block");
}

export function addBlurSelectionEntity(state) {
  const selection = state.getSelection();
  if (selection.isCollapsed()) return state;

  let content = state.getCurrentContent();
  if (!isSelectionInContent(content, selection)) return state;

  try {
    content = content.createEntity("PENPOT_SELECTION", "MUTABLE");
    const entityKey = content.getLastCreatedEntityKey();
    content = Modifier.applyEntity(content, selection, entityKey);
  } catch (_) {
    return state;
  }
  return EditorState.push(state, content, "apply-entity");
}

export function removeBlurSelectionEntity(state) {
  const selectionAll = getSelectAllSelection(state);
  if (!selectionAll) return state;
  const selection = state.getSelection();

  let content = state.getCurrentContent();
  if (!isSelectionInContent(content, selection)) return state;

  try {
    content = Modifier.applyEntity(content, selectionAll, null);
  } catch (_) {
    return state;
  }

  state = EditorState.push(state, content, "apply-entity");
  state = EditorState.forceSelection(state, selection);

  return state;
}

export function getCurrentBlock(state) {
  const content = state.getCurrentContent();
  const selection = state.getSelection();
  const startKey = selection.getStartKey();
  return content.getBlockForKey(startKey);
}

export function getCurrentEntityKey(state) {
  const block = getCurrentBlock(state);
  const selection = state.getSelection();
  const startOffset = selection.getStartOffset();
  return block.getEntityAt(startOffset);
}

export function removeInlineStylePrefix(contentState, selectionState, stylePrefix) {
  const startKey = selectionState.getStartKey();
  const startOffset = selectionState.getStartOffset();
  const endKey = selectionState.getEndKey();
  const endOffset = selectionState.getEndOffset();

  return modifySelectedBlocks(contentState, selectionState, (block, blockKey) => {
    let sliceStart;
    let sliceEnd;

    if (startKey === endKey) {
      sliceStart = startOffset;
      sliceEnd = endOffset;
    } else {
      sliceStart = blockKey === startKey ? startOffset : 0;
      sliceEnd = blockKey === endKey ? endOffset : block.getLength();
    }

    let chars = block.getCharacterList();
    let current;

    while (sliceStart < sliceEnd) {
      current = chars.get(sliceStart);
      current = current.set("style", current.getStyle().filter((s) => !s.startsWith(stylePrefix)))
      chars = chars.set(sliceStart, CharacterMetadata.create(current));

      sliceStart++;
    }

    return block.set("characterList", chars);
  });
}

export function cursorToEnd(state) {
  const newSelection = getCursorInEndPosition(state);
  if (!newSelection) return state;

  let content = state.getCurrentContent();
  if (!isSelectionInContent(content, newSelection)) return state;

  try {
    content = Modifier.applyEntity(content, newSelection, null);
  } catch (_) {
    return state;
  }

  state = EditorState.forceSelection(state, newSelection);
  state = EditorState.push(state, content, "apply-entity");

  return state;
}

export function isCurrentEmpty(state) {
  const selection = state.getSelection();

  if (!selection.isCollapsed()) {
    return false;
  }

  const blockKey = selection.getStartKey();
  const content = state.getCurrentContent();

  const block = content.getBlockForKey(blockKey);

  return block.getText() === "";
}

/*
  Returns the block keys between a selection
*/
export function getSelectedBlocks(state) {
  const selection = state.getSelection();
  const startKey = selection.getStartKey();
  const endKey = selection.getEndKey();
  const content = state.getCurrentContent();
  const result = [ startKey ];

  let currentKey = startKey;

  while (currentKey !== endKey) {
    const currentBlock = content.getBlockAfter(currentKey);
    currentKey = currentBlock.getKey();
    result.push(currentKey);
  }

  return result;
}

export function getBlockContent(state, blockKey) {
  const content = state.getCurrentContent();
  const block = content.getBlockForKey(blockKey);
  return block.getText();
}

export function getBlockData(state, blockKey) {
  const content = state.getCurrentContent();
  const block = content.getBlockForKey(blockKey);
  return block && block.getData().toJS();
}

export function updateBlockData(state, blockKey, data) {
  const userSelection = state.getSelection();
  const inlineStyleOverride = state.getInlineStyleOverride();
  const content = state.getCurrentContent();
  const block = content.getBlockForKey(blockKey);
  const newBlock = mergeBlockData(block, data);
  if (!newBlock) return state;

  const blockData = newBlock.getData();

  const newContent = Modifier.setBlockData(
    state.getCurrentContent(),
    SelectionState.createEmpty(blockKey),
    blockData
  );

  let result = EditorState.push(state, newContent, 'change-block-data');
  result = EditorState.acceptSelection(result, userSelection);
  result = EditorState.setInlineStyleOverride(result, inlineStyleOverride);
  return result;
}

export function getSelection(state) {
  return state.getSelection();
}

export function setSelection(state, selection) {
  return EditorState.acceptSelection(state, selection);
}

export function selectBlock(state, blockKey) {
  const block = state.getCurrentContent().getBlockForKey(blockKey);
  const length = block.getText().length;
  const selection = SelectionState.createEmpty(blockKey).merge({
    focusOffset: length
  });
  return EditorState.acceptSelection(state, selection);
}

export function getInlineStyle(state, blockKey, offset) {
  const content = state.getCurrentContent();
  const block = content.getBlockForKey(blockKey);
  return block.getInlineStyleAt(offset).toJS();
}

const NEWLINE_REGEX = /\r\n?|\n/g;

function splitTextIntoTextBlocks(text) {
  if (text == null) {
    return [];
  }
  return text.split(NEWLINE_REGEX);
}

export function insertText(state, text, attrs, inlineStyles) {
  const blocks = splitTextIntoTextBlocks(text);

  if (blocks.length === 0) {
    return state;
  }

  const character = CharacterMetadata.create({style: OrderedSet(inlineStyles)});

  let blockArray = DraftPasteProcessor.processText(
    blocks,
    character,
    "unstyled",
  );

  blockArray = blockArray.map((b) => {
      return mergeBlockData(b, attrs);
  });

  const fragment = BlockMapBuilder.createFromArray(blockArray);
  const content = state.getCurrentContent();
  const selection = state.getSelection();

  const newContent = Modifier.replaceWithFragment(
    content,
    selection,
    fragment
  );

  const resultSelection = SelectionState.createEmpty(selection.getStartKey());
  return EditorState.push(state, newContent, 'insert-fragment');
}

export function setInlineStyleOverride(state, inlineStyles) {
  return EditorState.setInlineStyleOverride(state, inlineStyles);
}

export function selectionEquals(selection, other) {
  return selection.getAnchorKey() === other.getAnchorKey() &&
    selection.getAnchorOffset() === other.getAnchorOffset() &&
    selection.getFocusKey() === other.getFocusKey() &&
    selection.getFocusOffset() === other.getFocusOffset() &&
    selection.getIsBackward() === other.getIsBackward();
}
