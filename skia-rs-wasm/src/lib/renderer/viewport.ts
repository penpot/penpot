/**
 * Viewport class for managing pan and zoom state
 * Provides coordinate transformations between screen and world space
 */

import type { Point, ViewportOptions, ViewBox } from './types'

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
   * Pan by screen-space deltas. Matches frontend: vbox is world-space visible top-left,
   * so we subtract (delta/zoom) from pan (frontend: update-viewport-position {:x #(- % (/ (:x delta) zoom))}).
   * @param dx - Screen pixel delta (mouse movement)
   * @param dy - Screen pixel delta (mouse movement)
   */
  pan(dx: number, dy: number): void {
    this._panX -= dx / this._zoom
    this._panY -= dy / this._zoom
  }

  /**
   * Zoom at a specific point, keeping that point fixed on screen.
   * panX/panY are world-space visible top-left: world at screen (px,py) = (panX + px/zoom, panY + py/zoom).
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

    const worldX = this._panX + point.x / oldZoom
    const worldY = this._panY + point.y / oldZoom

    this._zoom = newZoom

    this._panX = worldX - point.x / newZoom
    this._panY = worldY - point.y / newZoom
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
   * Convert screen coordinates to world coordinates.
   * Visible top-left in world is (panX, panY), so world = (panX, panY) + screen/zoom.
   */
  screenToWorld(screenX: number, screenY: number): Point {
    return {
      x: this._panX + screenX / this._zoom,
      y: this._panY + screenY / this._zoom,
    }
  }

  /**
   * Convert world coordinates to screen coordinates.
   */
  worldToScreen(worldX: number, worldY: number): Point {
    return {
      x: (worldX - this._panX) * this._zoom,
      y: (worldY - this._panY) * this._zoom,
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

