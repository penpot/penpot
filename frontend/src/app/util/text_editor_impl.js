/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

'use strict';

import {
  CharacterMetadata,
  EditorState,
  CompositeDecorator,
  SelectionState,
  Modifier
} from "draft-js";

import {Map} from "immutable";

function isDefined(v) {
  return v !== undefined && v !== null;
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
  const firstBlock = content.getBlockMap().first();
  const lastBlock = content.getBlockMap().last();

  return new SelectionState({
    "anchorKey": firstBlock.getKey(),
    "anchorOffset": 0,
    "focusKey": lastBlock.getKey(),
    "focusOffset": lastBlock.getLength()
  });
}

export function selectAll(state) {
  return EditorState.forceSelection(state, getSelectAllSelection(state));
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
    let data = block.getData();
    for (let key of Object.keys(attrs)) {
      const oldVal = data.get(key);
      if (oldVal === attrs[key]) {
        data = data.delete(key);
      } else {
        data = data.set(key, attrs[key]);
      }
    }

    return block.merge({
      data: data
    });
  });

  return EditorState.push(state, content, "change-block-data");
}

export function applyInlineStyle(state, styles) {
  const selection = state.getSelection();
  let content = null;

  for (let style of styles) {
    const [p, k, v] = style.split("$$$");
    const prefix = [p, k, ""].join("$$$");

    content = state.getCurrentContent();
    content = removeInlineStylePrefix(content, selection, prefix);

    if (v !== "z:null") {
      content = Modifier.applyInlineStyle(content, selection, style);
    }

    state = EditorState.push(state, content, "change-inline-style");
  }

  return state;
}

export function splitBlockPreservingData(state) {
  let content = state.getCurrentContent();
  const selection = state.getSelection();

  content = Modifier.splitBlock(content, selection);

  const blockData = content.blockMap.get(content.selectionBefore.getStartKey()).getData();
  const blockKey = content.selectionAfter.getStartKey();
  const blockMap = content.blockMap.update(blockKey, (block) => {
    return block.set("data", blockData);
  });

  content = content.set("blockMap", blockMap);

  return EditorState.push(state, content, "split-block");
}

export function addBlurSelectionEntity(state) {
  let content = state.getCurrentContent(state);
  const selection = state.getSelection();

  content = content.createEntity("PENPOT_SELECTION", "MUTABLE");
  const entityKey = content.getLastCreatedEntityKey();

  content = Modifier.applyEntity(content, selection, entityKey);
  return EditorState.push(state, content, "apply-entity");
}

export function removeBlurSelectionEntity(state) {
  const selectionAll = getSelectAllSelection(state);
  const selection = state.getSelection();

  let content = state.getCurrentContent();
  content = Modifier.applyEntity(content, selectionAll, null);

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
