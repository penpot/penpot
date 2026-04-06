import { useCallback } from 'react'
import type { Fill } from 'penpot-exporter/types'
import { FillEditor } from '../FillEditor/FillEditor'
import { useColorEditor } from './use-color-editor'
import { FloatingPanelShell } from './FloatingPanelShell'

export function FloatingColorEditorPanel() {
  const { activeTarget, activeFill, anchorY, title, closeEditor, onChangeRef } = useColorEditor()

  const targetKey =
    activeTarget && activeTarget.kind !== 'shadow'
      ? `${activeTarget.kind}-${activeTarget.index}`
      : null

  const handleChange = useCallback(
    (next: Fill) => {
      onChangeRef.current?.(next)
    },
    [onChangeRef],
  )

  if (!targetKey || !activeFill) return null

  return (
    <FloatingPanelShell
      targetKey={targetKey}
      anchorY={anchorY}
      title={title}
      onClose={closeEditor}
    >
      <FillEditor fill={activeFill} onChange={handleChange} />
    </FloatingPanelShell>
  )
}
