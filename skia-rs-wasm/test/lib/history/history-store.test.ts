import { beforeEach, describe, expect, it } from 'vitest'
import { useHistoryStore } from '../../../src/lib/history/history-store'

describe('useHistoryStore', () => {
  beforeEach(() => {
    useHistoryStore.setState({ undoStack: [], redoStack: [] })
  })

  it('pushCommitFrame clears redo stack', () => {
    const frame = {
      redoChanges: [{ type: 'mod-obj' as const, id: 'x', operations: [] }],
      undoChanges: [{ type: 'mod-obj' as const, id: 'x', operations: [] }],
    }
    useHistoryStore.setState({ redoStack: [frame] })
    useHistoryStore.getState().pushCommitFrame(frame)
    expect(useHistoryStore.getState().redoStack).toHaveLength(0)
    expect(useHistoryStore.getState().undoStack).toHaveLength(1)
  })

  it('popUndoFrame / pushRedoFrame round-trip', () => {
    const frame = {
      redoChanges: [{ type: 'mod-obj' as const, id: 'a', operations: [] }],
      undoChanges: [{ type: 'mod-obj' as const, id: 'a', operations: [] }],
    }
    useHistoryStore.getState().pushCommitFrame(frame)
    const popped = useHistoryStore.getState().popUndoFrame()
    expect(popped).toEqual(frame)
    useHistoryStore.getState().pushRedoFrame(frame)
    expect(useHistoryStore.getState().redoStack).toHaveLength(1)
  })
})
