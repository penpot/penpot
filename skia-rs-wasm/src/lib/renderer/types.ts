/**
 * Penpot Node/Shape Types
 * Uses only exporter types (camelCase); no backward compatibility layer.
 */

import type { Matrix, PenpotPage } from '@penpot-exporter/types'
import type { QueryParams, WorkerMessage } from '../worker/types'

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

export function isSvgContentTree(
  content: SvgContent
): content is { tag: string; attrs?: Record<string, unknown>; content?: SvgContent[] } {
  return typeof content === 'object' && content !== null && 'tag' in content
}

export function isSvgContentString(content: SvgContent): content is string {
  return typeof content === 'string'
}

export function isSvgContent(
  content: Record<string, unknown> | SvgContent | undefined
): content is SvgContent {
  if (content === undefined) {
    return false
  }
  return (
    typeof content === 'string' ||
    (typeof content === 'object' && content !== null && 'tag' in content)
  )
}

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

export type { Point, ViewportOptions, ViewBox } from './viewport'

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
