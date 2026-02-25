/**
 * Enriches a page's text shapes with position-data computed by the WASM layout engine.
 * Must run after initPage (or equivalent) so shapes are loaded into WASM.
 */

import type { WasmModule } from '../wasm-types'
import type { PenpotNode, PenpotPage } from '@penpot-exporter/types'
import type { PositionDataEntry } from '@penpot-exporter/types'
import { calculatePositionData } from '../api/text'

function mapToCamelCase(
  entry: { paragraph: number; span: number; 'start-pos': number; 'end-pos': number; x: number; y: number; width: number; height: number; direction: number }
): PositionDataEntry {
  return {
    paragraph: entry.paragraph,
    span: entry.span,
    startPos: entry['start-pos'],
    endPos: entry['end-pos'],
    x: entry.x,
    y: entry.y,
    width: entry.width,
    height: entry.height,
    direction: entry.direction,
  }
}

function enrichNode(module: WasmModule, node: PenpotNode): PenpotNode {
  const childList = (node as { children?: PenpotNode[] }).children
  const enrichedChildren = childList?.length
    ? childList.map((child) => enrichNode(module, child))
    : undefined

  if (node.type === 'text' && node.id) {
    try {
      const rawEntries = calculatePositionData(module, node)
      const positionData =
        rawEntries.length > 0 ? rawEntries.map(mapToCamelCase) : undefined
      return {
        ...node,
        ...(enrichedChildren ? { children: enrichedChildren } : {}),
        ...(positionData ? { positionData } : {}),
      }
    } catch {
      return enrichedChildren ? { ...node, children: enrichedChildren } : { ...node }
    }
  }

  return enrichedChildren ? { ...node, children: enrichedChildren } : { ...node }
}

/**
 * Walks the page tree and attaches position-data to each text shape.
 * Returns a new page (clone-on-write). Shapes must already be loaded into WASM.
 */
export function enrichPageWithPositionData(
  module: WasmModule,
  page: PenpotPage
): PenpotPage {
  const children = page.children
  if (!children?.length) return page
  return {
    ...page,
    children: children.map((node) => enrichNode(module, node)),
  }
}
