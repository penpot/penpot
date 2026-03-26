/**
 * Left rail: page / layer tree, page metadata, layer selection.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import type { IndexedNode, IndexedPage } from '../../worker/types'
import { useWorkspaceStore } from '../../renderer/store/workspace-store'
import { useWorkspaceDevStore } from '../../renderer/store/workspace-dev-store'
import { FloatingEditorRail } from '../editor-shell/floating-editor-rail'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { ChevronRight, Layers } from 'lucide-react'
import { commitPageMetadataUpdate } from '../shape-properties-panel/commit-page-properties'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

function normalizeHex(input: string): string {
  let s = input.trim()
  if (!s.startsWith('#')) s = `#${s}`
  if (/^#[0-9A-Fa-f]{3}$/.test(s)) {
    const r = s[1],
      g = s[2],
      b = s[3]
    s = `#${r}${r}${g}${g}${b}${b}`
  }
  if (/^#[0-9A-Fa-f]{6}$/.test(s)) return s
  return '#FFFFFF'
}

export interface LayersPanelProps {
  className?: string
}

export function LayersPanel({ className }: LayersPanelProps) {
  const pageId = useWorkspaceStore((s) => s.pageId)
  const documentModel = useWorkspaceStore((s) => s.documentModel)
  const setSelectedIds = useWorkspaceStore((s) => s.setSelectedIds)
  const selectedIds = useWorkspaceStore((s) => s.selectedIds)
  const currentPageNodes = useWorkspaceDevStore((s) => s.currentPageNodes)

  const [collapsed, setCollapsed] = useState(false)
  const [pageSectionOpen, setPageSectionOpen] = useState(true)
  const [pageName, setPageName] = useState('')
  const [pageBg, setPageBg] = useState('#FFFFFF')

  const resolvePageId = useCallback((): string | null => {
    return pageId ?? documentModel?.getActiveOrSinglePageId() ?? null
  }, [pageId, documentModel])

  useEffect(() => {
    const pid = resolvePageId()
    if (!pid || !documentModel) return
    const page = documentModel.getPage(pid)
    if (!page) return
    setPageName(page.name ?? 'Page')
    setPageBg(page.background ?? '#FFFFFF')
  }, [documentModel, pageId, resolvePageId, currentPageNodes])

  const shapeLayers = useMemo(
    () => currentPageNodes.filter((n) => n.id !== ROOT_UUID),
    [currentPageNodes],
  )

  const layerCount = shapeLayers.length

  const pid = resolvePageId()
  const page: IndexedPage | undefined = pid && documentModel ? documentModel.getPage(pid) : undefined
  const displayPageName = page?.name ?? pageName ?? 'Page'

  const commitPageName = useCallback(async () => {
    if (!pid || !documentModel) return
    const p = documentModel.getPage(pid)
    if (!p) return
    const trimmed = pageName.trim()
    if (trimmed === (p.name ?? '')) return
    await commitPageMetadataUpdate(pid, p, { name: trimmed || 'Page' })
  }, [documentModel, pageName, pid])

  const commitPageBackground = useCallback(async () => {
    if (!pid || !documentModel) return
    const p = documentModel.getPage(pid)
    if (!p) return
    const next = normalizeHex(pageBg)
    if (next === (p.background ?? '#FFFFFF')) return
    await commitPageMetadataUpdate(pid, p, { background: next })
  }, [documentModel, pageBg, pid])

  const onPageBgColorPick = useCallback(
    (hex: string) => {
      setPageBg(hex)
      if (!pid || !documentModel) return
      const p = documentModel.getPage(pid)
      if (!p) return
      void commitPageMetadataUpdate(pid, p, { background: hex })
    },
    [documentModel, pid],
  )

  const onLayerRowClick = useCallback(
    (id: string) => {
      setSelectedIds(new Set([id]))
    },
    [setSelectedIds],
  )

  const footer =
    layerCount === 1 ? '1 layer' : `${layerCount} layers`

  return (
    <FloatingEditorRail
      side="left"
      title="Layers"
      collapsed={collapsed}
      onCollapsedChange={setCollapsed}
      footer={footer}
      data-layers-panel
      className={cn('min-h-0', className)}
    >
      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-1 p-2">
          <div className="rounded-lg border border-transparent">
            <button
              type="button"
              className="flex w-full items-center gap-1 rounded-md px-1 py-1.5 text-left text-sm hover:bg-muted/60"
              onClick={() => setPageSectionOpen((o) => !o)}
              aria-expanded={pageSectionOpen}
            >
              <ChevronRight
                className={cn('size-4 shrink-0 text-muted-foreground transition-transform', pageSectionOpen && 'rotate-90')}
              />
              <Layers className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <span className="min-w-0 flex-1 truncate font-medium text-foreground">{displayPageName}</span>
            </button>

            {pageSectionOpen && (
              <div className="space-y-3 border-l border-border/60 pb-2 pl-6 pr-1 pt-0.5">
                {!page && <p className="text-xs text-muted-foreground">Loading page…</p>}
                {page && (
                  <>
                    <div>
                      {shapeLayers.length === 0 ? (
                        <p className="text-xs text-muted-foreground">No layers yet</p>
                      ) : (
                        <ul className="list-none space-y-0.5 p-0">
                          {shapeLayers.map((node: IndexedNode) => {
                            const active = selectedIds.has(node.id)
                            return (
                              <li key={node.id}>
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="sm"
                                  className={cn(
                                    'h-8 w-full justify-start px-2 font-normal',
                                    active && 'bg-muted/80 text-foreground',
                                  )}
                                  onClick={() => onLayerRowClick(node.id)}
                                >
                                  <span className="truncate">{node.name ?? 'Shape'}</span>
                                </Button>
                              </li>
                            )
                          })}
                        </ul>
                      )}
                    </div>

                    <Separator />
                    <div className="space-y-2">
                      <Label htmlFor="layers-page-name" className="text-xs">
                        Page name
                      </Label>
                      <Input
                        id="layers-page-name"
                        value={pageName}
                        onChange={(e) => setPageName(e.target.value)}
                        onBlur={() => void commitPageName()}
                        className="h-8 text-sm"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="layers-page-bg" className="text-xs">
                        Background
                      </Label>
                      <div className="flex min-w-0 items-center gap-2">
                        <input
                          id="layers-page-bg"
                          type="color"
                          className="h-8 w-10 shrink-0 cursor-pointer rounded border border-border bg-transparent p-0.5"
                          value={normalizeHex(pageBg)}
                          onChange={(e) => onPageBgColorPick(e.target.value)}
                          aria-label="Background color"
                        />
                        <Input
                          className="h-8 min-w-0 flex-1 font-mono text-xs"
                          value={pageBg}
                          onChange={(e) => setPageBg(e.target.value)}
                          onBlur={() => void commitPageBackground()}
                          placeholder="#FFFFFF"
                        />
                      </div>
                    </div>
                  </>
                )}
              </div>
            )}
          </div>
        </div>
      </ScrollArea>
    </FloatingEditorRail>
  )
}
