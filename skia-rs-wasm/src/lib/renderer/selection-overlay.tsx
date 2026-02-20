/**
 * SVG overlay for selection bounds and area marquee.
 * Reads from workspace store; receives canvasSize from parent for viewBox.
 */

import { useMemo } from 'react'
import { useWorkspaceStore } from './store/workspace-store'
import { getSelectionBounds } from './selection-bounds'

const SELECTION_STROKE = 'var(--color-accent-tertiary, #0d7377)'
const SELECTION_STROKE_WIDTH = 1

export interface SelectionOverlayProps {
  canvasSize: { width: number; height: number }
}

export function SelectionOverlay({ canvasSize }: SelectionOverlayProps) {
  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const selectedNodes = useWorkspaceStore((state) => state.selectedNodes)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const viewportVersion = useWorkspaceStore((state) => state.viewportVersion)
  const isSelecting = useWorkspaceStore((state) => state.isSelecting)
  const selectionRect = useWorkspaceStore((state) => state.selectionRect)
  const isMoving = useWorkspaceStore((state) => state.isMoving)
  const movePreviewDelta = useWorkspaceStore((state) => state.movePreviewDelta)

  const selectionBounds = useMemo(() => getSelectionBounds(selectedNodes), [selectedNodes])

  const showSelectionRect = selectedIds.size > 0 && selectionBounds && viewport
  const effectiveBounds =
    showSelectionRect && selectionBounds
      ? isMoving && movePreviewDelta
        ? {
          x: selectionBounds.x + movePreviewDelta.x,
          y: selectionBounds.y + movePreviewDelta.y,
          width: selectionBounds.width,
          height: selectionBounds.height,
        }
        : selectionBounds
      : null
  const showAreaMarquee = isSelecting && selectionRect != null && viewport != null
  const areaMarqueeWorld =
    showAreaMarquee && viewport && selectionRect
      ? {
        x: viewport.panX + (selectionRect.x ?? (selectionRect as { x1?: number }).x1 ?? 0) / viewport.zoom,
        y: viewport.panY + (selectionRect.y ?? (selectionRect as { y1?: number }).y1 ?? 0) / viewport.zoom,
        width: (selectionRect.width ?? 0) / viewport.zoom,
        height: (selectionRect.height ?? 0) / viewport.zoom,
      }
      : null
  const viewBox =
    viewport && canvasSize.width > 0 && canvasSize.height > 0
      ? `${viewport.panX} ${viewport.panY} ${canvasSize.width / viewport.zoom} ${canvasSize.height / viewport.zoom}`
      : '0 0 100 100'

  return (
    <svg
      key={`selection-overlay-${viewportVersion}`}
      aria-hidden
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
      }}
      viewBox={viewBox}
      preserveAspectRatio="xMidYMid meet"
    >
      {showSelectionRect && effectiveBounds && (
        <rect
          x={effectiveBounds.x}
          y={effectiveBounds.y}
          width={effectiveBounds.width}
          height={effectiveBounds.height}
          fill="none"
          stroke={SELECTION_STROKE}
          strokeWidth={SELECTION_STROKE_WIDTH / (viewport?.zoom ?? 1)}
        />
      )}
      {showAreaMarquee && areaMarqueeWorld && (
        <rect
          x={areaMarqueeWorld.x}
          y={areaMarqueeWorld.y}
          width={areaMarqueeWorld.width}
          height={areaMarqueeWorld.height}
          fill="rgba(37,99,235,0.1)"
          stroke="#2563eb"
          strokeWidth={SELECTION_STROKE_WIDTH / (viewport?.zoom ?? 1)}
        />
      )}
    </svg>
  )
}
