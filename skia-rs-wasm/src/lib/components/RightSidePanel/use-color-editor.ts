import { useCallback, useContext } from 'react'
import type { Fill } from 'penpot-exporter/types'
import type { EffectItem } from '../../renderer/properties/panel-utils'
import {
  ColorEditorContext,
  type ColorEditorContextValue,
  type ColorEditorKind,
} from './color-editor-context'

/** Full unified context — used by FloatingColorEditorPanel and Sections. */
export function useColorEditor(): ColorEditorContextValue {
  return useContext(ColorEditorContext)
}

/**
 * Convenience hook scoped to a specific kind + index.
 * Returns `isActive` (whether this slot is the one being edited)
 * and `openEditor` / `closeEditor` pre-bound to the kind and index.
 */
export function useColorEditorFor(kind: ColorEditorKind, index: number) {
  const ctx = useContext(ColorEditorContext)

  const isActive =
    ctx.activeTarget !== null &&
    ctx.activeTarget.kind === kind &&
    ctx.activeTarget.index === index

  const openEditor = useCallback(
    (fill: Fill, anchorY: number, title: string, onChange: (fill: Fill) => void) => {
      ctx.openEditor(kind, index, fill, anchorY, title, onChange)
    },
    [ctx, kind, index],
  )

  return { isActive, openEditor, closeEditor: ctx.closeEditor }
}

/**
 * Convenience hook for opening the effect editor panel (glass, etc.).
 * Mirrors `useColorEditorFor` but for effect-level editing.
 */
export function useEffectEditorFor(kind: ColorEditorKind, index: number) {
  const ctx = useContext(ColorEditorContext)

  const isActive =
    ctx.activeTarget !== null &&
    ctx.activeTarget.kind === kind &&
    ctx.activeTarget.index === index

  const openEffectEditor = useCallback(
    (effect: EffectItem, anchorY: number, title: string, onChange: (effect: EffectItem) => void) => {
      ctx.openEffectEditor(kind, index, effect, anchorY, title, onChange)
    },
    [ctx, kind, index],
  )

  return { isActive, openEffectEditor, closeEditor: ctx.closeEditor }
}
