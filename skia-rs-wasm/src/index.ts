/**
 * Skia WASM Renderer Package
 * 
 * A TypeScript wrapper for the Skia WASM renderer that provides
 * a simple canvas-like API for rendering Penpot nodes.
 */

export { Renderer } from './lib/renderer/index'
export { Viewport } from './lib/renderer/viewport'
export type {
  ShapeType,
  RendererOptions,
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

// Re-export utilities for advanced usage
export {
  uuidToU32,
  uuidToU32Tuple,
  hexToU32ARGB,
  colorToU32ARGB,
  getDPR,
  detectBrowser,
  isWebGL2Supported,
} from './lib/renderer/utils'

// Re-export serializers for advanced usage
export {
  translateShapeType,
  translateBlendMode,
  translateConstraintH,
  translateConstraintV,
  translateBoolType,
  translateStrokeStyle,
  translateStrokeCap,
  translateShadowStyle,
  translateBlurType,
  translateVerticalAlign,
  translateGrowType,
} from './lib/renderer/api/serializers'

