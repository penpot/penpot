/**
 * Process incremental changes against IndexedPage.
 * Aligned with Penpot's common/files/changes process-change multimethods.
 */

import type { IndexedPage, IndexedShape } from './types'
import { ZERO_UUID } from '@skia-rs-wasm/common'
import type {
  Change,
  AddObjChange,
  ModObjChange,
  DelObjChange,
  MovObjectsChange,
  ReorderChildrenChange,
  Operation,
  AssignOperation,
  SetOperation,
} from '@skia-rs-wasm/common'
import type { PenpotNode } from 'penpot-exporter/lib'
import { isFrameShape } from './geometry/shapes'
import { assignHierarchy, ensureShapes } from './helpers'

/** Normalize shapes to array (Penpot sends single id or array) */
function normalizeShapes(shapes: unknown): string[] {
  if (Array.isArray(shapes)) {
    return shapes.filter((s): s is string => typeof s === 'string')
  }
  if (typeof shapes === 'string') {
    return [shapes]
  }
  return []
}

function getParentId(c: { parentId?: string | null }): string | undefined {
  return c.parentId ?? undefined
}

function getFrameId(c: { frameId?: string }): string | undefined {
  return c.frameId
}

/** Insert items at index, or append if index is null/undefined */
function insertAtIndex<T>(arr: T[], index: number | null | undefined, items: T[]): T[] {
  const list = [...arr]
  if (index == null || index >= list.length) {
    return [...list, ...items]
  }
  list.splice(index, 0, ...items)
  return list
}

/** Get all descendant ids for a shape (for delete) */
function getDescendantIds(objects: Record<string, IndexedShape>, shapeId: string): string[] {
  const result: string[] = []
  const stack = [shapeId]
  while (stack.length > 0) {
    const id = stack.pop()!
    const shape = objects[id]
    const childIds = shape?.shapes
    if (childIds?.length) {
      for (const cid of childIds) {
        result.push(cid)
        stack.push(cid)
      }
    }
  }
  return result
}

function processOperation(shape: IndexedShape, op: Operation): IndexedShape {
  switch (op.type) {
    case 'assign': {
      const assignOp = op as AssignOperation
      const value = assignOp.value ?? {}
      return { ...shape, ...value } as IndexedShape
    }
    case 'set': {
      const setOp = op as SetOperation
      const attr = setOp.attr
      const val = setOp.val
      return { ...shape, [attr]: val } as IndexedShape
    }
    case 'set-touched': {
      const touched = (op as { touched?: Set<string> | string[] | null }).touched
      if (touched == null || (Array.isArray(touched) && touched.length === 0)) {
        const { touched: _t, ...rest } = shape
        return rest as IndexedShape
      }
      const touchedSet = Array.isArray(touched) ? new Set(touched) : touched
      return { ...shape, touched: Array.from(touchedSet) as import('penpot-exporter/lib').SyncGroups[] }
    }
    case 'set-remote-synced': {
      const remoteSynced = (op as { remoteSynced?: boolean | null }).remoteSynced
      if (!remoteSynced) {
        const { remoteSynced: _r, ...rest } = shape
        return rest as IndexedShape
      }
      return { ...shape, remoteSynced: true }
    }
    default:
      return shape
  }
}

function processAddObj(data: IndexedPage, change: AddObjChange): IndexedPage {
  const { id, obj } = change
  const parentId = getParentId(change) ?? getFrameId(change) ?? ZERO_UUID
  const frameId = getFrameId(change) ?? parentId
  const index = change.index ?? null

  const objects = { ...data.objects }

  const parent = objects[parentId]
  const resolvedParentId = parent ? parentId : ZERO_UUID
  const resolvedFrameId = objects[frameId] ? frameId : ZERO_UUID

  const childIds = (obj as { children?: PenpotNode[] }).children
    ?.map(c => c.id)
    .filter((sid): sid is string => sid != null)

  const frameIdForShape =
    obj.type === 'frame' ? id ?? obj.id ?? resolvedFrameId : resolvedFrameId
  const shapeWithMeta = ensureShapes(
    assignHierarchy(obj, id, resolvedParentId, frameIdForShape),
    childIds
  )

  objects[id] = shapeWithMeta

  const parentShape = objects[resolvedParentId]
  if (parentShape) {
    const shapes = parentShape.shapes ?? []
    const newShapes = shapes.includes(id)
      ? shapes
      : insertAtIndex(shapes, index, [id]).filter(Boolean)
    objects[resolvedParentId] = {
      ...parentShape,
      shapes: newShapes,
    }
  }

  return { ...data, objects }
}

function processDelObj(data: IndexedPage, change: DelObjChange): IndexedPage {
  const { id } = change
  const objects = { ...data.objects }
  const target = objects[id]
  if (!target) {
    return data
  }

  const parentId = target.parentId ?? target.frameId ?? ZERO_UUID
  const toRemove = [id, ...getDescendantIds(objects, id)]

  for (const rid of toRemove) {
    delete objects[rid]
  }

  const parent = objects[parentId]
  if (parent) {
    const shapes = (parent.shapes ?? []).filter((s: string) => !toRemove.includes(s))
    objects[parentId] = { ...parent, shapes }
  }

  return { ...data, objects }
}

function processModObj(data: IndexedPage, change: ModObjChange): IndexedPage {
  const { id, operations } = change
  const objects = { ...data.objects }
  const shape = objects[id]
  if (!shape) {
    return data
  }

  const updated = operations.reduce(processOperation, shape)
  objects[id] = updated
  return { ...data, objects }
}

function processMovObjects(data: IndexedPage, change: MovObjectsChange): IndexedPage {
  const parentId = getParentId(change) ?? ''
  const shapeIds = normalizeShapes(change.shapes)
  const index = change.index ?? null
  const afterShape = change.afterShape ?? null

  const objects = { ...data.objects }
  const parent = objects[parentId]
  if (!parent || shapeIds.length === 0) {
    return data
  }

  const frameId = isFrameShape(parent) ? parent.id : parent.frameId ?? ZERO_UUID

  const insertIndex =
    afterShape != null
      ? (parent.shapes?.indexOf(afterShape) ?? -1) + 1
      : index

  const parentShapes = parent.shapes ?? []
  let newParentShapes = [...parentShapes]
  for (const shapeId of shapeIds) {
    if (!newParentShapes.includes(shapeId)) {
      newParentShapes = insertAtIndex(newParentShapes, insertIndex, [shapeId])
    }
  }
  objects[parentId] = { ...parent, shapes: newParentShapes }

  for (const shapeId of shapeIds) {
    const shape = objects[shapeId]
    if (shape) {
      objects[shapeId] = {
        ...shape,
        parentId,
        frameId,
      }
    }
  }

  for (const shapeId of shapeIds) {
    const shape = objects[shapeId]
    if (!shape) continue
    const oldParentId = shape.parentId
    if (oldParentId && oldParentId !== parentId) {
      const oldParent = objects[oldParentId]
      if (oldParent) {
        const oldShapes = (oldParent.shapes ?? []).filter((s: string) => s !== shapeId)
        objects[oldParentId] = { ...oldParent, shapes: oldShapes }
      }
    }
  }

  const updateFrameIdRec = (objs: Record<string, IndexedShape>, fid: string, sid: string): void => {
    const s = objs[sid]
    if (s) {
      objs[sid] = { ...s, frameId: fid }
      if (s.shapes && !isFrameShape(s)) {
        for (const cid of s.shapes) {
          updateFrameIdRec(objs, fid, cid)
        }
      }
    }
  }

  for (const shapeId of shapeIds) {
    const shape = objects[shapeId]
    if (shape && !isFrameShape(shape) && shape.shapes) {
      for (const cid of shape.shapes) {
        updateFrameIdRec(objects, frameId, cid)
      }
    }
  }

  return { ...data, objects }
}

function processReorderChildren(data: IndexedPage, change: ReorderChildrenChange): IndexedPage {
  const parentId = getParentId(change) ?? ''
  const order = normalizeShapes(change.shapes)

  const objects = { ...data.objects }
  const parent = objects[parentId]
  if (!parent) {
    return data
  }

  const oldShapes = parent.shapes ?? []
  const idToIdx = new Map<string, number>()
  order.forEach((id, idx) => idToIdx.set(id, idx))

  const sorted = [...oldShapes].sort((a, b) => {
    const ia = idToIdx.get(a) ?? -1
    const ib = idToIdx.get(b) ?? -1
    return ia - ib
  })

  if (JSON.stringify(sorted) === JSON.stringify(oldShapes)) {
    return data
  }

  objects[parentId] = { ...parent, shapes: sorted }
  return { ...data, objects }
}

export function processChange(data: IndexedPage, change: Change): IndexedPage {
  switch (change.type) {
    case 'add-obj':
      return processAddObj(data, change)
    case 'del-obj':
      return processDelObj(data, change)
    case 'mod-obj':
      return processModObj(data, change)
    case 'mov-objects':
      return processMovObjects(data, change)
    case 'reorder-children':
      return processReorderChildren(data, change)
    default:
      return data
  }
}

export function processChanges(data: IndexedPage, changes: Change[]): IndexedPage {
  return changes.reduce(processChange, data)
}
