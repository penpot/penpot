import { createContext, type RefObject } from 'react'
import type { Fill } from 'penpot-exporter/types'
import type { EffectItem } from '../../renderer/properties/panel-utils'

export type ColorEditorKind = 'fill' | 'stroke' | 'shadow'

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

  // Effect editor: current effect being edited in the floating effect panel
  activeEffect: EffectItem | null
  setActiveEffect: (effect: EffectItem | null) => void
  // Effect editor: callback for effect changes (properties, type, etc.)
  onEffectChangeRef: RefObject<((effect: EffectItem) => void) | null>
}

export const ColorEditorContext = createContext<ColorEditorContextValue>({
  activeTarget: null,
  activeFill: null,
  anchorY: 12,
  title: '',
  openEditor: () => {},
  closeEditor: () => {},
  onChangeRef: { current: null },
  activeEffect: null,
  setActiveEffect: () => {},
  onEffectChangeRef: { current: null },
})
