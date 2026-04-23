import { useCallback, useRef } from 'react'
import { cn } from '@/lib/utils'
import type { IndexedNode, IndexedShape } from '../../worker/types'
import { ShapeIcon } from '../shape-icons'
import {
  computeDropSide,
  isAncestor,
  isContainer,
  type DropSide,
} from './reparent'

export const DRAG_MIME = 'application/x-skia-layer-ids'

export interface DragOverState {
  id: string
  side: DropSide
}

export interface LayerRowProps {
  node: IndexedNode
  depth: number
  active: boolean
  selectedIds: Set<string>
  objects: Record<string, IndexedShape>
  dragOver: DragOverState | null
  onSelect: (id: string) => void
  onDragStart: (id: string) => string[]
  onDragOver: (state: DragOverState | null) => void
  onDragEnd: () => void
  onDrop: (targetId: string, side: DropSide, draggedIds: string[]) => void
}

function readDraggedIds(e: React.DragEvent): string[] {
  const raw = e.dataTransfer.getData(DRAG_MIME)
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

function wouldBeInvalidCenter(
  targetId: string,
  draggedIds: readonly string[],
  objects: Record<string, IndexedShape>,
): boolean {
  if (draggedIds.length === 0) return true
  for (const id of draggedIds) {
    if (id === targetId) return true
    if (isAncestor(objects, id, targetId)) return true
  }
  return false
}

export function LayerRow({
  node,
  depth,
  active,
  selectedIds,
  objects,
  dragOver,
  onSelect,
  onDragStart,
  onDragOver,
  onDragEnd,
  onDrop,
}: LayerRowProps) {
  const rowRef = useRef<HTMLDivElement | null>(null)
  const container = isContainer(node)

  const handleDragStart = useCallback(
    (e: React.DragEvent) => {
      const ids = onDragStart(node.id)
      e.dataTransfer.setData(DRAG_MIME, JSON.stringify(ids))
      e.dataTransfer.effectAllowed = 'move'
    },
    [node.id, onDragStart],
  )

  const handleDragOver = useCallback(
    (e: React.DragEvent) => {
      if (!e.dataTransfer.types.includes(DRAG_MIME)) return
      e.preventDefault()
      e.dataTransfer.dropEffect = 'move'
      const el = rowRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const offsetY = e.clientY - rect.top
      const draggedIds = readDraggedIds(e)
      const invalidCenter = wouldBeInvalidCenter(node.id, draggedIds, objects)
      let side = computeDropSide(offsetY, rect.height, container && !invalidCenter)
      if (side === 'center' && invalidCenter) {
        side = offsetY < rect.height / 2 ? 'top' : 'bot'
      }
      if (!dragOver || dragOver.id !== node.id || dragOver.side !== side) {
        onDragOver({ id: node.id, side })
      }
    },
    [container, dragOver, node.id, objects, onDragOver],
  )

  const handleDragLeave = useCallback(
    (e: React.DragEvent) => {
      const related = e.relatedTarget as Node | null
      const el = rowRef.current
      if (el && related && el.contains(related)) return
      if (dragOver?.id === node.id) onDragOver(null)
    },
    [dragOver, node.id, onDragOver],
  )

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      if (!e.dataTransfer.types.includes(DRAG_MIME)) return
      e.preventDefault()
      const el = rowRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const offsetY = e.clientY - rect.top
      const draggedIds = readDraggedIds(e)
      if (draggedIds.length === 0) return
      const invalidCenter = wouldBeInvalidCenter(node.id, draggedIds, objects)
      let side = computeDropSide(offsetY, rect.height, container && !invalidCenter)
      if (side === 'center' && invalidCenter) {
        side = offsetY < rect.height / 2 ? 'top' : 'bot'
      }
      onDrop(node.id, side, draggedIds)
    },
    [container, node.id, objects, onDrop],
  )

  const side = dragOver?.id === node.id ? dragOver.side : null

  return (
    <div
      ref={rowRef}
      draggable
      onDragStart={handleDragStart}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDragEnd={() => {
        onDragOver(null)
        onDragEnd()
      }}
      onDrop={handleDrop}
      onClick={() => onSelect(node.id)}
      role="button"
      tabIndex={0}
      className={cn(
        'flex h-8 w-full cursor-pointer items-center gap-2 rounded-md px-2 text-sm transition-colors',
        active ? 'bg-muted/80 text-foreground' : 'text-foreground hover:bg-muted/60',
        side === 'top' && 'border-t-2 border-blue-500',
        side === 'bot' && 'border-b-2 border-blue-500',
        side === 'center' && 'ring-2 ring-blue-500 ring-inset',
      )}
      style={{ paddingLeft: 8 + depth * 12 }}
      data-layer-id={node.id}
      data-selected={active ? 'true' : 'false'}
      aria-selected={active}
    >
      <ShapeIcon type={node.type} className="size-3.5 shrink-0 text-muted-foreground" />
      <span className="min-w-0 flex-1 truncate">{node.name ?? 'Shape'}</span>
      {selectedIds.has(node.id) && selectedIds.size > 1 && (
        <span className="shrink-0 rounded bg-blue-500/10 px-1 text-[10px] text-blue-600">
          {selectedIds.size}
        </span>
      )}
    </div>
  )
}
