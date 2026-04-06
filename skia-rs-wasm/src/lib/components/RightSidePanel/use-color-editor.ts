import { useCallback, useContext } from 'react'
import type { Fill } from 'penpot-exporter/types'
import {
  ColorEditorContext,
  type ColorEditorContextValue,
  type ColorEditorKind,
} from './color-editor-context'

/** Full unified context — used by FloatingColorEditorPanel, FloatingEffectEditorPanel, and Sections. */
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

  return {
    isActive,
    openEditor,
    closeEditor: ctx.closeEditor,
    activeEffect: ctx.activeEffect,
    setActiveEffect: ctx.setActiveEffect,
    onEffectChangeRef: ctx.onEffectChangeRef,
  }
}
