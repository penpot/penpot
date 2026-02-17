/**
 * Penpot Node/Shape Types
 * Uses only exporter types (camelCase); no backward compatibility layer.
 */

import type { Matrix, PenpotPage } from '@penpot-exporter/types'

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
  [key: string]: any
}

/**
 * SVG content structure - can be a tree or a string (leaf node)
 */
export type SvgContent =
  | {
      tag: string
      attrs?: Record<string, any>
      content?: SvgContent[]
    }
  | string

export function isSvgContentTree(
  content: SvgContent
): content is { tag: string; attrs?: Record<string, any>; content?: SvgContent[] } {
  return typeof content === 'object' && content !== null && 'tag' in content
}

export function isSvgContentString(content: SvgContent): content is string {
  return typeof content === 'string'
}

export function isSvgContent(
  content: Record<string, any> | SvgContent | undefined
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

export interface CanvasWrapperProps {
  width?: number
  height?: number
  className?: string
  style?: React.CSSProperties
  rendererOptions?: RendererOptions
  onError?: (error: Error) => void
}

export interface WorkerClient {
  sendMessage(cmd: string, payload?: any): Promise<any>
  configure(config: any): Promise<void>
  addPage(page: PenpotPage): Promise<void>
  updatePage(pageId: string, page: PenpotPage): Promise<void>
  onMessage(callback: (message: any) => void): () => void
  destroy(): void
}

export type InitializationState = 'idle' | 'loading' | 'ready' | 'error'
