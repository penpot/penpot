import type { Gradient } from 'penpot-exporter/types'
import { activeEditorGradient } from '../../renderer/signals/selection'
import { useSignalCoalesced } from '../../renderer/signals/use-signal-coalesced'

export function useGradientFill(): Gradient | null {
  return useSignalCoalesced(activeEditorGradient)
}
