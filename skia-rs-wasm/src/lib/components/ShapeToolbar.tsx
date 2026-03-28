/**
 * Bottom pill tool strip for creation tools (reference editor UI).
 * Extend `DrawTool` in canvas-machine when adding new shape icons.
 */

import { useCallback, type ReactNode } from 'react'
import { useSelector } from '@xstate/react'
import {
  Circle,
  Hexagon,
  Image,
  MessageCircle,
  Minus,
  Pencil,
  Star,
  Triangle,
  Type,
} from 'lucide-react'
import { useCanvasActor } from '../renderer/machine/canvas-actor-context'
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

const placeholderTitle = 'Coming soon'

export function ShapeToolbar() {
  const canvasActor = useCanvasActor()
  const drawTool = useSelector(canvasActor, (s) => s.context.drawTool)

  const syncCanvasCursor = useCallback((tool: 'rect' | null) => {
    const canvas = document.querySelector('.canvas-container canvas') as HTMLCanvasElement | null
    if (canvas) canvas.style.cursor = tool === 'rect' ? 'crosshair' : 'default'
  }, [])

  const onSelect = useCallback(() => {
    canvasActor.send({ type: 'DRAW_TOOL_DEACTIVATE' })
    syncCanvasCursor(null)
  }, [canvasActor, syncCanvasCursor])

  const onRect = useCallback(() => {
    const active = canvasActor.getSnapshot().context.drawTool === 'rect'
    if (active) {
      canvasActor.send({ type: 'DRAW_TOOL_DEACTIVATE' })
      syncCanvasCursor(null)
    } else {
      canvasActor.send({ type: 'DRAW_TOOL_ACTIVATE', tool: 'rect' })
      syncCanvasCursor('rect')
    }
  }, [canvasActor, syncCanvasCursor])

  const toolBtn = (
    pressed: boolean,
    onClick: () => void,
    label: string,
    children: ReactNode,
  ) => (
    <li>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className={cn(
          'h-10 w-10 rounded-full text-muted-foreground',
          pressed && 'bg-blue-100 text-blue-700 hover:bg-blue-100 dark:bg-blue-950 dark:text-blue-200',
        )}
        title={label}
        aria-label={label}
        aria-pressed={pressed}
        onClick={onClick}
      >
        {children}
      </Button>
    </li>
  )

  const disabledTool = (label: string, Icon: React.ComponentType<{ className?: string }>) => (
    <li>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-10 w-10 rounded-full text-muted-foreground/50"
        disabled
        title={placeholderTitle}
        aria-label={label}
      >
        <Icon className="size-5 shrink-0 stroke-[1.5]" />
      </Button>
    </li>
  )

  return (
    <aside
      className="pointer-events-auto fixed bottom-6 left-1/2 z-60 -translate-x-1/2"
      aria-label="Shape tools"
    >
      <ul className="flex list-none flex-row items-center gap-0.5 rounded-full border border-border/80 bg-white px-2 py-1.5 shadow-md">
        {toolBtn(drawTool == null, onSelect, 'Select and move', <IconSelect className="shrink-0" />)}
        {toolBtn(drawTool === 'rect', onRect, 'Draw rectangle', <IconRect className="shrink-0" />)}
        {disabledTool('Ellipse', Circle)}
        {disabledTool('Triangle', Triangle)}
        {disabledTool('Star', Star)}
        {disabledTool('Polygon', Hexagon)}
        {disabledTool('Line', Minus)}
        {disabledTool('Draw', Pencil)}
        {disabledTool('Text', Type)}
        {disabledTool('Image', Image)}
        {disabledTool('Comment', MessageCircle)}
      </ul>
    </aside>
  )
}
