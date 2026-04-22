import { useCallback, useState } from 'react'
import type { PenpotNode } from 'penpot-exporter/types'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Separator } from '@/components/ui/separator'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import type { RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import { LayoutFlexBody } from './LayoutFlexBody'
import { LayoutGridBody } from './LayoutGridBody'
import { LayoutModeToggle } from './LayoutModeToggle'
import { getLayoutMode, modeSwitchPartial, type LayoutMode } from './layout-mode'

export interface NodeLayoutSectionProps {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}

export function NodeLayoutSection({ nodeId, initialNode, readOnly }: NodeLayoutSectionProps) {
  const [collapsed, setCollapsed] = useState(false)
  const mode = getLayoutMode(initialNode)

  const onModeChange = useCallback(
    async (next: LayoutMode | null) => {
      if (readOnly || next === mode) return
      const before = getCommittedNodeOnActivePage(nodeId)
      const pid = getActiveOrSinglePageId()
      if (!before || !pid) return
      const partial = modeSwitchPartial(next, before as RectLikeNode) as Partial<PenpotNode>
      await commitNodePartialUpdate(nodeId, before, partial, pid)
    },
    [nodeId, readOnly, mode],
  )

  return (
    <>
      <Separator />
      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2 py-0.5">
          <button
            type="button"
            className="flex min-h-8 flex-1 items-center gap-1 text-left text-xs font-medium tracking-wide text-muted-foreground uppercase hover:text-foreground"
            onClick={() => setCollapsed((c) => !c)}
            aria-expanded={!collapsed}
          >
            {collapsed ? (
              <ChevronRight className="size-3.5 shrink-0" aria-hidden />
            ) : (
              <ChevronDown className="size-3.5 shrink-0" aria-hidden />
            )}
            Layout
          </button>
          <LayoutModeToggle mode={mode} onChange={onModeChange} disabled={readOnly} />
        </div>

        {!collapsed && mode === 'flex' && (
          <LayoutFlexBody nodeId={nodeId} initialNode={initialNode} readOnly={readOnly} />
        )}
        {!collapsed && mode === 'grid' && <LayoutGridBody />}
      </div>
    </>
  )
}
