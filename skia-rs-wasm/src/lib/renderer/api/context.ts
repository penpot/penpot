/**
 * Context state management and utility functions
 */

import type { WasmModule } from '../wasm-types'

// Internal state for render management
let pendingRender = false
let contextInitialized = false
let contextLost = false

// Canvas pixels state (shared with canvas module)
export let canvasPixels: ImageData | null = null
export function setCanvasPixels(pixels: ImageData | null): void {
  canvasPixels = pixels
}

/**
 * Sets the context initialization state
 */
export function setContextInitialized(initialized: boolean): void {
  contextInitialized = initialized
}

/**
 * Sets the context lost state
 */
export function setContextLost(lost: boolean): void {
  contextLost = lost
}

/**
 * Gets the pending render state
 */
export function getPendingRender(): boolean {
  return pendingRender
}

/**
 * Sets the pending render state
 */
export function setPendingRender(value: boolean): void {
  pendingRender = value
}

/**
 * Gets the context initialized state
 */
export function getContextInitialized(): boolean {
  return contextInitialized
}

/**
 * Gets the context lost state
 */
export function getContextLost(): boolean {
  return contextLost
}

/**
 * Checks if context is ready for operations
 */
export function checkContext(_module: WasmModule): void {
  if (!contextInitialized || contextLost) {
    throw new Error('WASM context not initialized or lost')
  }
}

