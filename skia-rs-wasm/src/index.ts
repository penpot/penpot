/**
 * Skia WASM Renderer Package
 * 
 * A TypeScript wrapper for the Skia WASM renderer that provides
 * a simple canvas-like API for rendering Penpot nodes.
 */

export { Renderer } from './lib/renderer/index'
export { Viewport, screenToWorld, worldToScreen } from './lib/renderer/viewport'
export type { ViewportData } from './lib/renderer/viewport'
export { setPan, setZoom, zoomAt, resetViewport } from './lib/renderer/hooks/viewport-actions'
export { CanvasWrapper } from './lib/renderer/canvas-wrapper'
export type {
  ShapeType,
  RendererOptions,
  CanvasWrapperProps,
} from './lib/renderer/types'

export type { WasmModule, WasmModuleFactory } from './lib/renderer/wasm-types'

// WASM Module Initialization API
export {
  initWasmModule,
  isWasmModuleReady,
  getWasmModule,
  resetWasmModule,
} from './lib/wasm-init'

// Worker Initialization API
export {
  initWorker,
  isWorkerReady,
  getWorkerClient,
  cleanupWorker,
} from './lib/worker-init'

// Re-export utilities for advanced usage (conversions from common, rest from renderer)
export {
  uuidToU32,
  uuidToU32Tuple,
  hexToU32ARGB,
  colorToU32ARGB,
} from '@skia-rs-wasm/common'
export {
  getDPR,
  detectBrowser,
  isWebGL2Supported,
} from './lib/renderer/utils'

// Page/document update API (page-crud and commit)
export {
  createNewDocument,
  setDocument,
  setActivePage,
  addPage,
  deletePage,
  applyChanges,
  commitChangesPublic as commitChanges,
  undo,
  redo,
} from './lib/page-crud'

export type { CommitChangesParams, CommitFrame } from './lib/changes/commit-types'
export {
  emptyChangesBuilder,
  appendModObjPair,
  snapshotGeometryForUndo,
  buildTransformModObjPair,
  mergeBundle,
  toCommitBundle,
} from './lib/changes/changes-builder'
export { useHistoryStore } from './lib/history/history-store'

// Workspace store (for plugin UI viewport sync, etc.)
export { useWorkspaceStore } from './lib/renderer/store/workspace-store'
export type { WorkspaceState } from './lib/renderer/store/workspace-store'

