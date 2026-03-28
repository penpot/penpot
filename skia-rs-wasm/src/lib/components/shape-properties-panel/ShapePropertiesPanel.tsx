/**
 * Canvas-driven properties: floating Design rail; empty hint when nothing selected.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { snapshot, useSnapshot } from 'valtio'
import type { Fill, PenpotNode } from 'penpot-exporter/types'
import { applyTransformToNode } from '../../renderer/geom/apply-transform-to-node'
import { rotationMatrixAroundPoint, translateMatrix } from '../../renderer/geom/matrix'
import type { IndexedPage } from '../../worker/types'
import { useWorkspaceStore } from '../../renderer/store/workspace-store'
import { docProxy, getActiveOrSinglePageId } from '../../renderer/store/doc-proxy'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { FillEditor } from '../fill-editor/FillEditor'
import { FloatingEditorRail } from '../editor-shell/floating-editor-rail'
import { commitNodePartialUpdate, rectLayoutPartial } from './commit-node-properties'
import { commitPageMetadataUpdate } from './commit-page-properties'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

const DEFAULT_FILL: Fill = { fillColor: '#3B82F6', fillOpacity: 1 }

function normalizeHex(input: string): string {
  let s = input.trim()
  if (!s.startsWith('#')) s = `#${s}`
  if (/^#[0-9A-Fa-f]{3}$/.test(s)) {
    const r = s[1]
    const g = s[2]
    const b = s[3]
    s = `#${r}${r}${g}${g}${b}${b}`
  }
  if (/^#[0-9A-Fa-f]{6}$/.test(s)) return s
  return '#FFFFFF'
}

type RectLikeNode = PenpotNode & { x?: number; y?: number; width?: number; height?: number }

function PagePropertiesForm({ pageId, initialPage }: { pageId: string; initialPage: IndexedPage }) {
  const [pageName, setPageName] = useState(() => initialPage.name ?? 'Page')
  const [pageBg, setPageBg] = useState(() => initialPage.background ?? '#FFFFFF')

  const commitPageName = useCallback(async () => {
    const page = docProxy.pageMap.get(pageId)
    if (!page) return
    const trimmed = pageName.trim()
    if (trimmed === (page.name ?? '')) return
    await commitPageMetadataUpdate(pageId, page, { name: trimmed || 'Page' })
  }, [pageName, pageId])

  const commitPageBackground = useCallback(async () => {
    const page = docProxy.pageMap.get(pageId)
    if (!page) return
    const next = normalizeHex(pageBg)
    if (next === (page.background ?? '#FFFFFF')) return
    await commitPageMetadataUpdate(pageId, page, { background: next })
  }, [pageBg, pageId])

  const onPageBgColorPick = useCallback(
    (hex: string) => {
      setPageBg(hex)
      const page = docProxy.pageMap.get(pageId)
      if (!page) return
      void commitPageMetadataUpdate(pageId, page, { background: hex })
    },
    [pageId],
  )

  return (
    <>
      <div className="space-y-2">
        <Label htmlFor="sp-page-name">Page name</Label>
        <Input
          id="sp-page-name"
          value={pageName}
          onChange={(e) => setPageName(e.target.value)}
          onBlur={() => void commitPageName()}
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="sp-page-bg">Background</Label>
        <div className="flex min-w-0 items-center gap-2">
          <input
            id="sp-page-bg"
            type="color"
            className="h-8 w-10 shrink-0 cursor-pointer rounded border border-border bg-transparent p-0.5"
            value={normalizeHex(pageBg)}
            onChange={(e) => onPageBgColorPick(e.target.value)}
            aria-label="Background color"
          />
          <Input
            className="h-8 min-w-0 flex-1 font-mono text-xs"
            value={pageBg}
            onChange={(e) => setPageBg(e.target.value)}
            onBlur={() => void commitPageBackground()}
            placeholder="#FFFFFF"
          />
        </div>
      </div>
    </>
  )
}

function ShapePropertiesForm({
  nodeId,
  initialNode,
  readOnly,
}: {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}) {
  const isMoving = useWorkspaceStore((s) => s.isMoving)
  const isRotating = useWorkspaceStore((s) => s.isRotating)
  const rotatePreviewDeltaDeg = useWorkspaceStore((s) => s.rotatePreviewDeltaDeg)
  const movePreviewWorldDelta = useWorkspaceStore((s) => s.movePreviewWorldDelta)

  const [name, setName] = useState(() => initialNode.name ?? '')
  const [x, setX] = useState(() => initialNode.x ?? 0)
  const [y, setY] = useState(() => initialNode.y ?? 0)
  const [width, setWidth] = useState(() => initialNode.width ?? 100)
  const [height, setHeight] = useState(() => initialNode.height ?? 100)
  const [rotation, setRotation] = useState(() => initialNode.rotation ?? 0)
  const [fills, setFills] = useState<Fill[]>(() =>
    initialNode.fills ? [...initialNode.fills] : [],
  )

  useEffect(() => {
    setName(initialNode.name ?? '')
    setX(initialNode.x ?? 0)
    setY(initialNode.y ?? 0)
    setWidth(initialNode.width ?? 100)
    setHeight(initialNode.height ?? 100)
    setRotation(initialNode.rotation ?? 0)
    setFills(initialNode.fills ? [...initialNode.fills] : [])
  }, [initialNode])

  const liveLayoutPartial = useMemo((): Partial<PenpotNode> | null => {
    if (readOnly) return null
    const node = initialNode as PenpotNode
    if (isRotating) {
      const sr = node.selrect as
        | { x?: number; y?: number; width?: number; height?: number }
        | undefined
      if (!sr) return null
      const x0 = sr.x ?? 0
      const y0 = sr.y ?? 0
      const w0 = sr.width ?? 0
      const h0 = sr.height ?? 0
      if (w0 <= 0 || h0 <= 0) return null
      const cx = x0 + w0 / 2
      const cy = y0 + h0 / 2
      return applyTransformToNode(node, rotationMatrixAroundPoint(cx, cy, rotatePreviewDeltaDeg))
    }
    if (isMoving) {
      return applyTransformToNode(
        node,
        translateMatrix(movePreviewWorldDelta.x, movePreviewWorldDelta.y),
      )
    }
    return null
  }, [
    readOnly,
    initialNode,
    isRotating,
    isMoving,
    rotatePreviewDeltaDeg,
    movePreviewWorldDelta,
  ])

  const xDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.x === 'number' ? liveLayoutPartial.x : x
  const yDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.y === 'number' ? liveLayoutPartial.y : y
  const widthDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.width === 'number'
      ? liveLayoutPartial.width
      : width
  const heightDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.height === 'number'
      ? liveLayoutPartial.height
      : height
  const rotationDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.rotation === 'number'
      ? liveLayoutPartial.rotation
      : rotation

  const layoutFieldsDisabled = readOnly || isMoving || isRotating

  const resolvePageId = useCallback((): string | null => {
    return getActiveOrSinglePageId()
  }, [])

  const getNodeBefore = useCallback((): PenpotNode | null => {
    const snap = snapshot(docProxy)
    const page = snap.currentPageId ? snap.pageMap.get(snap.currentPageId) : undefined
    return (page?.objects[nodeId] as PenpotNode | undefined) ?? null
  }, [nodeId])

  const commitLayout = useCallback(async () => {
    if (readOnly) return
    const before = getNodeBefore()
    const pid = resolvePageId()
    if (!before || !pid) return
    const partial = rectLayoutPartial(x, y, width, height, rotation)
    await commitNodePartialUpdate(nodeId, before, partial, pid)
  }, [readOnly, getNodeBefore, resolvePageId, x, y, width, height, rotation, nodeId])

  const commitName = useCallback(async () => {
    if (readOnly) return
    const before = getNodeBefore()
    const pid = resolvePageId()
    if (!before || !pid) return
    const trimmed = name.trim()
    if (trimmed === (before.name ?? '')) return
    await commitNodePartialUpdate(nodeId, before, { name: trimmed || 'Shape' }, pid)
  }, [readOnly, getNodeBefore, resolvePageId, name, nodeId])

  const commitFills = useCallback(
    async (next: Fill[]) => {
      if (readOnly) return
      const before = getNodeBefore()
      const pid = resolvePageId()
      if (!before || !pid) return
      await commitNodePartialUpdate(
        nodeId,
        before,
        { fills: next.length > 0 ? next : undefined },
        pid,
      )
    },
    [readOnly, getNodeBefore, resolvePageId, nodeId],
  )

  const onFillChange = useCallback(
    (fill: Fill) => {
      const next = [...fills]
      if (next.length === 0) next.push(fill)
      else next[0] = fill
      setFills(next)
      void commitFills(next)
    },
    [fills, commitFills],
  )

  const addFill = useCallback(() => {
    const next = [...fills, DEFAULT_FILL]
    setFills(next)
    void commitFills(next)
  }, [fills, commitFills])

  const removeFills = useCallback(() => {
    setFills([])
    void commitFills([])
  }, [commitFills])

  return (
    <>
      {readOnly && (
        <p className="text-xs text-muted-foreground">
          Root frame is read-only here. Use the canvas to navigate.
        </p>
      )}

      <div className="space-y-2">
        <Label htmlFor="sp-name">Name</Label>
        <Input
          id="sp-name"
          value={name}
          disabled={readOnly}
          onChange={(e) => setName(e.target.value)}
          onBlur={() => void commitName()}
        />
      </div>

      <Separator />

      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="sp-x">X</Label>
          <Input
            id="sp-x"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(xDisplay) ? xDisplay : 0}
            onChange={(e) => setX(parseFloat(e.target.value) || 0)}
            onBlur={() => void commitLayout()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="sp-y">Y</Label>
          <Input
            id="sp-y"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(yDisplay) ? yDisplay : 0}
            onChange={(e) => setY(parseFloat(e.target.value) || 0)}
            onBlur={() => void commitLayout()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="sp-w">W</Label>
          <Input
            id="sp-w"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(widthDisplay) ? widthDisplay : 0}
            onChange={(e) => setWidth(Math.max(1, parseFloat(e.target.value) || 1))}
            onBlur={() => void commitLayout()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="sp-h">H</Label>
          <Input
            id="sp-h"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(heightDisplay) ? heightDisplay : 0}
            onChange={(e) => setHeight(Math.max(1, parseFloat(e.target.value) || 1))}
            onBlur={() => void commitLayout()}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="sp-rot">Rotation (deg)</Label>
        <Input
          id="sp-rot"
          type="number"
          disabled={layoutFieldsDisabled}
          value={Number.isFinite(rotationDisplay) ? rotationDisplay : 0}
          onChange={(e) => setRotation(parseFloat(e.target.value) || 0)}
          onBlur={() => void commitLayout()}
        />
      </div>

      <Separator />

      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <Label>Fill</Label>
          {!readOnly && (
            <div className="flex gap-1">
              {fills.length === 0 ? (
                <Button type="button" variant="outline" size="sm" onClick={addFill}>
                  Add fill
                </Button>
              ) : (
                <Button type="button" variant="ghost" size="sm" onClick={removeFills}>
                  Clear
                </Button>
              )}
            </div>
          )}
        </div>
        {!readOnly && fills.length > 0 && <FillEditor fill={fills[0]!} onChange={onFillChange} />}
        {readOnly && fills.length > 0 && (
          <p className="text-xs text-muted-foreground">Fill present (read-only)</p>
        )}
      </div>
    </>
  )
}

export interface ShapePropertiesPanelProps {
  className?: string
}

export function ShapePropertiesPanel({ className }: ShapePropertiesPanelProps) {
  const selectedIds = useWorkspaceStore((s) => s.selectedIds)
  const doc = useSnapshot(docProxy)

  const [collapsed, setCollapsed] = useState(false)

  const count = selectedIds.size
  const singleId = count === 1 ? Array.from(selectedIds)[0] : null
  const isRoot = singleId === ROOT_UUID

  const resolvePageId = useCallback((): string | null => {
    return getActiveOrSinglePageId()
  }, [])

  if (count === 0) {
    const pid = resolvePageId()
    const page = pid ? doc.pageMap.get(pid) : undefined

    return (
      <FloatingEditorRail
        side="right"
        title="Design"
        collapsed={collapsed}
        onCollapsedChange={setCollapsed}
        data-shape-properties-panel
        className={cn('min-h-0', className)}
      >
        <div className="flex min-h-0 flex-1 flex-col">
          <ScrollArea className="min-h-0 flex-1">
            <div className="space-y-4 p-3">
              {pid && page && (
                <PagePropertiesForm key={pid} pageId={pid} initialPage={page} />
              )}
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
        data-shape-properties-panel
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
      data-shape-properties-panel
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
              <ShapePropertiesForm
                key={singleId}
                nodeId={singleId}
                initialNode={node}
                readOnly={readOnly}
              />
            ) : null}
          </div>
        </ScrollArea>
      </div>
    </FloatingEditorRail>
  )
}
