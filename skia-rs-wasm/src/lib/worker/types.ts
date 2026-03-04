/**
 * Worker-specific types. Shared types (Point, Line, Change) come from common.
 */

import type { PenpotNode, PenpotPage, Selrect } from 'penpot-exporter/lib'
import type { Change } from 'penpot-exporter/lib'
import type { Point } from '@skia-rs-wasm/common'
import type { Quadtree } from './quadtree'

/** Worker configuration (keys logged only; shape extensible). */
export type WorkerConfig = Record<string, unknown>

/** Dimensions for index/update-text-rect. */
export interface WorkerTextRectDimensions {
  x?: number
  y?: number
  width?: number
  height?: number
}

/** In-memory text rect cache value (dimensions plus optional layout data). */
export type WorkerTextRectCacheValue = WorkerTextRectDimensions & {
  positionData?: unknown
  points?: unknown
  selrect?: unknown
}

/** Payload for configure command. */
export interface WorkerConfigurePayload {
  config: WorkerConfig
}

/** Payload for index/initialize command. */
export interface WorkerIndexInitializePayload {
  page: PenpotPage
}

/** Payload for index/update command (full page replacement). */
export interface WorkerIndexUpdatePayload {
  pageId: string
  page: PenpotPage
}

/** Payload for index/update command (incremental changes). */
export interface WorkerIndexUpdateWithChangesPayload {
  pageId: string
  changes: Change[]
}

/** Payload for index/update-text-rect command. */
export interface WorkerUpdateTextRectPayload {
  pageId: string
  shapeId: string
  dimensions: WorkerTextRectDimensions
}

/** Indexed shape: PenpotNode with optional child-id list (flat structure). Uses camelCase parentId/frameId from ShapeBaseAttributes. */
export type IndexedShape = PenpotNode & {
  shapes?: string[]
}

/** Internal indexed page (flat objects map) used for selection/index state */
export interface IndexedPage {
  id: string
  objects: Record<string, IndexedShape>
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
  index: Quadtree
  bounds: Selrect
  parentsIndex: Record<string, Set<string>>
  clipIndex: Record<string, PenpotNode[]>
}

export interface WorkerState {
  pagesIndex: Record<string, IndexedPage>
  selection: Record<string, SelectionIndex>
  textRect?: Record<string, Record<string, WorkerTextRectCacheValue>>
}

/** Request/response correlation ID. Client generates unique values: client_${Date.now()}_${counter} */
export interface WorkerMessage {
  cmd: string
  replyTo: string
  payload?: unknown
  buffer?: boolean
}

export interface SerializedMessage {
  cmd: string
  replyTo: string
  payload?: unknown
  buffer?: boolean
}

export type Line = [Point, Point]

/** Payload shapes for WorkerClient.sendMessage by command. */
export type WorkerSendPayload =
  | WorkerConfigurePayload
  | WorkerIndexInitializePayload
  | WorkerIndexUpdatePayload
  | WorkerIndexUpdateWithChangesPayload
  | QueryParams
  | WorkerUpdateTextRectPayload
  | undefined

/** Response from worker handlers (null or array of node ids for query-selection). */
export type WorkerResponse = null | string[]

export interface WorkerClient {
  sendMessage(cmd: string, payload?: WorkerSendPayload): Promise<WorkerResponse>
  configure(config: WorkerConfig): Promise<void>
  addPage(page: PenpotPage): Promise<void>
  updatePage(pageId: string, page: PenpotPage): Promise<void>
  updatePageWithChanges(pageId: string, changes: Change[]): Promise<void>
  onMessage(callback: (message: WorkerMessage) => void): () => void
  destroy(): void
}

export { flattenPageToIndexed } from './flatten'
export { ZERO_UUID, makeSelrect } from '@skia-rs-wasm/common'
