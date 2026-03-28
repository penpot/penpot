/**
 * Right rail: page + node properties driven by document selection (Valtio).
 */

import { useCallback, useMemo, useState } from 'react'
import { useSnapshot } from 'valtio'
import { docProxy, getActiveOrSinglePageId } from '../../renderer/store/doc-proxy'
import { cn } from '@/lib/utils'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { FloatingEditorRail } from '../editor-shell/floating-editor-rail'
import { ROOT_UUID, type RectLikeNode } from '../../renderer/properties/panel-utils'
import { PagePropertyPanel } from './PagePropertyPanel'
import { NodePropertyPanel } from './NodePropertyPanel'

export interface RightSidePanelProps {
  className?: string
}

export function RightSidePanel({ className }: RightSidePanelProps) {
  const doc = useSnapshot(docProxy)
  const selectedIds = useMemo(() => new Set(doc.selectedIds), [doc.selectedIds])

  const [collapsed, setCollapsed] = useState(false)

  const count = selectedIds.size
  const singleId = count === 1 ? Array.from(selectedIds)[0] : null
  const isRoot = singleId === ROOT_UUID

  const resolvePageId = useCallback((): string | null => getActiveOrSinglePageId(), [])

  if (count === 0) {
    const pid = resolvePageId()
    const page = pid ? doc.pageMap.get(pid) : undefined

    return (
      <FloatingEditorRail
        side="right"
        title="Design"
        collapsed={collapsed}
        onCollapsedChange={setCollapsed}
        data-right-side-panel
        className={cn('min-h-0', className)}
      >
        <div className="flex min-h-0 flex-1 flex-col">
          <ScrollArea className="min-h-0 flex-1">
            <div className="space-y-4 p-3">
              {pid && page && <PagePropertyPanel key={pid} pageId={pid} initialPage={page} />}
              <Separator />
              <p className="text-sm text-muted-foreground">Select a layer to view shape properties.</p>
            </div>
          </ScrollArea>
        </div>
      </FloatingEditorRail>
    )
  }

  if (count > 1) {
    return (
      <FloatingEditorRail
        side="right"
        title="Design"
        collapsed={collapsed}
        onCollapsedChange={setCollapsed}
        data-right-side-panel
        className={cn('min-h-0', className)}
      >
        <div className="p-3 text-sm">
          <span className="font-medium">{count} items selected</span>
          <p className="mt-2 text-muted-foreground">Edit one shape at a time.</p>
        </div>
      </FloatingEditorRail>
    )
  }

  if (!singleId) {
    return null
  }

  const readOnly = isRoot
  const currentPage = doc.currentPageId ? doc.pageMap.get(doc.currentPageId) : undefined
  const node = currentPage?.objects[singleId] as RectLikeNode | undefined

  return (
    <FloatingEditorRail
      side="right"
      title="Design"
      collapsed={collapsed}
      onCollapsedChange={setCollapsed}
      data-right-side-panel
      className={cn('min-h-0', className)}
    >
      <div className="flex min-h-0 flex-1 flex-col">
        {singleId && !isRoot && (
          <p
            className="shrink-0 truncate border-b border-border px-3 py-1.5 text-xs text-muted-foreground"
            title={singleId}
          >
            {singleId.slice(0, 8)}…
          </p>
        )}
        <ScrollArea className="min-h-0 flex-1">
          <div className="space-y-4 p-3">
            {node ? (
              <NodePropertyPanel key={singleId} nodeId={singleId} initialNode={node} readOnly={readOnly} />
            ) : null}
          </div>
        </ScrollArea>
      </div>
    </FloatingEditorRail>
  )
}

/** @deprecated Use `RightSidePanel` */
export const ShapePropertiesPanel = RightSidePanel
/** @deprecated Use `RightSidePanelProps` */
export type ShapePropertiesPanelProps = RightSidePanelProps
