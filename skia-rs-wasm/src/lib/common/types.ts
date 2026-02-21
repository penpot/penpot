/**
 * Shared types for renderer and worker.
 * Uses only exporter types for references (camelCase); no re-exports from @penpot-exporter.
 */

import type { CSSProperties } from 'react'
import type { Matrix, PenpotNode, PenpotPage, Selrect } from '@penpot-exporter/types'

/** Resize handle position (matches frontend handler keywords) */
export type ResizeHandlePosition =
  | 'top'
  | 'bottom'
  | 'left'
  | 'right'
  | 'top-left'
  | 'top-right'
  | 'bottom-left'
  | 'bottom-right'

/** Shape type values supported by the WASM renderer */
export type ShapeType =
  | 'rect'
  | 'path'
  | 'text'
  | 'frame'
  | 'group'
  | 'bool'
  | 'circle'
  | 'svg-raw'
  | 'image'

/** Boolean operation type (matches exporter BoolOperations string union) */
export type BoolType = 'union' | 'difference' | 'intersection' | 'exclude'

/**
 * Selection rectangle result from WASM
 */
export interface SelectionRectResult {
  width: number
  height: number
  center: { x: number; y: number }
  transform: Matrix
}

export interface PathContent {
  [key: string]: unknown
}

/**
 * SVG content structure - can be a tree or a string (leaf node)
 */
export type SvgContent =
  | {
      tag: string
      attrs?: Record<string, unknown>
      content?: SvgContent[]
    }
  | string

export interface Viewport {
  x: number
  y: number
  width: number
  height: number
}

export interface RendererOptions {
  dpr?: number
  debug?: boolean
  background?: string
}

/** Viewport coordinate point */
export interface Point {
  x: number
  y: number
}

export interface ViewportOptions {
  zoom?: number
  panX?: number
  panY?: number
  minZoom?: number
  maxZoom?: number
}

export interface ViewBox {
  x: number
  y: number
  width: number
  height: number
}

export interface PendingImageCallback {
  key: string
  thumbnail: boolean
  callback: () => Promise<boolean>
}

export interface SetObjectResult {
  thumbnails: PendingImageCallback[]
  full: PendingImageCallback[]
}

export type ResolveFontUrlCallback = (
  fontId: string,
  fontVariantId?: string,
  fontWeight?: number,
  fontStyle?: string
) => string

export interface FontInfo {
  fontId: string
  fontVariantId?: string
  fontWeight?: number
  fontStyle?: string
  isEmoji?: boolean
  isFallback?: boolean
}

export interface FontData {
  wasmId: string
  fontId: string
  fontVariantId: string
  style: number
  styleName: string
  weight: number
}

/**
 * Modifier key for triggering pan with left mouse button.
 * Use null to disable modifier-triggered pan.
 */
export type ViewportPanModifier = 'shift' | 'alt' | 'ctrl' | 'meta' | null

/**
 * Full viewport shortcuts config (KeyboardEvent.code strings and mouse options).
 * All fields are required in the resolved config; use Partial for overrides.
 */
export interface ShortcutsConfig {
  panLeft: string
  panRight: string
  panUp: string
  panDown: string
  panStep: number
  zoomInKeys: string[]
  zoomOutKeys: string[]
  zoomInFactor: number
  zoomOutFactor: number
  resetKeys: string[]
  panMouseButton: number
  panWithModifier: ViewportPanModifier
  wheelZoomEnabled: boolean
  wheelScalePerPixel: number
}

export interface CanvasWrapperProps {
  className?: string
  /** Style applied to the container div that wraps the canvas and overlay. */
  containerStyle?: CSSProperties
  /** Class name applied to the container div that wraps the canvas and overlay. */
  containerClassName?: string
  rendererOptions?: RendererOptions
  onError?: (error: Error) => void
  /** Initial viewport shortcuts (merged with defaults). Applied on mount when provided. */
  shortcuts?: Partial<ShortcutsConfig>
}

/** Worker configuration (keys logged only; shape extensible). */
export type WorkerConfig = Record<string, unknown>

/** Dimensions for index/update-text-rect. */
export interface WorkerTextRectDimensions {
  x?: number
  y?: number
  width?: number
  height?: number
}

/** Payload for configure command. */
export interface WorkerConfigurePayload {
  config: WorkerConfig
}

/** Payload for index/initialize command. */
export interface WorkerIndexInitializePayload {
  page: PenpotPage
}

/** Payload for index/update command. */
export interface WorkerIndexUpdatePayload {
  pageId: string
  page: PenpotPage
}

/** Payload for index/update-text-rect command. */
export interface WorkerUpdateTextRectPayload {
  pageId: string
  shapeId: string
  dimensions: WorkerTextRectDimensions
}

/** Internal indexed page (flat objects map) used for selection/index state */
export interface IndexedPage {
  id: string
  objects: Record<string, PenpotNode>
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

/** Payload shapes for WorkerClient.sendMessage by command. */
export type WorkerSendPayload =
  | WorkerConfigurePayload
  | WorkerIndexInitializePayload
  | WorkerIndexUpdatePayload
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
  onMessage(callback: (message: WorkerMessage) => void): () => void
  destroy(): void
}

export type InitializationState = 'idle' | 'loading' | 'ready' | 'error'
