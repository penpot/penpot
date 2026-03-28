import { useCallback, useState } from 'react'
import type { IndexedPage } from '../../worker/types'
import { docProxy } from '../../renderer/store/doc-proxy'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { commitPageMetadataUpdate } from '../../renderer/properties/commit-page-properties'
import { normalizeHex } from '../../renderer/properties/panel-utils'

export interface PagePropertyPanelProps {
  pageId: string
  initialPage: IndexedPage
}

export function PagePropertyPanel({ pageId, initialPage }: PagePropertyPanelProps) {
  const [pageName, setPageName] = useState(() => initialPage.name ?? 'Page')
  const [pageBg, setPageBg] = useState(() => initialPage.background ?? '#FFFFFF')

  const commitPageName = useCallback(async () => {
    const page = docProxy.pageMap.get(pageId)
    if (!page) return
    const trimmed = pageName.trim()
    if (trimmed === (page.name ?? '')) return
    await commitPageMetadataUpdate(pageId, page, { name: trimmed || 'Page' })
  }, [pageName, pageId])

  const commitPageBackground = useCallback(async () => {
    const page = docProxy.pageMap.get(pageId)
    if (!page) return
    const next = normalizeHex(pageBg)
    if (next === (page.background ?? '#FFFFFF')) return
    await commitPageMetadataUpdate(pageId, page, { background: next })
  }, [pageBg, pageId])

  const onPageBgColorPick = useCallback(
    (hex: string) => {
      setPageBg(hex)
      const page = docProxy.pageMap.get(pageId)
      if (!page) return
      void commitPageMetadataUpdate(pageId, page, { background: hex })
    },
    [pageId],
  )

  return (
    <>
      <div className="space-y-2">
        <Label htmlFor="rsp-page-name">Page name</Label>
        <Input
          id="rsp-page-name"
          value={pageName}
          onChange={(e) => setPageName(e.target.value)}
          onBlur={() => void commitPageName()}
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="rsp-page-bg">Background</Label>
        <div className="flex min-w-0 items-center gap-2">
          <input
            id="rsp-page-bg"
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
  )
}
