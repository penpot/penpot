import type { IndexedNode, IndexedPage } from '../../worker/types'

/** Ordered list of nodes for a page (root first, then depth-first). */
export function orderedNodesFromPage(page: IndexedPage): IndexedNode[] {
  const result: IndexedNode[] = []
  const root = Object.values(page.objects).find((o) => o.parentId == null)
  if (!root) return result

  result.push(root)

  function walk(shapeIds: string[] | undefined): void {
    if (!shapeIds?.length) return
    for (const id of shapeIds) {
      const node = page.objects[id]
      if (!node) continue
      result.push(node)
      walk(node.shapes)
    }
  }

  walk(root.shapes)
  return result
}

export interface OrderedNodeWithDepth {
  node: IndexedNode
  depth: number
}

/** Ordered list of nodes with depth (root = 0, its children = 1, etc.). */
export function orderedNodesWithDepth(page: IndexedPage): OrderedNodeWithDepth[] {
  const result: OrderedNodeWithDepth[] = []
  const root = Object.values(page.objects).find((o) => o.parentId == null)
  if (!root) return result

  result.push({ node: root, depth: 0 })

  function walk(shapeIds: string[] | undefined, depth: number): void {
    if (!shapeIds?.length) return
    for (const id of shapeIds) {
      const node = page.objects[id]
      if (!node) continue
      result.push({ node, depth })
      walk(node.shapes, depth + 1)
    }
  }

  walk(root.shapes, 1)
  return result
}
