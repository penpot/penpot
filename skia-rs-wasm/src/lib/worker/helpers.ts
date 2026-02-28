/**
 * Helper utilities for shape operations and index generation
 */

import type { PenpotNode } from 'penpot-exporter'
import type { IndexedShape } from '@skia-rs-wasm/common'
import { ZERO_UUID } from '@skia-rs-wasm/common'
import {
  isFrameShape,
  isBoolShape,
} from './geometry/shapes'

/**
 * Assign hierarchy fields (id, parentId, frameId) to the shape. Uses camelCase.
 */
export function assignHierarchy(
  shape: PenpotNode,
  id: string | undefined,
  parentId: string,
  frameId: string
): IndexedShape {
  const out: IndexedShape = { ...shape, parentId, frameId }
  if (id !== undefined) {
    out.id = id
  }
  return out
}

/**
 * Set shapes (child id list) from childIds when provided; otherwise keep existing or leave undefined.
 */
export function ensureShapes(
  shape: IndexedShape,
  childIds?: string[] | null
): IndexedShape {
  if (childIds !== undefined && childIds !== null) {
    return { ...shape, shapes: childIds.length > 0 ? childIds : undefined }
  }
  return shape
}

export function getParentId(objects: Record<string, PenpotNode>, shapeId: string): string | null {
  const shape = objects[shapeId]
  if (!shape) {
    return null
  }

  const parentId = shape.parentId
  if (!parentId || parentId === shapeId) {
    return null
  }

  return parentId
}

export function getParentIds(objects: Record<string, PenpotNode>, shapeId: string): string[] {
  const result: string[] = []
  let id: string | null = shapeId

  while (id) {
    const parentId = getParentId(objects, id)
    if (parentId && parentId !== id) {
      result.push(parentId)
      id = parentId
    } else {
      break
    }
  }

  return result
}

function generateIndexRecursive(
  index: Record<string, Set<string>>,
  objects: Record<string, PenpotNode>,
  shapeId: string,
  parents: string[]
): Record<string, Set<string>> {
  const shape = objects[shapeId]
  if (!shape) {
    return index
  }

  index[shapeId] = new Set(parents)
  const newParents = [shapeId, ...parents]

  const children = shape.shapes || []
  for (const childId of children) {
    index = generateIndexRecursive(index, objects, childId, newParents)
  }

  return index
}

export function generateChildAllParentsIndex(
  objects: Record<string, PenpotNode>,
  shapes?: PenpotNode[]
): Record<string, Set<string>> {
  if (shapes) {
    const index: Record<string, Set<string>> = {}
    for (const shape of shapes) {
      const parentIds = getParentIds(objects, shape.id)
      index[shape.id] = new Set(parentIds)
    }
    return index
  }

  return generateIndexRecursive({}, objects, ZERO_UUID, [])
}

export function createClipIndex(
  objects: Record<string, PenpotNode>,
  parentsIndex: Record<string, Set<string>>
): Record<string, PenpotNode[]> {
  const clipIndex: Record<string, PenpotNode[]> = {}

  function getClipParents(shape: PenpotNode): PenpotNode[] {
    const result: PenpotNode[] = []

    // Frames without showContent (except root)
    if (
      isFrameShape(shape) &&
      !shape.showContent &&
      shape.id !== ZERO_UUID
    ) {
      result.push(shape)
    }

    // Bool shapes
    if (isBoolShape(shape)) {
      result.push(shape)
    }

    // Masked groups
    if (shape.maskedGroup && shape.shapes && shape.shapes.length > 0) {
      const firstChild = objects[shape.shapes[0]]
      if (firstChild) {
        result.push(firstChild)
      }
    }

    return result
  }

  for (const [shapeId, parents] of Object.entries(parentsIndex)) {
    const shape = objects[shapeId]
    if (!shape) {
      continue
    }

    const clipParents: PenpotNode[] = []
    for (const parentId of parents) {
      const parent = objects[parentId]
      if (parent) {
        clipParents.push(...getClipParents(parent))
      }
    }

    if (clipParents.length > 0) {
      clipIndex[shapeId] = clipParents
    }
  }

  return clipIndex
}

export function getChildrenIds(objects: Record<string, PenpotNode>, shapeId: string): string[] {
  const shape = objects[shapeId]
  if (!shape || !shape.shapes) {
    return []
  }

  const result: string[] = []
  const stack = [...shape.shapes]

  while (stack.length > 0) {
    const id = stack.pop()!
    result.push(id)

    const child = objects[id]
    if (child && child.shapes) {
      stack.push(...child.shapes)
    }
  }

  return result
}

