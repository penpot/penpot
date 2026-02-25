/**
 * Incremental change types aligned with Penpot's common/files/changes schema.
 * Supports both camelCase and kebab-case for JSON compatibility.
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
  'page-id'?: string
  componentId?: string
  'component-id'?: string
  frameId: string
  'frame-id'?: string
  parentId?: string | null
  'parent-id'?: string | null
  index?: number | null
  ignoreTouched?: boolean
  'ignore-touched'?: boolean
}

export interface ModObjChange {
  type: 'mod-obj'
  id: string
  pageId?: string
  'page-id'?: string
  componentId?: string
  'component-id'?: string
  operations: Operation[]
}

export interface DelObjChange {
  type: 'del-obj'
  id: string
  pageId?: string
  'page-id'?: string
  componentId?: string
  'component-id'?: string
  ignoreTouched?: boolean
  'ignore-touched'?: boolean
}

export interface MovObjectsChange {
  type: 'mov-objects'
  pageId?: string
  'page-id'?: string
  componentId?: string
  'component-id'?: string
  ignoreTouched?: boolean
  'ignore-touched'?: boolean
  parentId: string
  'parent-id'?: string
  shapes: string[]
  index?: number | null
  afterShape?: string | null
  'after-shape'?: string | null
  allowAlteringCopies?: boolean
  'allow-altering-copies'?: boolean
}

export interface ReorderChildrenChange {
  type: 'reorder-children'
  pageId?: string
  'page-id'?: string
  componentId?: string
  'component-id'?: string
  ignoreTouched?: boolean
  'ignore-touched'?: boolean
  parentId: string
  'parent-id'?: string
  shapes: string[]
}

export type Change =
  | AddObjChange
  | ModObjChange
  | DelObjChange
  | MovObjectsChange
  | ReorderChildrenChange

/** Helper to get page-id from a change (supports both naming conventions) */
export function getChangePageId(change: Change): string | undefined {
  return (change as { pageId?: string; 'page-id'?: string }).pageId ?? (change as { pageId?: string; 'page-id'?: string })['page-id']
}

/** Helper to get parent-id from a change */
export function getChangeParentId(change: AddObjChange | MovObjectsChange | ReorderChildrenChange): string {
  return (change as { parentId?: string; 'parent-id'?: string }).parentId ?? (change as { parentId?: string; 'parent-id'?: string })['parent-id'] ?? ''
}
