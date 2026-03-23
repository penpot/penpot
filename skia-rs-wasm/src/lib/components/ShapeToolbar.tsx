/**
 * Top-style tool strip for creation tools (Penpot workspace toolbar pattern).
 * Extend `DrawTool` in workspace-store when adding new shape icons.
 */

import { useCallback } from 'react'
import { useWorkspaceStore } from '../renderer/store/workspace-store'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

function IconSelect({ className }: { className?: string }) {
  return (
    <svg className={className} width="20" height="20" viewBox="0 0 20 20" aria-hidden>
      <path
        fill="currentColor"
        d="M4.5 3.5L15 11h-4.25L12 16.5 4.5 9.25H8.75L4.5 3.5z"
      />
    </svg>
  )
}

function IconRect({ className }: { className?: string }) {
  return (
    <svg className={className} width="20" height="20" viewBox="0 0 20 20" aria-hidden>
      <rect
        x="3.5"
        y="4.5"
        width="13"
        height="12"
        rx="1.5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.75"
      />
    </svg>
  )
}

export function ShapeToolbar() {
  const drawTool = useWorkspaceStore((s) => s.drawTool)
  const setDrawTool = useWorkspaceStore((s) => s.setDrawTool)

  const syncCanvasCursor = useCallback((tool: 'rect' | null) => {
    const canvas = document.querySelector('.canvas-container canvas') as HTMLCanvasElement | null
    if (canvas) canvas.style.cursor = tool === 'rect' ? 'crosshair' : 'default'
  }, [])

  const onSelect = useCallback(() => {
    setDrawTool(null)
    syncCanvasCursor(null)
  }, [setDrawTool, syncCanvasCursor])

  const onRect = useCallback(() => {
    const current = useWorkspaceStore.getState().drawTool
    const next = current === 'rect' ? null : 'rect'
    setDrawTool(next)
    syncCanvasCursor(next)
  }, [setDrawTool, syncCanvasCursor])

  return (
    <aside className="absolute top-3 left-1/2 z-20 -translate-x-1/2 pointer-events-auto" aria-label="Shape tools">
      <ul className="flex list-none flex-row items-center gap-0.5 rounded-lg border border-border bg-background p-1 shadow-sm">
        <li>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className={cn(
              'h-9 w-9 rounded-md text-muted-foreground',
              drawTool == null && 'bg-blue-100 text-blue-700 hover:bg-blue-100 dark:bg-blue-950 dark:text-blue-200'
            )}
            title="Select / move (Esc)"
            aria-label="Select and move"
            aria-pressed={drawTool == null}
            onClick={onSelect}
          >
            <IconSelect className="shrink-0" />
          </Button>
        </li>
        <li>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className={cn(
              'h-9 w-9 rounded-md text-muted-foreground',
              drawTool === 'rect' && 'bg-blue-100 text-blue-700 hover:bg-blue-100 dark:bg-blue-950 dark:text-blue-200'
            )}
            title="Rectangle (R)"
            aria-label="Draw rectangle"
            aria-pressed={drawTool === 'rect'}
            data-tool="rect"
            onClick={onRect}
          >
            <IconRect className="shrink-0" />
          </Button>
        </li>
      </ul>
    </aside>
  )
}
