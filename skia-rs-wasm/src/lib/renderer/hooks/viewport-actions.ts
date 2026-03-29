/**
 * Viewport actions: plain functions to update pan/zoom programmatically (e.g. zoom buttons, sync from Figma).
 * Each updates the `viewport` signal and applies the change to the WASM renderer when available.
 */

import { viewport } from '../signals/pointer'
import { useWorkspaceStore } from '../store/workspace-store'
import { Viewport } from '../viewport'

export function setPan(x: number, y: number): void {
  const vp = viewport.value
  const { renderer } = useWorkspaceStore.getState()
  if (!vp) return
  const tmp = Viewport.from(vp)
  tmp.setPan(x, y)
  if (renderer) renderer.applyViewport(tmp)
  viewport.value = { panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom }
}

export function setZoom(zoom: number): void {
  const vp = viewport.value
  const { renderer } = useWorkspaceStore.getState()
  if (!vp) return
  const tmp = Viewport.from(vp)
  tmp.setZoom(zoom)
  if (renderer) renderer.applyViewport(tmp)
  viewport.value = { panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom }
}

export function zoomAt(point: { x: number; y: number }, scale: number): void {
  const vp = viewport.value
  const { renderer } = useWorkspaceStore.getState()
  if (!vp) return
  const tmp = Viewport.from(vp)
  tmp.zoomAt(point, scale)
  if (renderer) renderer.applyViewport(tmp)
  viewport.value = { panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom }
}

export function resetViewport(): void {
  const vp = viewport.value
  const { renderer } = useWorkspaceStore.getState()
  if (!vp) return
  const tmp = Viewport.from(vp)
  tmp.reset()
  if (renderer) renderer.applyViewport(tmp)
  viewport.value = { panX: tmp.panX, panY: tmp.panY, zoom: tmp.zoom }
}
