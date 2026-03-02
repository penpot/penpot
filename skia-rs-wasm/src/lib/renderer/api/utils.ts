/**
 * Utility functions
 */

import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import type { WasmModule } from '../wasm-types'
import type { PenpotNode } from 'penpot-exporter/lib'
import { ObjectSvg } from './svg-components'

/**
 * Gets static SVG markup for a shape using React SSR
 * Replicates ClojureScript get-static-markup behavior
 */
export function getStaticMarkup(shape: PenpotNode): string {
  if ((shape as { type: string }).type !== 'svg-raw') {
    // For non-svg-raw shapes, return minimal wrapper
    return `<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" fill="none"></svg>`
  }

  const element = createElement(ObjectSvg, { shape })
  return renderToStaticMarkup(element)
}

/**
 * Initializes WASM module
 * Note: This is a simplified version. Full implementation may be in Renderer class
 */
export async function initWasmModule(
  moduleFactory: (options?: any) => Promise<WasmModule>,
  wasmPath?: string
): Promise<WasmModule | null> {
  try {
    const options: any = {}
    if (wasmPath) {
      options.locateFile = (path: string) => {
        if (path.endsWith('.wasm')) {
          return `${wasmPath}/${path}`
        }
        return path
      }
    }
    return await moduleFactory(options)
  } catch (error) {
    console.error('Failed to initialize WASM module:', error)
    return null
  }
}

