/**
 * Shared floating card shell for Layers / Design rails (reference editor UI).
 */

import { ChevronDown, ChevronUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type FloatingEditorRailSide = 'left' | 'right'

export interface FloatingEditorRailProps extends React.ComponentProps<'aside'> {
  side: FloatingEditorRailSide
  title: string
  collapsed: boolean
  onCollapsedChange: (collapsed: boolean) => void
  children?: React.ReactNode
  footer?: React.ReactNode
}

export function FloatingEditorRail({
  side,
  title,
  collapsed,
  onCollapsedChange,
  children,
  footer,
  className,
  ...rest
}: FloatingEditorRailProps) {
  return (
    <aside
      className={cn(
        'pointer-events-auto fixed top-3 bottom-3 z-50 flex flex-col overflow-hidden rounded-2xl border border-border/80 bg-white text-card-foreground shadow-md',
        side === 'left'
          ? 'left-3 w-(--layers-panel-width,280px)'
          : 'right-3 w-(--properties-panel-width,280px)',
        collapsed && 'w-auto min-w-11 max-w-11',
        className,
      )}
      {...rest}
    >
      <div
        className={cn(
          'flex shrink-0 items-center border-b border-border py-2',
          collapsed ? 'justify-center px-1' : 'justify-between gap-1 px-2 pl-3',
        )}
      >
        {!collapsed && (
          <h2 className="min-w-0 flex-1 truncate text-sm font-semibold tracking-tight text-foreground">
            {title}
          </h2>
        )}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0 text-muted-foreground"
          aria-label={collapsed ? 'Expand panel' : 'Collapse panel'}
          aria-expanded={!collapsed}
          onClick={() => onCollapsedChange(!collapsed)}
        >
          {collapsed ? <ChevronDown className="size-4" /> : <ChevronUp className="size-4" />}
        </Button>
      </div>
      {!collapsed && (
        <>
          <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">{children}</div>
          {footer != null ? (
            <div className="shrink-0 border-t border-border px-3 py-2 text-xs text-muted-foreground">
              {footer}
            </div>
          ) : null}
        </>
      )}
    </aside>
  )
}
