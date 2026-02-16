/**
 * Renderer client lifecycle: init and cleanup
 * Encapsulates AbortController and init-in-progress guard; updates store with renderer instance.
 */

import type { RendererOptions } from './types'
import type { Renderer } from './index'
import { Renderer as RendererClass } from './index'
import { useWorkspaceStore } from './store/workspace-store'

export class RendererClientManager {
  private abortController: AbortController | null = null
  private isInitializing = false

  async init(
    canvas: HTMLCanvasElement,
    rendererOptions?: RendererOptions,
    onRendererReady?: (renderer: Renderer) => void
  ): Promise<void> {
    const { renderer, wasmModule} = useWorkspaceStore.getState()

    if (renderer || this.isInitializing) {
      return
    }

    if (!wasmModule) {
      throw new Error('WASM module not initialized. Call initWasmModule() first.')
    }

    this.abortController = new AbortController()
    const signal = this.abortController.signal
    this.isInitializing = true

    try {
      const newRenderer = RendererClass.builder()
        .canvas(canvas)
        .module(wasmModule)
        .options(rendererOptions ?? {})
        .build()


      if (signal.aborted) {
        newRenderer.destroyContext()
        this.isInitializing = false
        this.abortController = null
        return
      }

      useWorkspaceStore.setState({ renderer: newRenderer })
      this.abortController = null
      this.isInitializing = false

      onRendererReady?.(newRenderer)
    } catch (error) {
      this.isInitializing = false
      this.abortController = null
      throw error
    }
  }

  cleanup(): void {
    const { renderer } = useWorkspaceStore.getState()

    if (this.abortController) {
      this.abortController.abort()
      this.abortController = null
    }

    if (renderer) {
      renderer.destroy()
    }

    useWorkspaceStore.setState({ renderer: null })
    this.isInitializing = false
  }
}

const rendererClientManager = new RendererClientManager()

export function initRendererClient(
  canvas: HTMLCanvasElement,
  rendererOptions?: RendererOptions,
  onRendererReady?: (renderer: Renderer) => void
): Promise<void> {
  return rendererClientManager.init(canvas, rendererOptions, onRendererReady)
}

export function cleanupRendererClient(): void {
  rendererClientManager.cleanup()
}
