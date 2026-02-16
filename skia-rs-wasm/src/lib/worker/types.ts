/**
 * Type definitions for the worker
 * Uses only types from penpot-exporter.
 */

import type { PenpotNode, PenpotPage, Point, Selrect } from '@penpot-exporter/types'

/** Build a full Selrect from origin and size */
export function makeSelrect(x: number, y: number, width: number, height: number): Selrect {
  return {
    x,
    y,
    width,
    height,
    x1: x,
    y1: y,
    x2: x + width,
    y2: y + height,
  }
}

/** Internal indexed page (flat objects map) used for selection/index state */
export interface IndexedPage {
  id: string
  objects: Record<string, PenpotNode>
}

function flattenChildren(nodes?: PenpotNode[]): Record<string, PenpotNode> {
  const acc: Record<string, PenpotNode> = {}
  if (!nodes) return acc
  for (const node of nodes) {
    acc[node.id] = node
    const childList = (node as { children?: PenpotNode[] }).children
    if (childList?.length) {
      Object.assign(acc, flattenChildren(childList))
    }
  }
  return acc
}

export function flattenPageToIndexed(page: PenpotPage): IndexedPage {
  return {
    id: page.id ?? ZERO_UUID,
    objects: flattenChildren(page.children),
  }
}

export interface QueryParams {
  pageId: string
  rect: Selrect
  frameId?: string
  fullFrame?: boolean
  includeFrames?: boolean
  ignoreGroups?: boolean
  clipChildren?: boolean
  usingSelrect?: boolean
}

export interface SelectionIndex {
  index: any // Quadtree
  bounds: Selrect
  parentsIndex: Record<string, Set<string>>
  clipIndex: Record<string, PenpotNode[]>
}

export interface WorkerState {
  pagesIndex: Record<string, IndexedPage>
  selection: Record<string, SelectionIndex>
  textRect?: Record<string, Record<string, any>>
}

/** Request/response correlation ID. Client generates unique values: client_${Date.now()}_${counter} */
export interface WorkerMessage {
  cmd: string
  replyTo: string
  payload?: any
  buffer?: boolean
}

export interface SerializedMessage {
  cmd: string
  replyTo: string
  payload?: any
  buffer?: boolean
}

export type Line = [Point, Point]

export const ZERO_UUID = '00000000-0000-0000-0000-000000000000'
