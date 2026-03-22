/**
 * Commit record for Penpot-shaped pipeline (redo/undo change vectors).
 */

import type { Change } from 'penpot-exporter/types'

/** One undo/redo frame: forward edit and its inverse. */
export interface CommitFrame {
  redoChanges: Change[]
  undoChanges: Change[]
}

export interface CommitChangesParams {
  redoChanges: Change[]
  undoChanges?: Change[]
  /** Resolved with workspace `pageId` or first change carrying `pageId` when missing. */
  pageId?: string | null
  /** Default: true when `undoChanges.length > 0`. Explicit `false` skips history even if undo is provided. */
  saveUndo?: boolean
  /** When true (undo/redo replay), do not push history and do not clear redo stack via push. */
  fromHistory?: boolean
  /** Skip renderer sync after local document apply (rare). */
  ignoreRendererSync?: boolean
}
