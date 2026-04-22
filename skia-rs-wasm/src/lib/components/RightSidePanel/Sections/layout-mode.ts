import type { PenpotNode } from 'penpot-exporter/types'
import type { RectLikeNode } from '../../../renderer/properties/panel-utils'

export type LayoutMode = 'flex' | 'grid'

type LayoutFieldsView = {
  layoutFlexDir?: unknown
  layoutGridDir?: unknown
  layoutWrapType?: unknown
  layoutJustifyContent?: unknown
  layoutAlignItems?: unknown
  layoutGap?: unknown
  layoutPadding?: unknown
}

export function getLayoutMode(node: RectLikeNode): LayoutMode | null {
  const n = node as LayoutFieldsView
  if (n.layoutFlexDir) return 'flex'
  if (n.layoutGridDir) return 'grid'
  return null
}

export function modeSwitchPartial(
  mode: LayoutMode | null,
  before: RectLikeNode,
): Partial<PenpotNode> {
  const b = before as LayoutFieldsView
  const patch: Record<string, unknown> = {}

  if (mode == null) {
    patch.layoutFlexDir = null
    patch.layoutGridDir = null
    return patch as Partial<PenpotNode>
  }

  if (mode === 'flex') {
    patch.layoutGridDir = null
    patch.layoutFlexDir = (b.layoutFlexDir as string | undefined) ?? 'row'
    if (b.layoutWrapType == null) patch.layoutWrapType = 'nowrap'
    if (b.layoutJustifyContent == null) patch.layoutJustifyContent = 'start'
    if (b.layoutAlignItems == null) patch.layoutAlignItems = 'start'
    if (b.layoutGap == null) patch.layoutGap = { rowGap: 0, columnGap: 0 }
    if (b.layoutPadding == null)
      patch.layoutPadding = { p1: 0, p2: 0, p3: 0, p4: 0 }
    return patch as Partial<PenpotNode>
  }

  // grid
  patch.layoutFlexDir = null
  patch.layoutGridDir = (b.layoutGridDir as string | undefined) ?? 'row'
  return patch as Partial<PenpotNode>
}
