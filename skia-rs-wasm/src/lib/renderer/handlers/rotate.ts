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
import { updateNode } from '../store/page-crud'
import type { Point } from '@skia-rs-wasm/common'
import type { Matrix } from '@penpot-exporter/types'

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

/** Pure rotation matrix around origin (e=0, f=0). Used for committed node transform; renderer applies center translation. */
function rotationMatrixOrigin(angleDeg: number): Matrix {
  const theta = (angleDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  return {
    a: cos,
    b: sin,
    c: -sin,
    d: cos,
    e: 0,
    f: 0,
  }
}

export function startRotateSelected(initialPosition: Point): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport, selectedIds, selectedNodes } = state

  const selectedId = selectedIds.size === 1 ? Array.from(selectedIds)[0] : null
  const node = selectedNodes?.[0]
  if (
    !renderer ||
    !viewport ||
    !selectedId ||
    !node ||
    node.id !== selectedId ||
    !node.selrect
  )
    return EMPTY

  const sr = node.selrect
  const x = (sr as { x?: number }).x ?? 0
  const y = (sr as { y?: number }).y ?? 0
  const w = (sr as { width?: number }).width ?? 0
  const h = (sr as { height?: number }).height ?? 0
  const cx = x + w / 2
  const cy = y + h / 2

  const initialWorld = screenToWorld(initialPosition.x, initialPosition.y, viewport)
  const initialAngleDeg = angleDegFromCenter(cx, cy, initialWorld.x, initialWorld.y)
  const startRotation = (node as { rotation?: number }).rotation ?? 0

  const stopper = dragStopper()
  const latestRotationDegRef = { current: startRotation }
  const rafScheduledRef = { current: false }
  const modifiersAppliedRef = { current: false }

  const rotateStream = mousePosition$.pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    map((pos) => screenToWorld(pos.x, pos.y, viewport)),
    map((world) => angleDegFromCenter(cx, cy, world.x, world.y)),
    map((currentAngleDeg) => startRotation + (currentAngleDeg - initialAngleDeg)),
    tap((newRotationDeg) => {
      latestRotationDegRef.current = newRotationDeg
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          modifiersAppliedRef.current = true
          const deltaDeg = latestRotationDegRef.current - startRotation
          const matrix = rotationMatrix(cx, cy, deltaDeg)
          renderer.setMoveModifiersAndRender([[selectedId, matrix]])
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
        useWorkspaceStore.getState().setIsRotating(false)
        return
      }
      const finalRotation = latestRotationDegRef.current
      updateNode(selectedId, {
        rotation: finalRotation,
        transform: rotationMatrixOrigin(finalRotation),
      })
        .then(async () => {
          const node = useWorkspaceStore.getState().selectedNodes?.[0]
          if (node?.id === selectedId) await renderer.updateShape(node)
          renderer.cleanModifiers()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          requestAnimationFrame(() => {
            renderer.requestRenderFrame()
          })
          useWorkspaceStore.getState().setIsRotating(false)
        })
        .catch(() => {
          renderer.cleanModifiers()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          requestAnimationFrame(() => {
            renderer.requestRenderFrame()
          })
          useWorkspaceStore.getState().setIsRotating(false)
        })
    }),
    map(() => undefined)
  )

  return merge(rotateStream, commitOnRelease) as Observable<void>
}
