import { createContext, useContext } from 'react'
import type { Fill } from 'penpot-exporter/types'

export interface StrokeEditorContextValue {
  activeStrokeIndex: number | null
  /** Stroke color represented as a Fill so FillEditor can be reused directly. */
  activeStrokeFill: Fill | null
  anchorY: number
  openEditor: (index: number, fill: Fill, anchorY: number, onChange: (fill: Fill) => void) => void
  closeEditor: () => void
  onChangeRef: React.RefObject<((fill: Fill) => void) | null>
}

const StrokeEditorContext = createContext<StrokeEditorContextValue>({
  activeStrokeIndex: null,
  activeStrokeFill: null,
  anchorY: 12,
  openEditor: () => {},
  closeEditor: () => {},
  onChangeRef: { current: null },
})

export function useStrokeEditor() {
  return useContext(StrokeEditorContext)
}

export { StrokeEditorContext }
