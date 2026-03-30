/**
 * Right rail: page + node properties driven by document selection (Valtio).
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSnapshot } from 'valtio'
import type { Fill } from 'penpot-exporter/types'
import { docProxy, getActiveOrSinglePageId } from '../../renderer/store/doc-proxy'
import { cn } from '@/lib/utils'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { FloatingEditorRail } from '../EditorShell/floating-editor-rail'
import { ROOT_UUID, type RectLikeNode } from '../../renderer/properties/panel-utils'
import { PagePropertyPanel } from './PagePropertyPanel'
import { NodePropertyPanel } from './NodePropertyPanel'
import { FillEditorContext, type FillEditorContextValue } from './fill-editor-context'
import { FloatingFillEditorPanel } from './FloatingFillEditorPanel'
import { StrokeEditorContext, type StrokeEditorContextValue } from './StrokeEditorContext'
import { FloatingStrokeEditorPanel } from './FloatingStrokeEditorPanel'

export interface RightSidePanelProps {
  className?: string
}

export function RightSidePanel({ className }: RightSidePanelProps) {
  const doc = useSnapshot(docProxy)
  const selectedIds = useMemo(() => new Set(doc.selectedIds), [doc.selectedIds])

  const [collapsed, setCollapsed] = useState(false)

  // Fill editor floating panel state
  const [activeFillIndex, setActiveFillIndex] = useState<number | null>(null)
  const [activeFill, setActiveFill] = useState<Fill | null>(null)
  const [fillAnchorY, setFillAnchorY] = useState(12)
  const onFillChangeRef = useRef<((fill: Fill) => void) | null>(null)

  const closeFillEditor = useCallback(() => {
    setActiveFillIndex(null)
    setActiveFill(null)
    onFillChangeRef.current = null
  }, [])

  // Stroke editor floating panel state
  const [activeStrokeIndex, setActiveStrokeIndex] = useState<number | null>(null)
  const [activeStrokeFill, setActiveStrokeFill] = useState<Fill | null>(null)
  const [strokeAnchorY, setStrokeAnchorY] = useState(12)
  const onStrokeChangeRef = useRef<((fill: Fill) => void) | null>(null)

  const closeStrokeEditor = useCallback(() => {
    setActiveStrokeIndex(null)
    setActiveStrokeFill(null)
    onStrokeChangeRef.current = null
  }, [])

  // Only one editor panel open at a time: each open closes the other
  const openFillEditor = useCallback(
    (index: number, fill: Fill, y: number, onChange: (fill: Fill) => void) => {
      closeStrokeEditor()
      setActiveFillIndex(index)
      setActiveFill(fill)
      setFillAnchorY(y)
      onFillChangeRef.current = (next: Fill) => {
        setActiveFill(next)
        onChange(next)
      }
    },
    [closeStrokeEditor],
  )

  const openStrokeEditor = useCallback(
    (index: number, fill: Fill, y: number, onChange: (fill: Fill) => void) => {
      closeFillEditor()
      setActiveStrokeIndex(index)
      setActiveStrokeFill(fill)
      setStrokeAnchorY(y)
      onStrokeChangeRef.current = (next: Fill) => {
        setActiveStrokeFill(next)
        onChange(next)
      }
    },
    [closeFillEditor],
  )

  // Close both editors when selection changes
  useEffect(() => {
    closeFillEditor()
    closeStrokeEditor()
  }, [selectedIds, closeFillEditor, closeStrokeEditor])

  // Close both editors when right panel collapses
  const handleCollapsedChange = useCallback(
    (next: boolean) => {
      setCollapsed(next)
      if (next) {
        closeFillEditor()
        closeStrokeEditor()
      }
    },
    [closeFillEditor, closeStrokeEditor],
  )

  const fillEditorCtx = useMemo<FillEditorContextValue>(
    () => ({
      activeFillIndex,
      activeFill,
      anchorY: fillAnchorY,
      openEditor: openFillEditor,
      closeEditor: closeFillEditor,
      onChangeRef: onFillChangeRef,
    }),
    [activeFillIndex, activeFill, fillAnchorY, openFillEditor, closeFillEditor],
  )

  const strokeEditorCtx = useMemo<StrokeEditorContextValue>(
    () => ({
      activeStrokeIndex,
      activeStrokeFill,
      anchorY: strokeAnchorY,
      openEditor: openStrokeEditor,
      closeEditor: closeStrokeEditor,
      onChangeRef: onStrokeChangeRef,
    }),
    [activeStrokeIndex, activeStrokeFill, strokeAnchorY, openStrokeEditor, closeStrokeEditor],
  )

  const count = selectedIds.size
  const singleId = count === 1 ? Array.from(selectedIds)[0] : null
  const isRoot = singleId === ROOT_UUID

  const resolvePageId = useCallback((): string | null => getActiveOrSinglePageId(), [])

  if (count === 0) {
    const pid = resolvePageId()
    const page = pid ? doc.pageMap.get(pid) : undefined

    return (
      <FillEditorContext.Provider value={fillEditorCtx}>
        <StrokeEditorContext.Provider value={strokeEditorCtx}>
          <FloatingFillEditorPanel />
          <FloatingStrokeEditorPanel />
          <FloatingEditorRail
            side="right"
            title="Design"
            collapsed={collapsed}
            onCollapsedChange={handleCollapsedChange}
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
        </StrokeEditorContext.Provider>
      </FillEditorContext.Provider>
    )
  }

  if (count > 1) {
    return (
      <FillEditorContext.Provider value={fillEditorCtx}>
        <StrokeEditorContext.Provider value={strokeEditorCtx}>
          <FloatingFillEditorPanel />
          <FloatingStrokeEditorPanel />
          <FloatingEditorRail
            side="right"
            title="Design"
            collapsed={collapsed}
            onCollapsedChange={handleCollapsedChange}
            data-right-side-panel
            className={cn('min-h-0', className)}
          >
            <div className="p-3 text-sm">
              <span className="font-medium">{count} items selected</span>
              <p className="mt-2 text-muted-foreground">Edit one shape at a time.</p>
            </div>
          </FloatingEditorRail>
        </StrokeEditorContext.Provider>
      </FillEditorContext.Provider>
    )
  }

  if (!singleId) {
    return null
  }

  const readOnly = isRoot
  const currentPage = doc.currentPageId ? doc.pageMap.get(doc.currentPageId) : undefined
  const node = currentPage?.objects[singleId] as RectLikeNode | undefined

  return (
    <FillEditorContext.Provider value={fillEditorCtx}>
      <StrokeEditorContext.Provider value={strokeEditorCtx}>
        <FloatingFillEditorPanel />
        <FloatingStrokeEditorPanel />
        <FloatingEditorRail
          side="right"
          title="Design"
          collapsed={collapsed}
          onCollapsedChange={handleCollapsedChange}
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
      </StrokeEditorContext.Provider>
    </FillEditorContext.Provider>
  )
}

/** @deprecated Use `RightSidePanel` */
export const ShapePropertiesPanel = RightSidePanel
/** @deprecated Use `RightSidePanelProps` */
export type ShapePropertiesPanelProps = RightSidePanelProps
