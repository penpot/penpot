/**
 * Main worker entry point
 * Translated from frontend/src/app/worker/index.cljs
 */

import type { WorkerState, IndexedPage, QueryParams, WorkerMessage, SerializedMessage } from './types'
import type { WorkerUpdateTextRectPayload } from '../renderer/types'
import type { PenpotNode, Point, Matrix, PenpotPage } from '@penpot-exporter/types'
import { flattenPageToIndexed } from './types'
import { handler, registerHandler } from './impl'
import { encode, decode } from './messages'
import * as selection from './selection'
import { makeRect, rectToPoints, pointsToRect } from './geometry/rect'
import { shapeToCenter } from './geometry/shapes'
import { point } from './geometry/point'

// Worker state
const state: WorkerState = {
  pagesIndex: {},
  selection: {},
  textRect: {},
}

// Helper: Create identity transform matrix
function identityTransform(): Matrix {
  return { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }
}

// Helper: Transform a point using a transform matrix
function transformPoint(pt: Point, transform: Matrix): Point {
  const { a, b, c, d, e, f } = transform
  return point(a * pt.x + c * pt.y + e, b * pt.x + d * pt.y + f)
}


// Register index handlers
registerHandler('index/clear', () => {
  state.pagesIndex = {}
  state.selection = {}
  state.textRect = {}
  return null
})

registerHandler('index/initialize', (message: WorkerMessage) => {
  console.log('index/initialize', message)
  const page = message.payload?.page as PenpotPage | undefined
  if (!page) {
    return null
  }

  const startTime = performance.now()

  try {
    const indexed = flattenPageToIndexed(page)
    // Update pages index
    state.pagesIndex[indexed.id] = indexed

    // Update selection index
    state.selection = selection.addPage(state.selection, indexed)

    const elapsed = performance.now() - startTime
    console.debug(`Page indexed: ${indexed.id}, elapsed: ${elapsed}ms`)

    return null
  } catch (error) {
    console.error('Error initializing page index:', error)
    return null
  }
})

registerHandler('index/update', (message: WorkerMessage) => {
  const pageId = message.payload?.pageId as string | undefined
  // const changes = message.payload?.changes as any[] | undefined // TODO: implement change processing

  if (!pageId) {
    return null
  }

  const startTime = performance.now()

  try {
    const oldPage = state.pagesIndex[pageId]
    if (!oldPage) {
      return null
    }

    // Simplified: in full implementation, would process changes
    // For now, assume newPage is provided or reconstructed
    const newPage = message.payload?.page as PenpotPage | undefined

    if (newPage) {
      const indexedNew = flattenPageToIndexed(newPage)
      state.pagesIndex[pageId] = indexedNew
      state.selection = selection.updatePage(state.selection, oldPage, indexedNew)
    }

    const elapsed = performance.now() - startTime
    console.debug(`Page index updated: ${pageId}, elapsed: ${elapsed}ms`)

    return null
  } catch (error) {
    console.error('Error updating page index:', error)
    return null
  }
})

registerHandler('index/query-selection', (message: WorkerMessage) => {
  const params = message.payload as QueryParams
  if (!params) {
    return []
  }

  try {
    const result = selection.query(state.selection, params)
    return Array.from(result)
  } catch (error) {
    console.error('Error querying selection:', error)
    return []
  }
})

registerHandler('index/update-text-rect', (message: WorkerMessage) => {
  const payload = message.payload as WorkerUpdateTextRectPayload | undefined
  const { pageId, shapeId, dimensions } = payload ?? {}

  if (!pageId || !shapeId || !dimensions) {
    return null
  }

  try {
    const page = state.pagesIndex[pageId]
    if (!page) {
      return null
    }

    const objects = page.objects
    const shape = objects[shapeId]
    if (!shape) {
      return null
    }

    // Get shape center
    const center = shapeToCenter(shape)
    if (!center) {
      return null
    }

    // Get transform or use identity
    const transform = shape.transform || identityTransform()

    // Create rect from dimensions (dimensions might be {width, height} or full Rect)
    // If dimensions has x/y, use them; otherwise create at origin
    const rect = makeRect(
      dimensions.x ?? 0,
      dimensions.y ?? 0,
      dimensions.width ?? 0,
      dimensions.height ?? 0
    )
    const rectPoints = rectToPoints(rect)
    if (!rectPoints) {
      return null
    }

    // Transform points: apply transform matrix, then translate by center
    // This matches ClojureScript's transform-points behavior
    const points = rectPoints.map(pt => {
      // Apply transform matrix (includes rotation, scale, and translation)
      const transformed = transformPoint(pt, transform)
      // Translate by center
      return point(transformed.x + center.x, transformed.y + center.y)
    })

    // Calculate selrect from transformed points
    const selrect = pointsToRect(points)
    if (!selrect) {
      return null
    }

    // Update shape with new data
    const updatedShape: PenpotNode = {
      ...shape,
      'position-data': null,
      points,
      selrect,
    }

    // Update objects
    const updatedObjects = {
      ...objects,
      [shapeId]: updatedShape,
    }

    // Update page
    const updatedPage: IndexedPage = {
      ...page,
      objects: updatedObjects,
    }
    state.pagesIndex[pageId] = updatedPage

    // Update text-rect cache
    if (!state.textRect) {
      state.textRect = {}
    }
    if (!state.textRect[pageId]) {
      state.textRect[pageId] = {}
    }
    state.textRect[pageId][shapeId] = {
      'position-data': null,
      points,
      selrect,
    }

    // Update selection index for this single shape
    const pageSelection = state.selection[pageId]
    if (pageSelection) {
      state.selection[pageId] = selection.updateIndexSingle(pageSelection, updatedObjects, updatedShape)
    }

    return null
  } catch (error) {
    console.error('Error updating text rect:', error)
    return null
  }
})

// Main worker message handler
self.addEventListener('message', (event: MessageEvent) => {
  console.log('Worker message received:', event.data)
  const raw = event.data as SerializedMessage
  const replyTo = raw?.replyTo
  try {
    const message = decode(raw)
    const result = handler(message)

    // #region agent log – worker debug for query-selection
    if (message.cmd === 'index/query-selection' && message.payload) {
      const params = message.payload as QueryParams
      const hasIndex = !!(state.selection && state.selection[params.pageId])
      const resultLen = Array.isArray(result) ? result.length : 0
      self.postMessage({
        type: 'agent-debug',
        data: {
          location: 'worker/index:query-selection',
          message: 'worker query-selection',
          data: { pageId: params.pageId, hasIndex, resultLength: resultLen },
          timestamp: Date.now(),
          hypothesisId: 'W1',
        },
      })
    }
    // #endregion

    // Always send response when client expects one (replyTo present)
    if (replyTo) {
      const response = encode({
        cmd: message.cmd,
        replyTo,
        payload: result ?? null,
      })
      self.postMessage(response)
    }
  } catch (error) {
    console.error('Error handling worker message:', error)
    self.postMessage({
      cmd: 'error',
      replyTo: replyTo ?? null,
      error: error instanceof Error ? error.message : String(error),
    })
  }
})

// Export for testing
export { state, handler }

