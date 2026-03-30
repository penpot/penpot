import { createContext, type RefObject } from 'react'
import type { Fill } from 'penpot-exporter/types'

export interface FillEditorContextValue {
  activeFillIndex: number | null
  activeFill: Fill | null
  anchorY: number
  openEditor: (index: number, fill: Fill, anchorY: number, onChange: (fill: Fill) => void) => void
  closeEditor: () => void
  onChangeRef: RefObject<((fill: Fill) => void) | null>
}

export const FillEditorContext = createContext<FillEditorContextValue>({
  activeFillIndex: null,
  activeFill: null,
  anchorY: 12,
  openEditor: () => {},
  closeEditor: () => {},
  onChangeRef: { current: null },
})
