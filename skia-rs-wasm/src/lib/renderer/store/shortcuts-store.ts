/**
 * Zustand store for viewport shortcut configuration and live modifier key state.
 * Apps can override pan, zoom, reset keys and mouse behavior via useViewportShortcuts() or setViewportShortcuts.
 * Live modifier keys (shift, alt, ctrl, meta) are updated on window keydown/keyup for move constrain, etc.
 */

import { create } from 'zustand'
import type { ShortcutsConfig } from '../types'

export interface ModifierKeys {
  shift: boolean
  alt: boolean
  ctrl: boolean
  meta: boolean
}

const DEFAULT_MODIFIER_KEYS: ModifierKeys = {
  shift: false,
  alt: false,
  ctrl: false,
  meta: false,
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
  modifierKeys: ModifierKeys
  setModifierKeys: (keys: Partial<ModifierKeys>) => void
}

export const useViewportShortcutsStore = create<ViewportShortcutsState>()((set) => ({
  viewportShortcuts: { ...DEFAULT_SHORTCUTS },
  modifierKeys: { ...DEFAULT_MODIFIER_KEYS },
  setViewportShortcuts: (partial) =>
    set((state) => ({
      viewportShortcuts: mergeShortcuts(state.viewportShortcuts, partial),
    })),
  resetViewportShortcuts: () =>
    set({ viewportShortcuts: { ...DEFAULT_SHORTCUTS } }),
  setModifierKeys: (keys) =>
    set((state) => ({
      modifierKeys: { ...state.modifierKeys, ...keys },
    })),
}))

/** Get current shortcuts without subscribing (e.g. inside event handlers). */
export function getViewportShortcuts(): ShortcutsConfig {
  return useViewportShortcutsStore.getState().viewportShortcuts
}

/** Get current modifier key state without subscribing (e.g. inside move handler). */
export function getModifierKeys(): ModifierKeys {
  return useViewportShortcutsStore.getState().modifierKeys
}
