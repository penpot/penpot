/**
 * Rotation handler
 * When the user drags from the rotation hit area, the selected shape rotates so its angle follows
 * the cursor (angle from selection center to cursor). Preview via setMoveModifiers(rotation matrix);
 * commit node.rotation on pointer up.
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { updatePage } from '../store/page-crud'
import { makeSelrect } from '@skia-rs-wasm/common'
import type { Point } from '@skia-rs-wasm/common'
import type { Matrix, PenpotNode } from 'penpot-exporter'

function screenToWorld(
  sx: number,
  sy: number,
  viewport: { panX: number; panY: number; zoom: number }
): { x: number; y: number } {
  return {
    x: viewport.panX + sx / viewport.zoom,
    y: viewport.panY + sy / viewport.zoom,
  }
}

function angleDegFromCenter(cx: number, cy: number, wx: number, wy: number): number {
  return Math.atan2(wy - cy, wx - cx) * (180 / Math.PI)
}

/** Build 2D affine rotation matrix around center (cx, cy). Angle in degrees. Used for drag modifier. */
function rotationMatrix(cx: number, cy: number, angleDeg: number): Matrix {
  const theta = (angleDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  return {
    a: cos,
    b: sin,
    c: -sin,
    d: cos,
    e: cx * (1 - cos) + cy * sin,
    f: cy * (1 - cos) - cx * sin,
  }
}

/** Rotate point (px, py) around (cx, cy) by angleDeg; returns new point. */
function rotatePointAround(
  px: number,
  py: number,
  cx: number,
  cy: number,
  angleDeg: number
): { x: number; y: number } {
  const theta = (angleDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  const dx = px - cx
  const dy = py - cy
  return {
    x: cx + dx * cos - dy * sin,
    y: cy + dx * sin + dy * cos,
  }
}

export function startRotateSelected(initialPosition: Point): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport, selectedIds, selectedNodes, wasmSelectionRect } = state

  if (!renderer || !viewport || selectedIds.size < 1) return EMPTY

  const ids = Array.from(selectedIds)
  const isSingle = ids.length === 1
  const singleNode = selectedNodes?.[0]

  // Single selection: require valid node with selrect (current behavior)
  if (isSingle) {
    if (!singleNode || singleNode.id !== ids[0] || !singleNode.selrect) return EMPTY
  }

  // Rotation center: selection rect center (works for single and group)
  let cx: number
  let cy: number
  const startRotations = new Map<string, number>()

  if (isSingle && singleNode?.selrect) {
    const sr = singleNode.selrect
    const x = (sr as { x?: number }).x ?? 0
    const y = (sr as { y?: number }).y ?? 0
    const w = (sr as { width?: number }).width ?? 0
    const h = (sr as { height?: number }).height ?? 0
    cx = x + w / 2
    cy = y + h / 2
    startRotations.set(ids[0], (singleNode as { rotation?: number }).rotation ?? 0)
  } else {
    if (!wasmSelectionRect) return EMPTY
    cx = wasmSelectionRect.center.x
    cy = wasmSelectionRect.center.y
    selectedNodes?.forEach((n) => {
      if (n && ids.includes(n.id))
        startRotations.set(n.id, (n as { rotation?: number }).rotation ?? 0)
    })
  }

  const initialWorld = screenToWorld(initialPosition.x, initialPosition.y, viewport)
  const initialAngleDeg = angleDegFromCenter(cx, cy, initialWorld.x, initialWorld.y)

  const stopper = dragStopper()
  const latestDeltaDegRef = { current: 0 }
  const rafScheduledRef = { current: false }
  const modifiersAppliedRef = { current: false }

  const rotateStream = mousePosition$.pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    map((pos) => screenToWorld(pos.x, pos.y, viewport)),
    map((world) => angleDegFromCenter(cx, cy, world.x, world.y)),
    map((currentAngleDeg) => currentAngleDeg - initialAngleDeg),
    tap((deltaDeg) => {
      latestDeltaDegRef.current = deltaDeg
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          modifiersAppliedRef.current = true
          const delta = latestDeltaDegRef.current
          const matrix = rotationMatrix(cx, cy, delta)
          const entries: Array<[string, Matrix]> = ids.map((id) => [id, matrix])
          renderer.setMoveModifiersAndRender(entries)
          useWorkspaceStore.getState().refreshWasmSelectionRect()
        })
      }
    }),
    map(() => undefined),
    takeUntil(stopper)
  )

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      if (!modifiersAppliedRef.current) {
        const store = useWorkspaceStore.getState()
        store.setRotationCorner(null)
        store.setIsRotating(false)
        return
      }
      const deltaDeg = latestDeltaDegRef.current
      const store = useWorkspaceStore.getState()
      const nodes = store.selectedNodes ?? []
      const pageId = store.pageId
      const documentModel = store.documentModel
      if (!pageId || !documentModel) {
        const storeEarly = useWorkspaceStore.getState()
        storeEarly.setRotationCorner(null)
        storeEarly.setIsRotating(false)
        return
      }
      const page = documentModel.getPage(pageId)
      if (!page) {
        const storeNoPage = useWorkspaceStore.getState()
        storeNoPage.setRotationCorner(null)
        storeNoPage.setIsRotating(false)
        return
      }

      const payloadsById: Record<string, Partial<PenpotNode>> = {}
      const theta = (deltaDeg * Math.PI) / 180
      const cosD = Math.cos(theta)
      const sinD = Math.sin(theta)
      for (const id of ids) {
        const node = nodes.find((n) => n.id === id)
        if (!node) continue
        const hasPosition =
          typeof (node as { x?: number }).x === 'number' && typeof (node as { y?: number }).y === 'number'
        const sr = node.selrect
        const nx = (sr as { x?: number })?.x ?? (node as { x?: number }).x ?? 0
        const ny = (sr as { y?: number })?.y ?? (node as { y?: number }).y ?? 0
        const nw = (sr as { width?: number })?.width ?? (node as { width?: number }).width ?? 0
        const nh = (sr as { height?: number })?.height ?? (node as { height?: number }).height ?? 0
        const nodeCx = nx + nw / 2
        const nodeCy = ny + nh / 2
        const rotated = rotatePointAround(nodeCx, nodeCy, cx, cy, deltaDeg)
        const newX = rotated.x - nw / 2
        const newY = rotated.y - nh / 2
        // Compose rotation delta with existing transform to correctly handle non-pure-rotation
        // transforms (e.g. after a resize). Using T' = R(delta) * T avoids the bug where
        // rotationMatrixOrigin(startRot + delta) overwrites a shear/scale transform with a pure rotation.
        const T = (node as { transform?: { a: number; b: number; c: number; d: number } }).transform ?? { a: 1, b: 0, c: 0, d: 1 }
        const newTransform: Matrix = {
          a: cosD * T.a - sinD * T.b,
          b: sinD * T.a + cosD * T.b,
          c: cosD * T.c - sinD * T.d,
          d: sinD * T.c + cosD * T.d,
          e: 0,
          f: 0,
        }
        const finalRotation = Math.atan2(newTransform.b, newTransform.a) * (180 / Math.PI)
        const centerX = newX + nw / 2
        const centerY = newY + nh / 2
        const localCorners: Point[] = [
          { x: -nw / 2, y: -nh / 2 },
          { x: nw / 2, y: -nh / 2 },
          { x: nw / 2, y: nh / 2 },
          { x: -nw / 2, y: nh / 2 },
        ]
        const points: Point[] = localCorners.map((p) => ({
          x: centerX + newTransform.a * p.x + newTransform.c * p.y,
          y: centerY + newTransform.b * p.x + newTransform.d * p.y,
        }))
        const payload: Partial<PenpotNode> = {
          rotation: finalRotation,
          transform: newTransform,
          selrect: makeSelrect(newX, newY, nw, nh),
          points,
        }
        if (hasPosition) (payload as Record<string, unknown>).x = newX
        if (hasPosition) (payload as Record<string, unknown>).y = newY
        payloadsById[id] = payload
      }

      const updatedChildren = (page.children ?? []).map((n: PenpotNode) =>
        n.id && payloadsById[n.id] ? { ...n, ...payloadsById[n.id] } : n
      )
      const updatedPage = { ...page, pageId, children: updatedChildren }

      updatePage(updatedPage)
        .then(async () => {
          const storeAfter = useWorkspaceStore.getState()
          const updatedNodes = storeAfter.selectedNodes ?? []
          for (const id of ids) {
            const node = updatedNodes.find((n) => n.id === id)
            if (node) await renderer.updateShape(node)
          }
          renderer.cleanModifiers()
          storeAfter.refreshWasmSelectionRect()
          requestAnimationFrame(() => renderer.requestRenderFrame())
          storeAfter.setRotationCorner(null)
          storeAfter.setIsRotating(false)
        })
        .catch(() => {
          renderer.cleanModifiers()
          const storeCatch = useWorkspaceStore.getState()
          storeCatch.refreshWasmSelectionRect()
          requestAnimationFrame(() => renderer.requestRenderFrame())
          storeCatch.setRotationCorner(null)
          storeCatch.setIsRotating(false)
        })
    }),
    map(() => undefined)
  )

  return merge(rotateStream, commitOnRelease) as Observable<void>
}
