/**
 * Point query for node selection.
 * Mirrors frontend: small rect around cursor, then pick topmost node by z-order.
 */

import type { PenpotNode, PenpotPage } from '@penpot-exporter/types'
import type { WorkerClient } from '../types'
import type { Viewport } from '../viewport'
import { makeSelrect } from '../../worker/types'

const POINT_QUERY_SIZE_SCREEN = 5
/** Minimum half-size in world units so hit-test has tolerance after zoom/pan. */
const MIN_HALF_WORLD = 12

/**
 * Build a small world rect around the point (mirror frontend center->rect point (/ 5 zoom)).
 * Returns ids of nodes overlapping that rect.
 * Uses a minimum world-space half size so selection remains reliable after zoom.
 */
export async function queryNodesAtPoint(
  workerClient: WorkerClient,
  pageId: string,
  viewport: Viewport,
  screenX: number,
  screenY: number,
): Promise<string[]> {
  const center = viewport.screenToWorld(screenX, screenY)
  const half = Math.max(POINT_QUERY_SIZE_SCREEN / viewport.zoom, MIN_HALF_WORLD)
  const rect = makeSelrect(
    center.x - half,
    center.y - half,
    half * 2,
    half * 2
  )
  const result = await workerClient.sendMessage('index/query-selection', {
    pageId,
    rect,
    includeFrames: true,
    fullFrame: false,
    usingSelrect: false,
  })
  if (result === null || !Array.isArray(result)) return []
  return result
}

/**
 * Depth-first order of node ids from page (draw order: last = top).
 */
function depthFirstIds(page: PenpotPage): string[] {
  const ids: string[] = []
  const children = page.children ?? []
  function walk(nodes: PenpotNode[]) {
    for (const node of nodes) {
      ids.push(node.id)
      const childList = (node as { children?: PenpotNode[] }).children
      if (childList?.length) walk(childList)
    }
  }
  walk(children)
  return ids
}

/**
 * From the set of overlapping ids, return the one that appears last in depth-first order (topmost).
 */
export function pickTopmostNode(page: PenpotPage | null | undefined, ids: string[]): string | null {
  if (!page || !ids.length) return null
  const order = depthFirstIds(page)
  let lastIndex = -1
  let topId: string | null = null
  for (const id of ids) {
    const idx = order.indexOf(id)
    if (idx > lastIndex) {
      lastIndex = idx
      topId = id
    }
  }
  return topId
}
