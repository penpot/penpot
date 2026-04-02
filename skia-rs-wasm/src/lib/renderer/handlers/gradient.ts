/**
 * Gradient drag handler.
 * Routes gradient handle dragging through the same XState → RxJS pipeline as move/resize/rotate.
 * During drag:
 *   - SVG overlay handles update via direct `activeEditorGradient` signal write (1 RAF hop).
 *   - WASM canvas shape updates via fill modifier + throttled render at ~60 Hz.
 * On release: fill modifiers are cleaned and the final gradient is committed to the document.
 */

import { EMPTY, Observable, merge } from 'rxjs'
import { filter, map, take, takeUntil, tap } from 'rxjs/operators'
import { pointerPos, signalToObservable, viewport as viewportSignal } from '../signals/pointer'
import { activeEditorGradient, activeEditorOnChange, activeEditorTarget, wasmSelectionRect } from '../signals/selection'
import { dragStopper } from '../streams/drag-stopper'
import { screenToWorld } from '../viewport'
import { useWorkspaceStore } from '../store/workspace-store'
import { getActiveOrSinglePageId, getPage } from '../store/doc-proxy'
import { getSelectedIdsSet } from '../store/document-selection'
import { DRAG_RENDER_INTERVAL_MS } from './drag-render-interval'
import type { Point } from '../types'
import type { Fill, Gradient, Matrix } from 'penpot-exporter/types'

export type GradientHandleKind = 'start' | 'end' | 'width'

// Inverse of the shape's local-to-viewport affine transform.
// Mirrors GradientOverlay.viewportToLocal — inlined here since it is only needed in this handler.
function viewportToLocal(
  vp: { x: number; y: number },
  center: { x: number; y: number },
  transform: Matrix,
): { x: number; y: number } {
  const { a, b, c, d } = transform
  const det = a * d - b * c
  if (Math.abs(det) < 1e-12) return { x: 0, y: 0 }
  const dx = vp.x - center.x
  const dy = vp.y - center.y
  return {
    x: (d * dx - c * dy) / det,
    y: (-b * dx + a * dy) / det,
  }
}

// Extracted from GradientOverlay.handlePointerMove — pure coordinate math, no DOM access.
function computePatch(
  handle: GradientHandleKind,
  pt: { x: number; y: number }, // normalised gradient coords in [0, 1]
  current: Gradient,
): Partial<Pick<Gradient, 'startX' | 'startY' | 'endX' | 'endY' | 'width'>> {
  if (handle === 'start') {
    if (current.type === 'linear') {
      return { startX: pt.x, startY: pt.y }
    }
    // Radial / angular: translate both endpoints by the same delta to preserve direction.
    const dx = pt.x - current.startX
    const dy = pt.y - current.startY
    return { startX: pt.x, startY: pt.y, endX: current.endX + dx, endY: current.endY + dy }
  }

  if (handle === 'end') {
    return { endX: pt.x, endY: pt.y }
  }

  // handle === 'width': perpendicular distance from gradient vector, normalised by its length.
  const cx = current.startX
  const cy = current.startY
  const gvx = current.endX - cx
  const gvy = current.endY - cy
  const gLen = Math.hypot(gvx, gvy) || 1
  const pvx = pt.x - cx
  const pvy = pt.y - cy
  const perpDist = Math.abs(pvx * (-gvy / gLen) + pvy * (gvx / gLen))
  return { width: Math.max(0.01, perpDist / gLen) }
}

export function startGradientDrag(
  handle: GradientHandleKind,
  _initialPosition: Point,
): Observable<void> {
  const vp = viewportSignal.peek()
  const selRect = wasmSelectionRect.peek()

  if (!vp || !selRect) return EMPTY

  const { renderer } = useWorkspaceStore.getState()
  if (!renderer) return EMPTY

  const selectedIds = getSelectedIdsSet()
  const shapeId = selectedIds.size === 1 ? Array.from(selectedIds)[0] : null
  const pageId = getActiveOrSinglePageId()
  const editorTarget = activeEditorTarget.peek()

  if (!shapeId || !pageId || !editorTarget) return EMPTY

  const isFill = editorTarget.kind === 'fill'
  const fillIndex = isFill ? editorTarget.index : -1

  // Snapshot shape fills at drag start (only needed for fill drags)
  let baseFills: Fill[] = []
  if (isFill) {
    const page = getPage(pageId)
    const shape = page?.objects[shapeId] as { fills?: Fill[] } | undefined
    baseFills = shape?.fills ?? []
  }

  const { center, transform, width: selW, height: selH } = selRect
  let latestGradient: Gradient | null = activeEditorGradient.peek()
  let lastRenderTs = 0

  const stopper = dragStopper()

  const dragStream = signalToObservable(pointerPos).pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    // screen → world (same coordinate space as the SVG overlay viewport)
    map((pos) => screenToWorld(vp, pos.x, pos.y)),
    // world → local shape space (inverse of the shape's affine transform)
    map((world) => viewportToLocal(world, center, transform)),
    // local → normalised gradient coords [0, 1] (origin at shape centre)
    map((local) => ({ x: local.x / selW + 0.5, y: local.y / selH + 0.5 })),
    tap((pt) => {
      const current = activeEditorGradient.peek()
      if (!current) return
      const patch = computePatch(handle, pt, current)
      latestGradient = { ...current, ...patch }

      // 1. Overlay handles: direct signal write → 1 RAF hop
      activeEditorGradient.value = latestGradient

      // 2. WASM canvas: fill modifier → throttled render (~60 Hz)
      //    Only available for fills; strokes have no WASM modifier API,
      //    so they rely on SVG overlay feedback during drag.
      if (isFill) {
        const modifiedFills = baseFills.map((f, i) =>
          i === fillIndex ? { ...f, fillColorGradient: latestGradient! } : f
        )
        renderer.setFillModifierNoRender(shapeId, modifiedFills)

        const now = performance.now()
        if (now - lastRenderTs >= DRAG_RENDER_INTERVAL_MS) {
          lastRenderTs = now
          renderer.requestRenderFrame()
        }
      }
    }),
    map(() => undefined as void),
    takeUntil(stopper),
  )

  // Commit the final gradient to the document on pointer release.
  // Matches the commit-on-release pattern of move/resize/rotate (applyModifiersAndCommit).
  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      if (isFill) {
        renderer.cleanFillModifiers()
        renderer.flushRenderSync()
      }
      const fn = activeEditorOnChange.peek()
      if (fn && latestGradient) fn({ fillColorGradient: latestGradient })
    }),
    map(() => undefined as void),
  )

  return merge(dragStream, commitOnRelease)
}
