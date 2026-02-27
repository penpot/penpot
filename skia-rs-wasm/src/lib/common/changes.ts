/**
 * Incremental change types aligned with Penpot's common/files/changes schema.
 * Uses camelCase for all property names.
 */

import type { PenpotNode } from '@penpot-exporter/types'

/** Operations for mod-obj changes */
export interface AssignOperation {
  type: 'assign'
  value: Record<string, unknown>
  ignoreTouched?: boolean
  ignoreGeometry?: boolean
}

export interface SetOperation {
  type: 'set'
  attr: string
  val: unknown
  ignoreTouched?: boolean
  ignoreGeometry?: boolean
}

export interface SetTouchedOperation {
  type: 'set-touched'
  touched?: Set<string> | string[] | null
}

export interface SetRemoteSyncedOperation {
  type: 'set-remote-synced'
  remoteSynced?: boolean | null
}

export type Operation =
  | AssignOperation
  | SetOperation
  | SetTouchedOperation
  | SetRemoteSyncedOperation

/** Shape-related changes (page/component scope) */
export interface AddObjChange {
  type: 'add-obj'
  id: string
  obj: PenpotNode
  pageId?: string
  componentId?: string
  frameId: string
  parentId?: string | null
  index?: number | null
  ignoreTouched?: boolean
}

export interface ModObjChange {
  type: 'mod-obj'
  id: string
  pageId?: string
  componentId?: string
  operations: Operation[]
}

export interface DelObjChange {
  type: 'del-obj'
  id: string
  pageId?: string
  componentId?: string
  ignoreTouched?: boolean
}

export interface MovObjectsChange {
  type: 'mov-objects'
  pageId?: string
  componentId?: string
  ignoreTouched?: boolean
  parentId: string
  shapes: string[]
  index?: number | null
  afterShape?: string | null
  allowAlteringCopies?: boolean
}

export interface ReorderChildrenChange {
  type: 'reorder-children'
  pageId?: string
  componentId?: string
  ignoreTouched?: boolean
  parentId: string
  shapes: string[]
}

export type Change =
  | AddObjChange
  | ModObjChange
  | DelObjChange
  | MovObjectsChange
  | ReorderChildrenChange

/** Helper to get pageId from a change */
export function getChangePageId(change: Change): string | undefined {
  return (change as { pageId?: string }).pageId
}

/** Helper to get parentId from a change */
export function getChangeParentId(change: AddObjChange | MovObjectsChange | ReorderChildrenChange): string {
  return (change as { parentId?: string }).parentId ?? ''
}
