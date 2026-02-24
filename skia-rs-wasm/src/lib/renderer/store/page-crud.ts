/**
 * CRUD operations for document and pages.
 * Delegates to DocumentModel; updates are pushed by the model to workspace store and dev store.
 */

import { useWorkspaceStore } from './workspace-store'
import { DocumentModel } from './document-model'
import type { PenpotDocument, PenpotNode, PenpotPage } from '@penpot-exporter/types'
import { makeSelrect } from '../../worker/types'

export function createNewDocument(): PenpotDocument {
  const ROOT_UUID = '00000000-0000-0000-0000-000000000000'
  const rootFrame: PenpotNode = {
    id: ROOT_UUID,
    type: 'frame',
    x: 0,
    y: 0,
    width: 800,
    height: 600,
    parentId: undefined,
    selrect: { x: 0, y: 0, width: 800, height: 600, x1: 0, y1: 0, x2: 800, y2: 600 },
  }
  const initialPage: PenpotPage = {
    id: crypto.randomUUID(),
    name: 'Page 1',
    children: [rootFrame],
    background: '#FFFFFF',
  }
  return {
    name: 'Untitled',
    children: [initialPage],
    components: {},
    images: {},
    paintStyles: {},
    textStyles: {},
    componentProperties: {},
    externalLibraries: {},
    missingFonts: [],
    isShared: false,
  }
}

export async function setDocument(document: PenpotDocument): Promise<void> {
  const model = new DocumentModel()
  await model.loadDocument(document)
}

export async function setActivePage(pageId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.setActivePage(pageId)
}

export async function addPage(page: PenpotPage): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.addPage(page)
}

export async function updatePage(page: PenpotPage & { pageId: string }): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (!model) return
  const { pageId, ...pageData } = page
  const updatedPage = { ...pageData, id: pageId } as PenpotPage
  await model.commitMove(pageId, updatedPage)
}

export async function deletePage(pageId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.deletePage(pageId)
}

/**
 * Deep-clone the page tree and apply move delta to every node whose id is in selectedIds.
 * Preserves tree structure; only x, y, selrect, and points are updated for selected nodes.
 */
export function applyMoveDeltaToPage(
  page: PenpotPage,
  selectedIds: Set<string>,
  delta: { x: number; y: number }
): PenpotPage {
  function applyToNode(node: PenpotNode): PenpotNode {
    const childList = (node as { children?: PenpotNode[] }).children
    const updatedChildren = childList?.length ? childList.map(applyToNode) : undefined
    if (!selectedIds.has(node.id)) {
      return updatedChildren ? { ...node, children: updatedChildren } : { ...node }
    }
    const x = (node.x ?? 0) + delta.x
    const y = (node.y ?? 0) + delta.y
    const sr = node.selrect as {
      x?: number
      y?: number
      width?: number
      height?: number
      x1?: number
      y1?: number
      x2?: number
      y2?: number
    } | undefined
    const selrect = sr
      ? {
          ...sr,
          ...(typeof sr.x === 'number' && { x: sr.x + delta.x }),
          ...(typeof sr.y === 'number' && { y: sr.y + delta.y }),
          ...(typeof sr.x1 === 'number' && { x1: sr.x1 + delta.x }),
          ...(typeof sr.y1 === 'number' && { y1: sr.y1 + delta.y }),
          ...(typeof sr.x2 === 'number' && { x2: sr.x2 + delta.x }),
          ...(typeof sr.y2 === 'number' && { y2: sr.y2 + delta.y }),
        }
      : undefined
    const points = (node.points as { x: number; y: number }[] | undefined)?.map((p) => ({
      x: p.x + delta.x,
      y: p.y + delta.y,
    }))
    return {
      ...node,
      x,
      y,
      ...(selrect && { selrect }),
      ...(points && { points }),
      ...(updatedChildren && { children: updatedChildren }),
    }
  }
  const children = (page.children ?? []).map(applyToNode)
  return { ...page, children }
}

/**
 * Apply a 2D affine transform matrix to a shape's geometry.
 * Transforms selrect corners (or points), returns new selrect and optional x, y, width, height, points.
 * Used to commit resize.
 */
export function applyResizeTransformToNode(
  node: PenpotNode,
  matrix: { a: number; b: number; c: number; d: number; e: number; f: number }
): Partial<PenpotNode> | null {
  const sr = node.selrect
  if (!sr) return null
  const x = sr.x ?? 0
  const y = sr.y ?? 0
  const w = sr.width ?? 0
  const h = sr.height ?? 0
  if (w <= 0 || h <= 0) return null

  const { a: ma, b: mb, c: mc, d: md, e: me, f: mf } = matrix
  const T = (node as { transform?: { a: number; b: number; c: number; d: number } }).transform

  // Shape center in world space
  const cx = x + w / 2
  const cy = y + h / 2

  // Compute world-space corners by applying the shape's own transform (T)
  // around the selrect center, matching WASM's calculate_bounds(true) logic.
  const worldCorner = (dx: number, dy: number): { x: number; y: number } => {
    if (!T) return { x: cx + dx, y: cy + dy }
    return {
      x: cx + T.a * dx + T.c * dy,
      y: cy + T.b * dx + T.d * dy,
    }
  }

  const wNw = worldCorner(-w / 2, -h / 2)
  const wNe = worldCorner(w / 2, -h / 2)
  const wSe = worldCorner(w / 2, h / 2)
  const wSw = worldCorner(-w / 2, h / 2)

  // Apply resize matrix M to each world corner
  const applyM = (p: { x: number; y: number }): { x: number; y: number } => ({
    x: ma * p.x + mc * p.y + me,
    y: mb * p.x + md * p.y + mf,
  })

  const newNw = applyM(wNw)
  const newNe = applyM(wNe)
  const newSe = applyM(wSe)
  const newSw = applyM(wSw)

  // New center = M applied to old center
  const newCx = ma * cx + mc * cy + me
  const newCy = mb * cx + md * cy + mf

  // New physical dimensions (distances between corners)
  const newWidth = Math.sqrt((newNe.x - newNw.x) ** 2 + (newNe.y - newNw.y) ** 2)
  const newHeight = Math.sqrt((newSw.x - newNw.x) ** 2 + (newSw.y - newNw.y) ** 2)

  if (newWidth <= 0 || newHeight <= 0) return null

  // New selrect: centered at newCenter, axis-aligned with new dimensions
  // (matching Penpot convention: selrect is in the shape's local/unrotated frame)
  const newX = newCx - newWidth / 2
  const newY = newCy - newHeight / 2
  const selrect = makeSelrect(newX, newY, newWidth, newHeight)

  // New transform: derived from nw→ne and nw→sw directions of the transformed bounds,
  // exactly matching WASM's Bounds::transform_matrix() logic.
  const hvx = (newNe.x - newNw.x) / newWidth
  const hvy = (newNe.y - newNw.y) / newWidth
  const vvx = (newSw.x - newNw.x) / newHeight
  const vvy = (newSw.y - newNw.y) / newHeight
  const newTransform = { a: hvx, b: hvy, c: vvx, d: vvy, e: 0, f: 0 }

  const points = [newNw, newNe, newSe, newSw]

  const updates: Partial<PenpotNode> = {
    selrect,
    points,
    transform: newTransform as PenpotNode['transform'],
  }
  if (typeof node.x === 'number') updates.x = newX
  if (typeof node.y === 'number') updates.y = newY
  if (typeof (node as { width?: number }).width === 'number') (updates as { width?: number }).width = newWidth
  if (typeof (node as { height?: number }).height === 'number') (updates as { height?: number }).height = newHeight
  return updates
}

export async function addNode(node: PenpotNode): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.addNode(node)
}

export async function updateNode(nodeId: string, updates: Partial<PenpotNode>): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.updateNode(nodeId, updates)
}

export async function deleteNode(nodeId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.deleteNode(nodeId)
}
