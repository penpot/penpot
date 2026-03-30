import { createContext, type RefObject } from 'react'
import type { Fill } from 'penpot-exporter/types'

export type ColorEditorKind = 'fill' | 'stroke'

export interface ColorEditorTarget {
  kind: ColorEditorKind
  index: number
}

export interface ColorEditorContextValue {
  activeTarget: ColorEditorTarget | null
  activeFill: Fill | null
  anchorY: number
  title: string
  openEditor: (
    kind: ColorEditorKind,
    index: number,
    fill: Fill,
    anchorY: number,
    title: string,
    onChange: (fill: Fill) => void,
  ) => void
  closeEditor: () => void
  onChangeRef: RefObject<((fill: Fill) => void) | null>
}

export const ColorEditorContext = createContext<ColorEditorContextValue>({
  activeTarget: null,
  activeFill: null,
  anchorY: 12,
  title: '',
  openEditor: () => {},
  closeEditor: () => {},
  onChangeRef: { current: null },
})
