/**
 * Selection indexing and query logic
 * Translated from frontend/src/app/worker/selection.cljs
 */

import type { SelectionIndex, IndexedPage, QueryParams } from './types'
import type { IndexedShape } from './types'
import type { PenpotNode, Selrect } from 'penpot-exporter/lib'
import { ZERO_UUID, makeSelrect } from './types'
import * as quadtree from './quadtree'
import { pointsToRect, makeRect, containsRect } from './geometry/rect'
import { shapesToRect } from './geometry/shapes'
import {
  isTextShape,
  isFrameShape,
  isRootFrame,
  rectContainsShape,
  getImmediateChildren,
} from './geometry/shapes'
import { overlaps } from './geometry/intersect'
import { generateChildAllParentsIndex, createClipIndex, getChildrenIds } from './helpers'

const PADDING_PERCENT = 0.1

/** Normalize selrect to always have x, y, width, height (quadtree expects these). Handles both { x, y, width, height } and { x1, y1, x2, y2 } shapes. */
function normalizeSelrect(sr: Selrect | null | undefined): Selrect | null {
  if (!sr) return null
  const x = sr.x
  const y = sr.y
  const width = sr.width
  const height = sr.height
  if (typeof x !== 'number' || typeof y !== 'number' || width <= 0 || height <= 0) return null
  return makeSelrect(x, y, width, height)
}

function shapeToBounds(shape: PenpotNode): Selrect | null {
  const positionData = 'positionData' in shape ? shape.positionData : undefined
  if (isTextShape(shape) && positionData && Array.isArray(positionData) && positionData.length > 0) {
    return normalizeSelrect(shape.selrect) || null
  }

  const points = shape.points
  if (points && points.length > 0) {
    return pointsToRect(points)
  }

  return normalizeSelrect(shape.selrect) || null
}

function indexShape(
  objects: Record<string, PenpotNode>,
  parentsIndex: Record<string, Set<string>>,
  clipIndex: Record<string, PenpotNode[]>,
  index: quadtree.Quadtree,
  shape: PenpotNode
): quadtree.Quadtree {
  const bounds = shapeToBounds(shape)
  if (!bounds) {
    return index
  }

  const bound: Selrect = makeSelrect(bounds.x, bounds.y, bounds.width, bounds.height)

  const shapeId = shape.id
  const frameId = shape.frameId || ZERO_UUID
  const shapeType = shape.type

  const parents = parentsIndex[shapeId] || new Set<string>()
  const clipParents = clipIndex[shapeId] || []

  let frame: PenpotNode | undefined
  if (shapeType !== 'frame' && frameId !== ZERO_UUID) {
    frame = objects[frameId]
  }

  const shapeData = {
    ...shape,
    frame,
    clipParents,
    parents: Array.from(parents),
  }

  return quadtree.insert(index, shapeId, bound, shapeData)
}

function objectsBounds(objects: Record<string, PenpotNode>): Selrect | null {
  const shapes = Object.values(objects).filter(obj => obj.id !== ZERO_UUID)
  return shapesToRect(shapes)
}

function addPaddingBounds(bounds: Selrect): Selrect {
  const widthPad = bounds.width * PADDING_PERCENT
  const heightPad = bounds.height * PADDING_PERCENT

  return makeSelrect(
    bounds.x - widthPad,
    bounds.y - heightPad,
    bounds.width + 2 * widthPad,
    bounds.height + 2 * heightPad
  )
}

function createIndex(objects: Record<string, PenpotNode>): SelectionIndex {
  const parentsIndex = generateChildAllParentsIndex(objects)
  const clipIndex = createClipIndex(objects, parentsIndex)
  const rootShapes = getImmediateChildren(objects, ZERO_UUID)
  // Use bounds that encompass all objects so the quadtree covers content after pan/move (e.g. negative coords)
  const contentBounds = objectsBounds(objects)
  const rootBounds = shapesToRect(rootShapes)
  const bounds = contentBounds ?? rootBounds

  if (!bounds) {
    // Fallback bounds
    const defaultBounds = makeRect(0, 0, 10000, 10000)
    const index = quadtree.create(defaultBounds)
    return {
      index,
      bounds: defaultBounds,
      parentsIndex,
      clipIndex,
    }
  }

  const paddedBounds = addPaddingBounds(bounds)
  let index = quadtree.create(paddedBounds)

  // Index all shapes except root
  for (const [id, shape] of Object.entries(objects)) {
    if (id !== ZERO_UUID) {
      index = indexShape(objects, parentsIndex, clipIndex, index, shape)
    }
  }

  return {
    index,
    bounds: paddedBounds,
    parentsIndex,
    clipIndex,
  }
}

function updateIndex(
  data: SelectionIndex,
  oldObjects: Record<string, PenpotNode>,
  newObjects: Record<string, PenpotNode>
): SelectionIndex {
  function objectChanged(id: string): boolean {
    return oldObjects[id] !== newObjects[id]
  }

  const allIds = new Set([
    ...Object.keys(oldObjects),
    ...Object.keys(newObjects),
  ])

  const changedIds = new Set<string>()
  for (const id of allIds) {
    if (id !== ZERO_UUID && objectChanged(id)) {
      changedIds.add(id)
      // Also add children
      const children = getChildrenIds(newObjects, id)
      for (const childId of children) {
        changedIds.add(childId)
      }
    }
  }

  const shapes: PenpotNode[] = []
  for (const id of changedIds) {
    const shape = newObjects[id]
    if (shape) {
      shapes.push(shape)
    }
  }

  const partialParentsIndex = generateChildAllParentsIndex(newObjects, shapes)
  const partialClipIndex = createClipIndex(newObjects, partialParentsIndex)

  // Keep full indices: merge existing with partial for changed shapes, drop entries for removed shapes
  const parentsIndex: Record<string, Set<string>> = {}
  for (const [id, set] of Object.entries(data.parentsIndex)) {
    if (id in newObjects) parentsIndex[id] = set
  }
  for (const [id, set] of Object.entries(partialParentsIndex)) {
    parentsIndex[id] = set
  }
  const clipIndex: Record<string, PenpotNode[]> = {}
  for (const [id, arr] of Object.entries(data.clipIndex)) {
    if (id in newObjects) clipIndex[id] = arr
  }
  for (const [id, arr] of Object.entries(partialClipIndex)) {
    clipIndex[id] = arr
  }

  let index = quadtree.removeAll(data.index, changedIds)
  for (const shape of shapes) {
    index = indexShape(newObjects, parentsIndex, clipIndex, index, shape)
  }

  return {
    ...data,
    index,
    parentsIndex,
    clipIndex,
  }
}

export function updateIndexSingle(
  data: SelectionIndex,
  objects: Record<string, PenpotNode>,
  shape: PenpotNode
): SelectionIndex {
  const { index, parentsIndex, clipIndex } = data
  let newIndex = quadtree.removeAll(index, new Set([shape.id]))
  newIndex = indexShape(objects, parentsIndex, clipIndex, newIndex, shape)

  return {
    ...data,
    index: newIndex,
  }
}

function queryIndex(
  indexData: SelectionIndex,
  rect: Selrect,
  frameId: string | undefined,
  fullFrame: boolean,
  includeFrames: boolean,
  ignoreGroups: boolean,
  clipChildren: boolean,
  usingSelrect: boolean
): Set<string> {
  const { index } = indexData
  const result = new Set<string>()

  // Search quadtree
  for (const node of quadtree.search(index, rect)) {
    const shape = node.data as IndexedShape
    if (!shape) {
      continue
    }

    // Match criteria
    if (shape.hidden) {
      continue
    }

    if (!isFrameShape(shape) && shape.blocked) {
      continue
    }

    if (frameId && shape.frameId !== frameId) {
      continue
    }

    const shapeType = shape.type
    if (shapeType === 'frame' && !includeFrames) {
      continue
    }

    if ((shapeType === 'bool' || shapeType === 'group') && ignoreGroups) {
      continue
    }

    // Full frame check
    if (fullFrame) {
      if (!ignoreGroups && shape.componentId) {
        // OK
      } else if (!ignoreGroups && !isRootFrame(shape)) {
        // OK
      } else if ('shapes' in shape && shape.shapes && shape.shapes.length > 0) {
        if (!rectContainsShape(rect, shape)) {
          continue
        }
      } else {
        if (!overlaps(shape, rect, usingSelrect)) {
          continue
        }
      }
    }

    // Overlap check
    if (!overlaps(shape, rect, usingSelrect)) {
      continue
    }

    // Clip children check
    if (clipChildren) {
      const clipParents: PenpotNode[] =
        'clipParents' in shape && Array.isArray(shape.clipParents) ? shape.clipParents : []
      let shouldInclude = true
      for (const clipParent of clipParents) {
        if (!overlaps(clipParent, rect, usingSelrect)) {
          shouldInclude = false
          break
        }
      }
      if (!shouldInclude) {
        continue
      }
    }

    result.add(shape.id)
  }

  return result
}

// Public API
export function addPage(state: Record<string, SelectionIndex>, page: IndexedPage): Record<string, SelectionIndex> {
  const index = createIndex(page.objects)
  return {
    ...state,
    [page.id]: index,
  }
}

export function updatePage(
  state: Record<string, SelectionIndex>,
  oldPage: IndexedPage,
  newPage: IndexedPage
): Record<string, SelectionIndex> {
  const pageId = oldPage.id
  const existingIndex = state[pageId]

  const oldObjects = oldPage.objects
  const newObjects = newPage.objects
  const oldBounds = existingIndex?.bounds
  const newBounds = objectsBounds(newObjects)

  let newIndex: SelectionIndex

  if (existingIndex && oldBounds && newBounds && containsRect(oldBounds, newBounds)) {
    // Update existing index
    newIndex = updateIndex(existingIndex, oldObjects, newObjects)
  } else {
    // Recreate index
    newIndex = createIndex(newObjects)
  }

  return {
    ...state,
    [pageId]: newIndex,
  }
}

export function query(
  indexState: Record<string, SelectionIndex>,
  params: QueryParams
): Set<string> {
  const { pageId, rect, frameId, fullFrame, includeFrames, ignoreGroups, clipChildren, usingSelrect } = params

  const index = indexState[pageId]
  if (!index) {
    return new Set()
  }

  return queryIndex(
    index,
    rect,
    frameId,
    fullFrame ?? false,
    includeFrames ?? false,
    ignoreGroups ?? false,
    clipChildren ?? true,
    usingSelrect ?? false
  )
}

