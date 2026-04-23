/**
 * Left rail: page / layer tree, page metadata, layer selection.
 */

import { useCallback, useMemo, useState } from 'react'
import type { IndexedPage, IndexedShape } from '../../worker/types'
import type { PenpotNode, PenpotPage } from 'penpot-exporter/types'
import { useSnapshot } from 'valtio'
import { docProxy, getActiveOrSinglePageId } from '../../renderer/store/doc-proxy'
import { setSelectedIds } from '../../renderer/store/document-selection'
import { orderedNodesWithDepth } from '../../renderer/store/ordered-page-nodes'
import { FloatingEditorRail } from '../EditorShell/floating-editor-rail'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { ChevronDown, ChevronRight, FileText, Plus } from 'lucide-react'
import { commitPageMetadataUpdate } from '../../renderer/properties/commit-page-properties'
import { setActivePage, addPage } from '../../page-crud'
import { commitChanges } from '../../renderer/store/commit'
import { buildReparentChanges, resolveDropTarget, type DropSide } from './reparent'
import { LayerRow, type DragOverState } from './layer-row'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

export interface LayersPanelProps {
  className?: string
}

export function LayersPanel({ className }: LayersPanelProps) {
  const doc = useSnapshot(docProxy)
  const selectedIds = useMemo(() => new Set(doc.selectedIds), [doc.selectedIds])

  const [collapsed, setCollapsed] = useState(false)
  const [pagesOpen, setPagesOpen] = useState(true)
  const [layersOpen, setLayersOpen] = useState(true)
  const [dragOver, setDragOver] = useState<DragOverState | null>(null)

  const [editingPageId, setEditingPageId] = useState<string | null>(null)
  const [editingName, setEditingName] = useState('')

  const pid = doc.currentPageId ?? getActiveOrSinglePageId()
  const page: IndexedPage | undefined = pid ? doc.pageMap.get(pid) : undefined

  const pages = useMemo(() => Array.from(doc.pageMap.values()), [doc.pageMap])

  const currentPageNodes = useMemo(() => {
    if (!page) return
    return orderedNodesWithDepth(page)
  }, [page])

  const shapeLayers = useMemo(
    () => (currentPageNodes ?? []).filter(({ node }) => node.id !== ROOT_UUID),
    [currentPageNodes],
  )

  const layerCount = shapeLayers.length

  const onSelectPage = useCallback((id: string) => {
    if (id === docProxy.currentPageId) return
    void setActivePage(id)
  }, [])

  const onCreatePage = useCallback(async () => {
    const newId = crypto.randomUUID()
    const name = `Page ${docProxy.pageMap.size + 1}`
    const rootFrame: PenpotNode = {
      id: ROOT_UUID,
      name: 'Root',
      type: 'frame',
      x: 0,
      y: 0,
      width: 800,
      height: 600,
      parentId: undefined,
      selrect: { x: 0, y: 0, width: 800, height: 600, x1: 0, y1: 0, x2: 800, y2: 600 },
      points: [
        { x: 0, y: 0 },
        { x: 800, y: 0 },
        { x: 800, y: 600 },
        { x: 0, y: 600 },
      ],
    }
    const newPage: PenpotPage = {
      id: newId,
      name,
      children: [rootFrame],
      background: '#FFFFFF',
    }
    await addPage(newPage)
    await setActivePage(newId)
  }, [])

  const onStartRename = useCallback((id: string, current: string) => {
    setEditingPageId(id)
    setEditingName(current)
  }, [])

  const commitRename = useCallback(
    async (id: string) => {
      const p = docProxy.pageMap.get(id)
      if (!p) {
        setEditingPageId(null)
        return
      }
      const trimmed = editingName.trim()
      if (trimmed && trimmed !== (p.name ?? '')) {
        await commitPageMetadataUpdate(id, p, { name: trimmed })
      }
      setEditingPageId(null)
    },
    [editingName],
  )

  const onLayerRowClick = useCallback((id: string) => {
    setSelectedIds(new Set([id]))
  }, [])

  const [draggingInPanel, setDraggingInPanel] = useState(false)

  const onLayerDragStart = useCallback((id: string): string[] => {
    setDraggingInPanel(true)
    const current = docProxy.selectedIds
    if (current.has(id) && current.size > 1) {
      return Array.from(current)
    }
    setSelectedIds(new Set([id]))
    return [id]
  }, [])

  const onLayerDragEnd = useCallback(() => {
    setDraggingInPanel(false)
  }, [])

  const onLayerDrop = useCallback(
    async (targetId: string, side: DropSide, draggedIds: string[]) => {
      setDragOver(null)
      const activePageId = docProxy.currentPageId ?? getActiveOrSinglePageId()
      if (!activePageId) return
      const currentPage = docProxy.pageMap.get(activePageId)
      if (!currentPage) return
      const objects = currentPage.objects
      const resolved = resolveDropTarget({ targetId, side, draggedIds, objects })
      if (!resolved) return
      const { redoChanges, undoChanges } = buildReparentChanges({
        pageId: activePageId,
        parentId: resolved.parentId,
        index: resolved.index,
        shapeIds: draggedIds,
        objects,
      })
      await commitChanges({ redoChanges, undoChanges, pageId: activePageId })
    },
    [],
  )

  const [rootDropActive, setRootDropActive] = useState(false)

  const handleRootDrop = useCallback(async (draggedIds: string[]) => {
    setRootDropActive(false)
    setDragOver(null)
    if (draggedIds.length === 0) return
    const activePageId = docProxy.currentPageId ?? getActiveOrSinglePageId()
    if (!activePageId) return
    const currentPage = docProxy.pageMap.get(activePageId)
    if (!currentPage) return
    const objects = currentPage.objects as Record<string, IndexedShape>
    const rootChildren = objects[ROOT_UUID]?.shapes ?? []
    const filtered = draggedIds.filter((id) => {
      const shape = objects[id]
      if (!shape) return false
      if (shape.parentId === ROOT_UUID) return false
      return true
    })
    if (filtered.length === 0) return
    const { redoChanges, undoChanges } = buildReparentChanges({
      pageId: activePageId,
      parentId: ROOT_UUID,
      index: rootChildren.length,
      shapeIds: filtered,
      objects,
    })
    await commitChanges({ redoChanges, undoChanges, pageId: activePageId })
  }, [])

  const footer = layerCount === 1 ? '1 layer' : `${layerCount} layers`

  return (
    <FloatingEditorRail
      side="left"
      title="Design"
      collapsed={collapsed}
      onCollapsedChange={setCollapsed}
      footer={footer}
      data-layers-panel
      className={cn('min-h-0', className)}
    >
      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-2 p-2">
          {/* PAGES Section */}
          <section>
            <div className="flex items-center gap-0.5 pl-1 pr-0.5">
              <button
                type="button"
                className="flex flex-1 items-center gap-1 rounded-md py-1 pl-0.5 pr-1 text-left hover:text-foreground"
                onClick={() => setPagesOpen((o) => !o)}
                aria-expanded={pagesOpen}
              >
                {pagesOpen ? (
                  <ChevronDown className="size-3 shrink-0 text-muted-foreground" aria-hidden />
                ) : (
                  <ChevronRight className="size-3 shrink-0 text-muted-foreground" aria-hidden />
                )}
                <span className="text-[0.7rem] font-semibold uppercase tracking-wider text-muted-foreground">
                  Pages
                </span>
              </button>
              <span className="min-w-4 text-right text-xs tabular-nums text-muted-foreground">
                {doc.pageMap.size}
              </span>
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                className="text-muted-foreground"
                aria-label="Add page"
                title="Add page"
                onClick={() => void onCreatePage()}
              >
                <Plus />
              </Button>
            </div>

            {pagesOpen && (
              <ul className="mt-0.5 list-none space-y-0.5 p-0">
                {pages.length === 0 && (
                  <li className="px-2 py-1 text-xs text-muted-foreground">No pages yet</li>
                )}
                {pages.map((p) => {
                  const active = p.id === doc.currentPageId
                  const isEditing = editingPageId === p.id
                  return (
                    <li key={p.id}>
                      {isEditing ? (
                        <div
                          className={cn(
                            'flex items-center gap-2 rounded-md px-2 py-1.5',
                            active
                              ? 'bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300'
                              : 'bg-muted/40',
                          )}
                        >
                          <FileText
                            className={cn(
                              'size-4 shrink-0',
                              active
                                ? 'text-blue-700 dark:text-blue-300'
                                : 'text-muted-foreground',
                            )}
                            aria-hidden
                          />
                          <input
                            autoFocus
                            type="text"
                            className="min-w-0 flex-1 bg-transparent text-sm outline-none"
                            value={editingName}
                            onChange={(e) => setEditingName(e.target.value)}
                            onBlur={() => void commitRename(p.id)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') {
                                e.preventDefault()
                                void commitRename(p.id)
                              } else if (e.key === 'Escape') {
                                e.preventDefault()
                                setEditingPageId(null)
                              }
                            }}
                          />
                        </div>
                      ) : (
                        <button
                          type="button"
                          className={cn(
                            'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors',
                            active
                              ? 'bg-blue-100 font-medium text-blue-700 dark:bg-blue-950/40 dark:text-blue-300'
                              : 'text-foreground hover:bg-muted/60',
                          )}
                          onClick={() => onSelectPage(p.id)}
                          onDoubleClick={() => onStartRename(p.id, p.name ?? 'Page')}
                          title={p.name ?? 'Page'}
                        >
                          <FileText
                            className={cn(
                              'size-4 shrink-0',
                              active
                                ? 'text-blue-700 dark:text-blue-300'
                                : 'text-muted-foreground',
                            )}
                            aria-hidden
                          />
                          <span className="min-w-0 flex-1 truncate">{p.name ?? 'Page'}</span>
                        </button>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </section>

          {/* LAYERS Section */}
          <section>
            <div className="flex items-center pl-1 pr-0.5">
              <button
                type="button"
                className="flex flex-1 items-center gap-1 rounded-md py-1 pl-0.5 pr-1 text-left hover:text-foreground"
                onClick={() => setLayersOpen((o) => !o)}
                aria-expanded={layersOpen}
              >
                {layersOpen ? (
                  <ChevronDown className="size-3 shrink-0 text-muted-foreground" aria-hidden />
                ) : (
                  <ChevronRight className="size-3 shrink-0 text-muted-foreground" aria-hidden />
                )}
                <span className="text-[0.7rem] font-semibold uppercase tracking-wider text-muted-foreground">
                  Layers
                </span>
              </button>
            </div>

            {layersOpen && (
              <div className="mt-0.5">
                {!page && (
                  <p className="px-2 py-1 text-xs text-muted-foreground">Loading page…</p>
                )}
                {page && shapeLayers.length === 0 && (
                  <p className="px-2 py-1 text-xs text-muted-foreground">No layers yet</p>
                )}
                {page && shapeLayers.length > 0 && (
                  <div className="relative">
                    <ul className="list-none space-y-0.5 p-0">
                      {shapeLayers.map(({ node, depth }) => {
                        const active = selectedIds.has(node.id)
                        return (
                          <li key={node.id}>
                            <LayerRow
                              node={node}
                              depth={depth}
                              active={active}
                              selectedIds={selectedIds}
                              objects={page.objects as Record<string, IndexedShape>}
                              dragOver={dragOver}
                              onSelect={onLayerRowClick}
                              onDragStart={onLayerDragStart}
                              onDragOver={setDragOver}
                              onDragEnd={onLayerDragEnd}
                              onDrop={onLayerDrop}
                            />
                          </li>
                        )
                      })}
                    </ul>
                    {draggingInPanel && (
                      <div
                        onDragOver={(e) => {
                          if (!e.dataTransfer.types.includes('application/x-skia-layer-ids')) return
                          e.preventDefault()
                          e.dataTransfer.dropEffect = 'move'
                          if (!rootDropActive) setRootDropActive(true)
                        }}
                        onDragLeave={() => setRootDropActive(false)}
                        onDrop={(e) => {
                          if (!e.dataTransfer.types.includes('application/x-skia-layer-ids')) return
                          e.preventDefault()
                          const raw = e.dataTransfer.getData('application/x-skia-layer-ids')
                          let ids: string[] = []
                          try {
                            const parsed: unknown = JSON.parse(raw)
                            if (Array.isArray(parsed)) {
                              ids = parsed.filter((x): x is string => typeof x === 'string')
                            }
                          } catch {
                            ids = []
                          }
                          void handleRootDrop(ids)
                        }}
                        className={cn(
                          'sticky bottom-0 z-10 mt-1 flex min-h-[32px] items-center justify-center rounded-md border border-dashed text-[0.7rem] uppercase tracking-wider backdrop-blur transition-colors',
                          rootDropActive
                            ? 'border-blue-500 bg-blue-50/90 text-blue-700 dark:bg-blue-950/60 dark:text-blue-300'
                            : 'border-muted-foreground/25 bg-background/80 text-muted-foreground/60',
                        )}
                      >
                        {rootDropActive ? 'Move to root' : 'Drop here for root'}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </section>
        </div>
      </ScrollArea>
    </FloatingEditorRail>
  )
}
