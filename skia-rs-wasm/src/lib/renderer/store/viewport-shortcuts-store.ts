/**
 * Zustand store for viewport shortcut configuration.
 * Apps can override pan, zoom, reset keys and mouse behavior via useViewportShortcuts() or setViewportShortcuts.
 */

import { create } from 'zustand'
import type { ViewportShortcutsConfig } from '../types'

export const DEFAULT_VIEWPORT_SHORTCUTS: ViewportShortcutsConfig = {
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
  current: ViewportShortcutsConfig,
  partial: Partial<ViewportShortcutsConfig>
): ViewportShortcutsConfig {
  return {
    ...current,
    ...partial,
    zoomInKeys: partial.zoomInKeys ?? current.zoomInKeys,
    zoomOutKeys: partial.zoomOutKeys ?? current.zoomOutKeys,
    resetKeys: partial.resetKeys ?? current.resetKeys,
  }
}

export interface ViewportShortcutsState {
  viewportShortcuts: ViewportShortcutsConfig
  setViewportShortcuts: (partial: Partial<ViewportShortcutsConfig>) => void
  resetViewportShortcuts: () => void
}

export const useViewportShortcutsStore = create<ViewportShortcutsState>()((set) => ({
  viewportShortcuts: { ...DEFAULT_VIEWPORT_SHORTCUTS },
  setViewportShortcuts: (partial) =>
    set((state) => ({
      viewportShortcuts: mergeShortcuts(state.viewportShortcuts, partial),
    })),
  resetViewportShortcuts: () =>
    set({ viewportShortcuts: { ...DEFAULT_VIEWPORT_SHORTCUTS } }),
}))

/** Get current shortcuts without subscribing (e.g. inside event handlers). */
export function getViewportShortcuts(): ViewportShortcutsConfig {
  return useViewportShortcutsStore.getState().viewportShortcuts
}
