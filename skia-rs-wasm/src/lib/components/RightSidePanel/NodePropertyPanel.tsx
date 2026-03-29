import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { snapshot } from 'valtio'
import type { Fill, PenpotNode, Stroke } from 'penpot-exporter/types'
import { applyTransformToNode } from '../../renderer/geom/apply-transform-to-node'
import { rotationMatrixAroundPoint, translateMatrix } from '../../renderer/geom/matrix'
import { useSelector } from '@xstate/react'
import { useWorkspaceStore } from '../../renderer/store/workspace-store'
import { useCanvasActor } from '../../renderer/machine/canvas-actor-context'
import { docProxy, getActiveOrSinglePageId } from '../../renderer/store/doc-proxy'
import {
  commitNodePartialUpdate,
  rectLayoutPartial,
} from '../../renderer/properties/commit-node-properties'
import { DEFAULT_FILL, type RectLikeNode } from '../../renderer/properties/panel-utils'
import { Separator } from '@/components/ui/separator'
import { NodeIdentitySection } from './Sections/NodeIdentitySection'
import { NodeLayoutSection } from './Sections/NodeLayoutSection'
import { FillsSection } from './Sections/FillsSection'
import { StrokesSection } from './Sections/StrokesSection'

export interface NodePropertyPanelProps {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}

export function NodePropertyPanel({ nodeId, initialNode, readOnly }: NodePropertyPanelProps) {
  const canvasActor = useCanvasActor()
  const isMoving = useSelector(canvasActor, (s) => s.matches('moving'))
  const isRotating = useSelector(canvasActor, (s) => s.matches('rotating'))
  const rotatePreviewDeltaDeg = useWorkspaceStore((s) => s.rotatePreviewDeltaDeg)

  /** Coalesced to one React update per frame during move (store still updates every pointer event). */
  const [movePreviewCoalesced, setMovePreviewCoalesced] = useState({ x: 0, y: 0 })
  const movePreviewRafRef = useRef(0)

  useEffect(() => {
    if (!isMoving) {
      setMovePreviewCoalesced({ x: 0, y: 0 })
      return
    }
    const d0 = useWorkspaceStore.getState().movePreviewWorldDelta
    setMovePreviewCoalesced({ x: d0.x, y: d0.y })
    const unsub = useWorkspaceStore.subscribe(() => {
      if (movePreviewRafRef.current !== 0) return
      movePreviewRafRef.current = requestAnimationFrame(() => {
        movePreviewRafRef.current = 0
        const d = useWorkspaceStore.getState().movePreviewWorldDelta
        setMovePreviewCoalesced((prev) =>
          prev.x === d.x && prev.y === d.y ? prev : { x: d.x, y: d.y },
        )
      })
    })
    return () => {
      unsub()
      if (movePreviewRafRef.current !== 0) {
        cancelAnimationFrame(movePreviewRafRef.current)
        movePreviewRafRef.current = 0
      }
    }
  }, [isMoving])

  const [name, setName] = useState(() => initialNode.name ?? '')
  const [x, setX] = useState(() => initialNode.x ?? 0)
  const [y, setY] = useState(() => initialNode.y ?? 0)
  const [width, setWidth] = useState(() => initialNode.width ?? 100)
  const [height, setHeight] = useState(() => initialNode.height ?? 100)
  const [rotation, setRotation] = useState(() => initialNode.rotation ?? 0)
  const [fills, setFills] = useState<Fill[]>(() =>
    initialNode.fills ? [...initialNode.fills] : [],
  )
  const [strokes, setStrokes] = useState<Stroke[]>(() =>
    initialNode.strokes ? [...initialNode.strokes] : [],
  )

  // Keep form in sync when the same node is updated from outside (e.g. canvas commit).
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- mirrors external document updates into controlled fields */
    setName(initialNode.name ?? '')
    setX(initialNode.x ?? 0)
    setY(initialNode.y ?? 0)
    setWidth(initialNode.width ?? 100)
    setHeight(initialNode.height ?? 100)
    setRotation(initialNode.rotation ?? 0)
    setFills(initialNode.fills ? [...initialNode.fills] : [])
    setStrokes(initialNode.strokes ? [...initialNode.strokes] : [])
    /* eslint-enable react-hooks/set-state-in-effect */
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
        translateMatrix(movePreviewCoalesced.x, movePreviewCoalesced.y),
      )
    }
    return null
  }, [readOnly, initialNode, isRotating, isMoving, rotatePreviewDeltaDeg, movePreviewCoalesced])

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

  const resolvePageId = useCallback((): string | null => getActiveOrSinglePageId(), [])

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

  const commitStrokes = useCallback(
    async (next: Stroke[]) => {
      if (readOnly) return
      const before = getNodeBefore()
      const pid = resolvePageId()
      if (!before || !pid) return
      await commitNodePartialUpdate(
        nodeId,
        before,
        { strokes: next.length > 0 ? next : undefined },
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

  const onFirstStrokeChange = useCallback(
    (stroke: Stroke) => {
      const next = [...strokes]
      if (next.length === 0) next.push(stroke)
      else next[0] = stroke
      setStrokes(next)
      void commitStrokes(next)
    },
    [strokes, commitStrokes],
  )

  return (
    <>
      {readOnly ? (
        <p className="text-xs text-muted-foreground">
          Root frame is read-only here. Use the canvas to navigate.
        </p>
      ) : null}

      <NodeIdentitySection
        readOnly={readOnly}
        name={name}
        onNameChange={setName}
        onNameCommit={commitName}
      />

      <Separator />

      <NodeLayoutSection
        layoutFieldsDisabled={layoutFieldsDisabled}
        xDisplay={xDisplay}
        yDisplay={yDisplay}
        widthDisplay={widthDisplay}
        heightDisplay={heightDisplay}
        rotationDisplay={rotationDisplay}
        onXChange={setX}
        onYChange={setY}
        onWidthChange={setWidth}
        onHeightChange={setHeight}
        onRotationChange={setRotation}
        onLayoutCommit={commitLayout}
      />

      <FillsSection
        readOnly={readOnly}
        fills={fills}
        onFillChange={onFillChange}
        onAddFill={addFill}
        onClearFills={removeFills}
      />

      <StrokesSection
        readOnly={readOnly}
        strokes={strokes}
        onFirstStrokeChange={onFirstStrokeChange}
      />
    </>
  )
}
