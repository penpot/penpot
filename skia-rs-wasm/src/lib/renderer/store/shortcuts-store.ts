/**
 * Zustand store for viewport shortcut configuration.
 * Live modifier keys live in `signals/pointer.ts` (see docs/state-architecture.md §3).
 */

import { create } from 'zustand'
import type { ShortcutsConfig } from '../types'
import { modAlt, modCtrl, modMeta, modShift } from '../signals/pointer'

export interface ModifierKeys {
  shift: boolean
  alt: boolean
  ctrl: boolean
  meta: boolean
}

export const DEFAULT_SHORTCUTS: ShortcutsConfig = {
  panLeft: 'ArrowLeft',
  panRight: 'ArrowRight',
  panUp: 'ArrowUp',
  panDown: 'ArrowDown',
  panStep: 20,
  zoomInKeys: ['Equal', 'NumpadAdd'],
  zoomOutKeys: ['Minus', 'NumpadSubtract'],
  zoomInFactor: 1.1,
  zoomOutFactor: 0.9,
  resetKeys: ['Digit0', 'Numpad0'],
  panMouseButton: 1,
  panWithModifier: 'shift',
  wheelZoomEnabled: true,
  wheelScalePerPixel: 0.002,
}

function mergeShortcuts(
  current: ShortcutsConfig,
  partial: Partial<ShortcutsConfig>
): ShortcutsConfig {
  return {
    ...current,
    ...partial,
    zoomInKeys: partial.zoomInKeys ?? current.zoomInKeys,
    zoomOutKeys: partial.zoomOutKeys ?? current.zoomOutKeys,
    resetKeys: partial.resetKeys ?? current.resetKeys,
  }
}

export interface ViewportShortcutsState {
  viewportShortcuts: ShortcutsConfig
  setViewportShortcuts: (partial: Partial<ShortcutsConfig>) => void
  resetViewportShortcuts: () => void
}

export const useViewportShortcutsStore = create<ViewportShortcutsState>()((set) => ({
  viewportShortcuts: { ...DEFAULT_SHORTCUTS },
  setViewportShortcuts: (partial) =>
    set((state) => ({
      viewportShortcuts: mergeShortcuts(state.viewportShortcuts, partial),
    })),
  resetViewportShortcuts: () =>
    set({ viewportShortcuts: { ...DEFAULT_SHORTCUTS } }),
}))

/** Get current shortcuts without subscribing (e.g. inside event handlers). */
export function getViewportShortcuts(): ShortcutsConfig {
  return useViewportShortcutsStore.getState().viewportShortcuts
}

/** Modifier state from signals (e.g. inside drag handlers). */
export function getModifierKeys(): ModifierKeys {
  return {
    shift: modShift.value,
    alt: modAlt.value,
    ctrl: modCtrl.value,
    meta: modMeta.value,
  }
}
