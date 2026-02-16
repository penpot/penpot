/**
 * Viewport class for managing pan and zoom state
 * Provides coordinate transformations between screen and world space
 */

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

export class Viewport {
  private _zoom: number
  private _panX: number
  private _panY: number
  private readonly minZoom: number
  private readonly maxZoom: number

  constructor(options: ViewportOptions = {}) {
    this._zoom = options.zoom ?? 1
    this._panX = options.panX ?? 0
    this._panY = options.panY ?? 0
    this.minZoom = options.minZoom ?? 0.01
    this.maxZoom = options.maxZoom ?? 200

    // Ensure initial zoom is within bounds
    this._zoom = Math.max(this.minZoom, Math.min(this.maxZoom, this._zoom))
  }

  /**
   * Get current zoom level
   */
  get zoom(): number {
    return this._zoom
  }

  /**
   * Get current pan X position
   */
  get panX(): number {
    return this._panX
  }

  /**
   * Get current pan Y position
   */
  get panY(): number {
    return this._panY
  }

  /**
   * Pan the viewport by screen-space deltas (matches WASM playground)
   * We pass -panX to API; Rust: screen = zoom*world + pan. Drag right -> see content to right -> pan decreases
   * @param dx - Screen pixel delta (mouse movement)
   * @param dy - Screen pixel delta (mouse movement)
   */
  pan(dx: number, dy: number): void {
    this._panX -= dx
    this._panY -= dy
  }

  /**
   * Zoom at a specific point, keeping that point fixed on screen
   * Rust transform: world = (screen + pan) / zoom, so worldX = (point.x + panX) / zoom
   */
  zoomAt(point: Point, scale: number): void {
    const oldZoom = this._zoom
    const newZoom = Math.max(
      this.minZoom,
      Math.min(this.maxZoom, oldZoom * scale)
    )

    if (oldZoom === newZoom) {
      return // Zoom limit reached
    }

    // World under cursor: world = (screen + pan) / zoom (Rust viewbox transform)
    const worldX = (point.x + this._panX) / oldZoom
    const worldY = (point.y + this._panY) / oldZoom

    this._zoom = newZoom

    // Keep world point at same screen position: point.x = newZoom * worldX - newPanX
    this._panX = newZoom * worldX - point.x
    this._panY = newZoom * worldY - point.y
  }

  /**
   * Set zoom level directly
   * @param zoom - New zoom level (will be clamped to min/max)
   */
  setZoom(zoom: number): void {
    this._zoom = Math.max(this.minZoom, Math.min(this.maxZoom, zoom))
  }

  /**
   * Set pan position directly
   * @param x - New pan X position
   * @param y - New pan Y position
   */
  setPan(x: number, y: number): void {
    this._panX = x
    this._panY = y
  }

  /**
   * Convert screen coordinates to world coordinates
   * Rust transform: world = (screen + pan) / zoom
   */
  screenToWorld(screenX: number, screenY: number): Point {
    return {
      x: (screenX + this._panX) / this._zoom,
      y: (screenY + this._panY) / this._zoom,
    }
  }

  /**
   * Convert world coordinates to screen coordinates
   * Rust transform: screen = zoom * world - pan
   */
  worldToScreen(worldX: number, worldY: number): Point {
    return {
      x: worldX * this._zoom - this._panX,
      y: worldY * this._zoom - this._panY,
    }
  }

  /**
   * Get current viewbox rectangle in world coordinates
   * @param canvasWidth - Canvas width in pixels
   * @param canvasHeight - Canvas height in pixels
   * @returns Viewbox rectangle
   */
  getViewBox(canvasWidth: number, canvasHeight: number): ViewBox {
    const topLeft = this.screenToWorld(0, 0)
    const bottomRight = this.screenToWorld(canvasWidth, canvasHeight)

    return {
      x: topLeft.x,
      y: topLeft.y,
      width: bottomRight.x - topLeft.x,
      height: bottomRight.y - topLeft.y,
    }
  }

  /**
   * Reset viewport to default state
   */
  reset(): void {
    this._zoom = 1
    this._panX = 0
    this._panY = 0
  }

  /**
   * Get a copy of the current viewport state
   */
  clone(): Viewport {
    return new Viewport({
      zoom: this._zoom,
      panX: this._panX,
      panY: this._panY,
      minZoom: this.minZoom,
      maxZoom: this.maxZoom,
    })
  }
}

