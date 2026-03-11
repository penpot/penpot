/**
 * Viewport actions: plain functions to update pan/zoom programmatically (e.g. zoom buttons, sync from Figma).
 * Each updates the store and applies the change to the WASM renderer when available.
 */

import { useWorkspaceStore } from '../store/workspace-store'
import { Viewport } from '../viewport'

export function setPan(x: number, y: number): void {
  const { viewport, renderer, updateViewport } = useWorkspaceStore.getState()
  if (!viewport) return
  const tmp = Viewport.from(viewport)
  tmp.setPan(x, y)
  if (renderer) renderer.applyViewport(tmp)
  updateViewport({ panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom })
}

export function setZoom(zoom: number): void {
  const { viewport, renderer, updateViewport } = useWorkspaceStore.getState()
  if (!viewport) return
  const tmp = Viewport.from(viewport)
  tmp.setZoom(zoom)
  if (renderer) renderer.applyViewport(tmp)
  updateViewport({ panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom })
}

export function zoomAt(point: { x: number; y: number }, scale: number): void {
  const { viewport, renderer, updateViewport } = useWorkspaceStore.getState()
  if (!viewport) return
  const tmp = Viewport.from(viewport)
  tmp.zoomAt(point, scale)
  if (renderer) renderer.applyViewport(tmp)
  updateViewport({ panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom })
}

export function resetViewport(): void {
  const { viewport, renderer, updateViewport } = useWorkspaceStore.getState()
  if (!viewport) return
  const tmp = Viewport.from(viewport)
  tmp.reset()
  if (renderer) renderer.applyViewport(tmp)
  updateViewport({ panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom })
}
