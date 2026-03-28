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
