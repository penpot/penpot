/**
 * Hook for apps to read and update viewport shortcut configuration.
 * Returns the current full config and a setter that merges partial overrides.
 */

import { useViewportShortcutsStore } from '../store/viewport-shortcuts-store'
import type { ViewportShortcutsConfig } from '../types'

export function useViewportShortcuts(): [
  ViewportShortcutsConfig,
  (partial: Partial<ViewportShortcutsConfig>) => void
] {
  const viewportShortcuts = useViewportShortcutsStore((state) => state.viewportShortcuts)
  const setViewportShortcuts = useViewportShortcutsStore((state) => state.setViewportShortcuts)
  return [viewportShortcuts, setViewportShortcuts]
}
