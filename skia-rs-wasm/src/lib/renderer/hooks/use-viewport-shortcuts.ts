/**
 * Hook for apps to read and update viewport shortcut configuration.
 * Returns the current full config and a setter that merges partial overrides.
 */

import { useViewportShortcutsStore } from '../store/shortcuts-store'
import type { ShortcutsConfig } from '../types'

export function useViewportShortcuts(): [
  ShortcutsConfig,
  (partial: Partial<ShortcutsConfig>) => void
] {
  const viewportShortcuts = useViewportShortcutsStore((state) => state.viewportShortcuts)
  const setViewportShortcuts = useViewportShortcutsStore((state) => state.setViewportShortcuts)
  return [viewportShortcuts, setViewportShortcuts]
}
