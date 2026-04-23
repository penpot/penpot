/**
 * Pure helpers for drag-to-reparent: both in the Layers panel (zone-based)
 * and on the canvas (point-in-frame drop). Mirrors Penpot's drop-side geometry
 * (dnd.cljs) and on-drop dispatch (layer_item.cljs), emitting Penpot-shaped
 * MovObjectsChange records for the commit pipeline.
 */

import type { Change, MovObjectsChange, Point } from 'penpot-exporter/types'
import type { IndexedShape } from '../../worker/types'
import {
  isBoolShape,
  isComponentShape,
  isFrameShape,
  isGroupShape,
} from '../../worker/geometry/shapes'
import { containsPoint } from '../../worker/geometry/rect'

export type DropSide = 'top' | 'center' | 'bot'

/**
 * Three-zone detection for containers (top 20% / center 60% / bot 20%),
 * binary split at 50% otherwise.
 */
export function computeDropSide(
  offsetY: number,
  rowHeight: number,
  detectCenter: boolean,
): DropSide {
  if (rowHeight <= 0) return 'bot'
  if (detectCenter) {
    const thold1 = rowHeight * 0.2
    const thold2 = rowHeight * 0.8
    if (offsetY < thold1) return 'top'
    if (offsetY > thold2) return 'bot'
    return 'center'
  }
  return offsetY < rowHeight / 2 ? 'top' : 'bot'
}

/** True for shape types that can hold children (frame, group, bool, component). */
export function isContainer(node: IndexedShape | null | undefined): boolean {
  return isFrameShape(node) || isGroupShape(node) || isBoolShape(node) || isComponentShape(node)
}

/** Walk the parent chain from `descendantId` up — returns true if `ancestorId` is encountered. */
export function isAncestor(
  objects: Record<string, IndexedShape>,
  ancestorId: string,
  descendantId: string,
): boolean {
  let current: string | undefined = descendantId
  while (current) {
    if (current === ancestorId) return true
    current = objects[current]?.parentId
  }
  return false
}

export interface ResolveDropTargetParams {
  targetId: string
  side: DropSide
  draggedIds: readonly string[]
  objects: Record<string, IndexedShape>
}

export interface ResolvedDropTarget {
  parentId: string
  index: number
}

/**
 * Resolve a (targetId, side) gesture to a (parentId, index) commit.
 * Returns null for invalid or no-op drops.
 */
export function resolveDropTarget(
  params: ResolveDropTargetParams,
): ResolvedDropTarget | null {
  const { targetId, side, draggedIds, objects } = params
  if (draggedIds.length === 0) return null

  if (draggedIds.some((id) => id === targetId)) return null

  const target = objects[targetId]
  if (!target) return null

  const targetParentId = target.parentId
  let parentId: string
  let index: number

  if (side === 'center') {
    if (!isContainer(target)) return null
    parentId = targetId
    index = 0
  } else {
    if (!targetParentId) return null
    parentId = targetParentId
    const parent = objects[parentId]
    const siblings = parent?.shapes ?? []
    const currentIndex = siblings.indexOf(targetId)
    if (currentIndex < 0) return null
    index = side === 'top' ? currentIndex : currentIndex + 1
  }

  if (draggedIds.some((id) => isAncestor(objects, id, parentId))) return null

  const parent = objects[parentId]
  if (!parent || (parent.shapes === undefined && !isContainer(parent))) {
    return null
  }

  if (isNoOpDrop(draggedIds, objects, parentId, index)) return null

  return { parentId, index }
}

function isNoOpDrop(
  draggedIds: readonly string[],
  objects: Record<string, IndexedShape>,
  parentId: string,
  index: number,
): boolean {
  const parent = objects[parentId]
  const siblings = parent?.shapes ?? []
  for (const id of draggedIds) {
    const shape = objects[id]
    if (!shape) return false
    if (shape.parentId !== parentId) return false
    const currentIndex = siblings.indexOf(id)
    if (currentIndex !== index && currentIndex !== index - 1) {
      return false
    }
  }
  return true
}

export interface BuildReparentChangesParams {
  pageId: string
  parentId: string
  index: number
  shapeIds: readonly string[]
  objects: Record<string, IndexedShape>
}

export interface ReparentChanges {
  redoChanges: Change[]
  undoChanges: Change[]
}

/**
 * Redo = single `mov-objects` to (parentId, index).
 * Undo = one `mov-objects` per original parent group, using `afterShape` so
 * the inverse is robust against intermediate sibling shifts.
 */
export function buildReparentChanges(
  params: BuildReparentChangesParams,
): ReparentChanges {
  const { pageId, parentId, index, shapeIds, objects } = params

  const redo: MovObjectsChange = {
    type: 'mov-objects',
    pageId,
    parentId,
    shapes: [...shapeIds],
    index,
  }

  const undoByParent = new Map<string, MovObjectsChange>()
  for (const id of shapeIds) {
    const shape = objects[id]
    const oldParentId = shape?.parentId
    if (!oldParentId) continue
    const oldParent = objects[oldParentId]
    const siblings = oldParent?.shapes ?? []
    const oldIdx = siblings.indexOf(id)
    const afterShape = oldIdx > 0 ? siblings[oldIdx - 1] : null

    const existing = undoByParent.get(oldParentId)
    if (existing) {
      existing.shapes.push(id)
    } else {
      const entry: MovObjectsChange = {
        type: 'mov-objects',
        pageId,
        parentId: oldParentId,
        shapes: [id],
        ...(afterShape != null ? { afterShape } : { index: oldIdx >= 0 ? oldIdx : 0 }),
      }
      undoByParent.set(oldParentId, entry)
    }
  }

  return {
    redoChanges: [redo],
    undoChanges: Array.from(undoByParent.values()),
  }
}

/**
 * Innermost container whose selrect contains `point`, excluding `excludeIds` and
 * their descendants (so a shape can't be reparented into itself or another shape
 * being moved together). Returns the root frame (id where parentId == null) as a
 * fallback only when the point lies inside it — never returns a non-container.
 */
export function findContainerAtPoint(
  objects: Record<string, IndexedShape>,
  point: Point,
  excludeIds: readonly string[],
): string | null {
  const excluded = new Set<string>(excludeIds)
  for (const id of excludeIds) {
    collectDescendants(objects, id, excluded)
  }

  let bestId: string | null = null
  let bestArea = Infinity
  for (const id of Object.keys(objects)) {
    if (excluded.has(id)) continue
    const node = objects[id]
    if (!isContainer(node)) continue
    if (!node.selrect) continue
    if (!containsPoint(node.selrect, point)) continue
    const area = (node.selrect.width ?? 0) * (node.selrect.height ?? 0)
    if (area < bestArea) {
      bestId = id
      bestArea = area
    }
  }
  return bestId
}

function collectDescendants(
  objects: Record<string, IndexedShape>,
  rootId: string,
  acc: Set<string>,
): void {
  const stack: string[] = [rootId]
  while (stack.length > 0) {
    const id = stack.pop()!
    const node = objects[id]
    const kids = node?.shapes
    if (!kids) continue
    for (const child of kids) {
      if (!acc.has(child)) {
        acc.add(child)
        stack.push(child)
      }
    }
  }
}
