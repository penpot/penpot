import type { JSX } from 'react'
import { useWorkspaceStore } from '../lib/renderer/store/workspace-store'

const MAX_IDS_SHOWN = 5

export function SelectionInfo(): JSX.Element {
  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const selectionRect = useWorkspaceStore((state) => state.selectionRect)
  const selectedNodes = useWorkspaceStore((state) => state.selectedNodes)

  const count = selectedIds.size
  const idList = Array.from(selectedIds)
  const idsDisplay =
    idList.length <= MAX_IDS_SHOWN
      ? idList.join(', ')
      : `${idList.slice(0, MAX_IDS_SHOWN).join(', ')}… (+${idList.length - MAX_IDS_SHOWN})`

  if (count === 0 && selectionRect === null) {
    return (
      <div
        className="selection-info"
        style={{ marginTop: '10px', padding: '10px', backgroundColor: '#f5f5f5', borderRadius: '4px' }}
      >
        <h3>Selection</h3>
        <div style={{ marginBottom: '8px', fontSize: '0.9em', color: '#666' }}>
          No selection. Click or drag to select.
        </div>
      </div>
    )
  }

  return (
    <div
      className="selection-info"
      style={{ marginTop: '10px', padding: '10px', backgroundColor: '#f5f5f5', borderRadius: '4px' }}
    >
      <h3>Selection</h3>
      <div style={{ marginBottom: '8px' }}>
        <strong>Count:</strong> {count}
        {idList.length > 0 && (
          <>
            {' | '}
            <strong>IDs:</strong> {idsDisplay}
          </>
        )}
      </div>
      {selectionRect !== null && (
        <div style={{ marginBottom: '8px' }}>
          <strong>Rect:</strong> X: {selectionRect.x.toFixed(1)}, Y: {selectionRect.y.toFixed(1)}, W:{' '}
          {selectionRect.width.toFixed(1)}, H: {selectionRect.height.toFixed(1)}
        </div>
      )}
      {selectedNodes.length > 0 && (
        <div style={{ fontSize: '0.9em', color: '#666' }}>
          {selectedNodes.slice(0, MAX_IDS_SHOWN).map((node) => {
            const name = (node as { name?: string }).name ?? '(unnamed)'
            const type = (node as { type?: string }).type ?? '?'
            return (
              <div key={node.id}>
                {type}: {name} ({node.id.slice(0, 8)}…)
              </div>
            )
          })}
          {selectedNodes.length > MAX_IDS_SHOWN && (
            <div>…and {selectedNodes.length - MAX_IDS_SHOWN} more</div>
          )}
        </div>
      )}
    </div>
  )
}
