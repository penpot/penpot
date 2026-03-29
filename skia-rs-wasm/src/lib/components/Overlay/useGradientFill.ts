import { useMemo } from 'react'
import type { Snapshot } from 'valtio'
import type { Fill, Gradient } from 'penpot-exporter/types'
import { isLinearGradient, isRadialGradient, isAngularGradient, MAX_GRADIENT_STOPS } from '../../renderer/api/constants'
import type { DocState } from '../../renderer/store/doc-proxy'

export function useGradientFill(selectedIds: Set<string>, doc: Snapshot<DocState>): Gradient | null {
  return useMemo(() => {
    if (selectedIds.size !== 1) return null
    const singleId = Array.from(selectedIds)[0]
    const page = doc.currentPageId ? doc.pageMap.get(doc.currentPageId) : undefined
    const fills = singleId ? page?.objects[singleId]?.fills : undefined
    if (!fills?.length) return null
    const gradientFill = fills.find((f: Fill) => isLinearGradient(f) || isRadialGradient(f) || isAngularGradient(f)) ?? null
    if (gradientFill?.fillColorGradient == null) return null
    return {
      ...gradientFill.fillColorGradient,
      stops: gradientFill.fillColorGradient.stops?.slice(0, MAX_GRADIENT_STOPS) ?? [],
    }
  }, [doc, selectedIds])
}
