/**
 * Undo/redo stacks of commit frames. Does not import commit pipeline (avoids circular deps).
 * Orchestration: {@link undo}/{@link redo} in page-crud call commitChanges.
 */

import { create } from 'zustand'
import type { CommitFrame } from '../changes/commit-types'

const MAX_UNDO = 200

export interface HistoryState {
  undoStack: CommitFrame[]
  redoStack: CommitFrame[]
  /** New user commit: push undo frame, clear redo (Penpot-style). */
  pushCommitFrame: (frame: CommitFrame) => void
  /** Pop next undo frame (mutates undo stack). */
  popUndoFrame: () => CommitFrame | undefined
  /** Push frame onto redo stack after undo. */
  pushRedoFrame: (frame: CommitFrame) => void
  /** Pop next redo frame. */
  popRedoFrame: () => CommitFrame | undefined
  /** After redo, push frame back onto undo stack. */
  pushUndoFrame: (frame: CommitFrame) => void
  clearHistory: () => void
}

export const useHistoryStore = create<HistoryState>()((set, get) => ({
  undoStack: [],
  redoStack: [],

  pushCommitFrame: (frame) => {
    if (frame.undoChanges.length === 0) return
    set((s) => ({
      undoStack: [...s.undoStack, frame].slice(-MAX_UNDO),
      redoStack: [],
    }))
  },

  popUndoFrame: () => {
    const { undoStack } = get()
    if (undoStack.length === 0) return undefined
    const frame = undoStack[undoStack.length - 1]
    set({ undoStack: undoStack.slice(0, -1) })
    return frame
  },

  pushRedoFrame: (frame) => {
    set((s) => ({ redoStack: [...s.redoStack, frame] }))
  },

  popRedoFrame: () => {
    const { redoStack } = get()
    if (redoStack.length === 0) return undefined
    const frame = redoStack[redoStack.length - 1]
    set({ redoStack: redoStack.slice(0, -1) })
    return frame
  },

  pushUndoFrame: (frame) => {
    set((s) => ({
      undoStack: [...s.undoStack, frame].slice(-MAX_UNDO),
    }))
  },

  clearHistory: () => set({ undoStack: [], redoStack: [] }),
}))
