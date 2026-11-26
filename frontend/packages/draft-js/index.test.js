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
  test.each([
    ["missing keys", makeSelection("missing-start", 0, "missing-end", 3)],
    ["invalid offsets", makeSelection("first", 8, "second", 9)],
  ])("clears entities and preserves history for %s", (_, invalid) => {
    for (const hasFocus of [true, false]) {
      const selected = withSelection(
        makeState(),
        makeSelection("first", 1, "second", 2),
      );
      const blurred = addBlurSelectionEntity(selected);
      expect(
        blurred.getCurrentContent().getBlockForKey("first").getEntityAt(1),
      ).not.toBeNull();
      const withRedo = EditorState.set(blurred, {
        redoStack: EditorState.undo(blurred).getRedoStack(),
      });
      const stale = EditorState.acceptSelection(
        withRedo,
        invalid.set("hasFocus", hasFocus),
      );
      const undoStack = stale.getUndoStack();
      const redoStack = stale.getRedoStack();
      expect(redoStack.isEmpty()).toBe(false);
      const expectedBefore = stale.getCurrentContent().getSelectionBefore();
      const result = removeBlurSelectionEntity(stale);
      expect(
        result.getCurrentContent().getBlockForKey("first").getEntityAt(1),
      ).toBeNull();
      expect(result.getUndoStack()).toBe(undoStack);
      expect(result.getRedoStack()).toBe(redoStack);
      expect(result.getSelection().getHasFocus()).toBe(hasFocus);
      expect(result.getSelection().getAnchorKey()).toBe("second");
      expect(result.getSelection().getAnchorOffset()).toBe(5);
      expect(result.getSelection().isCollapsed()).toBe(true);
      const undone = EditorState.undo(result);
      expect(undone.getCurrentContent()).toBe(undoStack.peek());
      expect(undone.getSelection()).toEqual(expectedBefore);
      const redone = EditorState.redo(undone);
      expect(redone.getSelection().getAnchorKey()).toBe("second");
      expect(redone.getSelection().getAnchorOffset()).toBe(5);
      expect(redone.getSelection().isCollapsed()).toBe(true);
      expect(redone.getSelection().getHasFocus()).toBe(hasFocus);
    }
  });

  test("uses a valid undo selection when the saved selection is stale", () => {
    const selected = withSelection(
      makeState(),
      makeSelection("first", 1, "second", 2),
    );
    const blurred = addBlurSelectionEntity(selected);
    const staleSelection = makeSelection("missing", 0, "missing", 1);
    const content = blurred
      .getCurrentContent()
      .set("selectionBefore", staleSelection);
    const stale = EditorState.acceptSelection(
      EditorState.set(blurred, { currentContent: content }),
      staleSelection,
    );
    const undone = EditorState.undo(removeBlurSelectionEntity(stale));
    const selection = undone.getSelection();
    expect(selection.isCollapsed()).toBe(true);
    expect(
      undone.getCurrentContent().getBlockForKey(selection.getAnchorKey()),
    ).toBeDefined();
    expect(selection.getAnchorOffset()).toBeLessThanOrEqual(
      undone
        .getCurrentContent()
        .getBlockForKey(selection.getAnchorKey())
        .getLength(),
    );
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
    expect(result.getSelection()).toEqual(blurred.getSelection());
    expect(result.getUndoStack().size).toBe(blurred.getUndoStack().size + 1);
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
