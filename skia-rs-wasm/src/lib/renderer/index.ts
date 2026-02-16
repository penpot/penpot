/**
 * Main Renderer class that wraps the WASM module
 * Provides a simple canvas-like API for rendering Penpot nodes
 */

import type { WasmModule } from './wasm-types'
import type { RendererOptions } from './types'
import type { PenpotNode, PenpotPage } from '@penpot-exporter/types'
import { getDPR } from './utils'
import { Viewport } from './viewport'
import { initCanvasContext, setCanvasSize, setCanvasBackground, clearCanvas, clearCanvasPixels } from './api/canvas'
import { setViewBox, resizeViewbox, initializeViewport } from './api/viewport'
import { getContextInitialized } from './api/context'
import { processObject } from './api/orchestration'
import { requestRender } from './api/rendering'
import { useShape, setShapeChildren } from './api/shape'

function defaultOptions(options?: RendererOptions): Required<RendererOptions> {
  return {
    dpr: options?.dpr ?? getDPR(),
    debug: options?.debug ?? false,
    background: options?.background ?? '#FFFFFF',
  }
}

export class RendererBuilder {
  private canvasEl: HTMLCanvasElement | null = null
  private wasmModule: WasmModule | null = null
  private opts: RendererOptions = {}

  canvas(canvas: HTMLCanvasElement): this {
    this.canvasEl = canvas
    return this
  }

  module(module: WasmModule): this {
    this.wasmModule = module
    return this
  }

  options(options: RendererOptions): this {
    this.opts = options
    return this
  }

  build(): Renderer {
    if (!this.canvasEl || !this.wasmModule) {
      throw new Error('Renderer requires canvas and module. Use Renderer.builder().canvas(...).module(...).build()')
    }
    return Renderer.create(this.canvasEl, this.wasmModule, defaultOptions(this.opts))
  }
}

export class Renderer {
  private readonly module: WasmModule
  private readonly canvas: HTMLCanvasElement
  private readonly options: Required<RendererOptions>
  private viewport: Viewport | null = null
  private prevZoom: number = 1

  static builder(): RendererBuilder {
    return new RendererBuilder()
  }

  /** @internal Used by RendererBuilder. Prefer Renderer.builder().canvas().module().options().build() */
  static create(canvas: HTMLCanvasElement, module: WasmModule, options: Required<RendererOptions>): Renderer {
    return new Renderer(canvas, module, options)
  }

  private constructor(canvas: HTMLCanvasElement, module: WasmModule, options: Required<RendererOptions>) {
    this.canvas = canvas
    this.module = module
    this.options = options
  }

  /**
   * Destroys only the WebGL context. Module and canvas are unchanged.
   */
  destroyContext(): void {
    if (!getContextInitialized()) {
      return
    }
    clearCanvas(this.module, this.canvas)
  }

  /**
   * Destroys the WebGL context and resets viewport state. Does not null module or canvas.
   */
  destroy(): void {
    this.destroyContext()
    this.viewport = null
    this.prevZoom = 1
  }

  /**
   * Initializes or re-initializes the context and loads the given page (first load or page change).
   */
  async initPage(page: PenpotPage): Promise<void> {
    if (!this.module || !this.canvas) {
      throw new Error('Renderer not built. Use Renderer.builder() first.')
    }

    this.destroyContext()

    const success = initCanvasContext(
      this.module,
      this.canvas,
      this.options.dpr,
      this.options.debug
    )
    if (!success) {
      throw new Error('Failed to initialize WebGL context')
    }

    const canvasWidth = this.canvas.clientWidth || this.canvas.width
    const canvasHeight = this.canvas.clientHeight || this.canvas.height
    resizeViewbox(this.module, canvasWidth, canvasHeight)

    const background = page.background ?? this.options.background ?? '#FFFFFF'
    setCanvasBackground(this.module, background)

    const baseObjects: Record<string, PenpotNode> = Object.fromEntries(
      (page.children ?? []).map((n: PenpotNode) => [n.id, n])
    )
    await initializeViewport(
      this.module,
      baseObjects,
      1,
      { x: 0, y: 0 },
      background
    )
  }

  /**
   * Sets the viewport/viewbox
   */
  setViewport(zoom: number, x: number, y: number): void {
    if (!getContextInitialized() || !this.module) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    setViewBox(this.module, this.prevZoom, zoom, { x, y })
    this.prevZoom = zoom
  }

  /**
   * Sets the viewport using a Viewport instance
   * Rust render: scale(zoom) then translate(-area.left); area.left = -pan_x.
   * Transform: screen = zoom * world + pan_x, so pan must be in screen space.
   * API expects -panX (our screen-space pan), not -vbox.x (world-space).
   */
  applyViewport(viewport: Viewport): void {
    if (!getContextInitialized() || !this.module) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    this.viewport = viewport
    setViewBox(this.module, this.prevZoom, viewport.zoom, { x: viewport.panX, y: viewport.panY })
    this.prevZoom = viewport.zoom
  }

  getViewport(): Viewport | null {
    return this.viewport
  }

  resize(width: number, height: number): void {
    if (!getContextInitialized() || !this.module || !this.canvas) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    setCanvasSize(this.module, this.canvas, this.options.dpr)
    this.canvas.style.width = `${width}px`
    this.canvas.style.height = `${height}px`
    resizeViewbox(this.module, width, height)
  }

  clear(): void {
    if (!getContextInitialized()) {
      console.warn('Renderer context not initialized. Call initPage() first.')
      return
    }
    if (!this.module || !this.canvas) return
    clearCanvasPixels(this.module, this.canvas)
    if (this.module._reset_canvas) {
      this.module._reset_canvas()
    }
  }

  setBackground(color: number | string): void {
    if (!getContextInitialized() || !this.module) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    const hexColor =
      typeof color === 'number'
        ? `#${(color >>> 0).toString(16).padStart(8, '0').slice(2)}`
        : color
    setCanvasBackground(this.module, hexColor)
  }

  /**
   * Updates a single shape in place (attribute change). No teardown.
   */
  async updateShape(shape: PenpotNode): Promise<void> {
    if (!getContextInitialized() || !this.module) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    await processObject(this.module, shape)
    requestRender(this.module, 'updateShape')
  }

  /**
   * Adds a new shape to the pool. WASM use_shape adds it if it doesn't exist. No init_shapes_pool.
   */
  async addShape(shape: PenpotNode): Promise<void> {
    if (!getContextInitialized() || !this.module) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    await processObject(this.module, shape)
    requestRender(this.module, 'addShape')
  }

  /**
   * Updates the parent's children list (add/delete). For delete, pass the new list without the removed id;
   * WASM set_children will call delete_shape_children for removed ids.
   */
  updateParentChildren(parentId: string, childIds: string[]): void {
    if (!getContextInitialized() || !this.module) {
      throw new Error('Renderer context not initialized. Call initPage() first.')
    }
    useShape(this.module, parentId)
    setShapeChildren(this.module, childIds)
    requestRender(this.module, 'updateParentChildren')
  }

  getModule(): WasmModule {
    return this.module
  }

  isInitialized(): boolean {
    return getContextInitialized()
  }
}


// Export canvas wrapper and worker utilities
export { CanvasWrapper } from './canvas-wrapper'
export { WorkerClient } from '../worker-client'
export { createWorker } from '../worker-factory'
export type { CanvasWrapperProps, InitializationState } from './types'

// Export Zustand store
export { useWorkspaceStore, selectCurrentPageNodes } from './store/workspace-store'
export { setDocument, addPage, updatePage, deletePage, addNode, updateNode, deleteNode, createNewDocument } from './store/page-crud'

// Export renderer client lifecycle
export { initRendererClient, cleanupRendererClient, RendererClientManager } from './renderer-init'

// Export hooks
export { useStreams } from './hooks/use-streams'
export { useSelection } from './hooks/use-selection'
export { useMove } from './hooks/use-move'

// Export handlers
export { handleAreaSelection } from './handlers/selection'
export { startMoveSelected } from './handlers/move'

// Export streams
export { mousePosition$, mousePositionShift$, mousePositionAlt$, mousePositionMod$, keyboardSpace$ } from './streams'
export { askWorker$, askWorkerBuffered$ } from './streams/worker-streams'
export { dragStopper } from './streams/drag-stopper'

