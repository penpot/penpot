/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC Sucursal en España SL
 */
import { describe, test, expect } from "vitest";
import {
  EditorState,
  SelectionState,
  addBlurSelectionEntity,
  convertFromRaw,
  createEditorState,
  cursorToEnd,
  removeBlurSelectionEntity,
} from "./index.js";

/* @vitest-environment node */

function makeContent() {
  return convertFromRaw({
    blocks: [
      {
        key: "first",
        text: "Hello",
        type: "unstyled",
        depth: 0,
        inlineStyleRanges: [],
        entityRanges: [],
        data: {},
      },
      {
        key: "second",
        text: "World",
        type: "unstyled",
        depth: 0,
        inlineStyleRanges: [],
        entityRanges: [],
        data: {},
      },
    ],
    entityMap: {},
  });
}

function makeState() {
  return createEditorState(makeContent(), null);
}

function makeSelection(anchorKey, anchorOffset, focusKey, focusOffset) {
  return new SelectionState({
    anchorKey,
    anchorOffset,
    focusKey,
    focusOffset,
    isBackward: false,
    hasFocus: true,
  });
}

function withSelection(state, selection) {
  return EditorState.forceSelection(state, selection);
}

describe("addBlurSelectionEntity", () => {
  test("does not throw on stale selection keys and returns state unchanged", () => {
    const state = makeState();
    const stale = withSelection(
      state,
      makeSelection("missing-start", 0, "missing-end", 3),
    );
    expect(() => addBlurSelectionEntity(stale)).not.toThrow();
    expect(addBlurSelectionEntity(stale)).toBe(stale);
  });

  test("skips collapsed selections and returns state unchanged", () => {
    const state = makeState();
    const collapsed = withSelection(
      state,
      makeSelection("first", 2, "first", 2),
    );
    expect(addBlurSelectionEntity(collapsed)).toBe(collapsed);
  });

  test("applies PENPOT_SELECTION entity on valid non-collapsed selections", () => {
    const state = makeState();
    const selected = withSelection(
      state,
      makeSelection("first", 0, "first", 5),
    );
    const result = addBlurSelectionEntity(selected);
    const block = result.getCurrentContent().getBlockForKey("first");
    expect(block.getEntityAt(0)).not.toBeNull();
    expect(block.getEntityAt(4)).not.toBeNull();
    expect(block.getEntityAt(0)).toBe(block.getEntityAt(4));
  });
});

describe("removeBlurSelectionEntity", () => {
  test("does not throw on stale selection keys and returns state unchanged", () => {
    const state = makeState();
    const stale = withSelection(
      state,
      makeSelection("missing-start", 0, "missing-end", 3),
    );
    expect(() => removeBlurSelectionEntity(stale)).not.toThrow();
    expect(removeBlurSelectionEntity(stale)).toBe(stale);
  });

  test("clears entities and restores the user selection", () => {
    const state = makeState();
    const selected = withSelection(
      state,
      makeSelection("first", 1, "second", 2),
    );
    const blurred = addBlurSelectionEntity(selected);
    const result = removeBlurSelectionEntity(blurred);
    expect(
      result.getCurrentContent().getBlockForKey("first").getEntityAt(1),
    ).toBeNull();
    expect(result.getSelection().getAnchorKey()).toBe(
      blurred.getSelection().getAnchorKey(),
    );
  });
});

describe("cursorToEnd", () => {
  test("moves the selection to the end of the last block", () => {
    const state = makeState();
    const result = cursorToEnd(state);
    const selection = result.getSelection();
    expect(selection.getAnchorKey()).toBe("second");
    expect(selection.getAnchorOffset()).toBe(5);
    expect(selection.isCollapsed()).toBe(true);
  });
});
