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
import type { DrawTool } from '../renderer/machine/canvas-machine'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { IconFrame, IconRect, IconSelect } from './shape-icons'

const placeholderTitle = 'Coming soon'

export function ShapeToolbar() {
  const canvasActor = useCanvasActor()
  const drawTool = useSelector(canvasActor, (s) => s.context.drawTool)

  const syncCanvasCursor = useCallback((tool: DrawTool | null) => {
    const canvas = document.querySelector('.canvas-container canvas') as HTMLCanvasElement | null
    if (canvas) canvas.style.cursor = tool != null ? 'crosshair' : 'default'
  }, [])

  const onSelect = useCallback(() => {
    canvasActor.send({ type: 'DRAW_TOOL_DEACTIVATE' })
    syncCanvasCursor(null)
  }, [canvasActor, syncCanvasCursor])

  const toggleDrawTool = useCallback(
    (tool: DrawTool) => {
      const active = canvasActor.getSnapshot().context.drawTool === tool
      if (active) {
        canvasActor.send({ type: 'DRAW_TOOL_DEACTIVATE' })
        syncCanvasCursor(null)
      } else {
        canvasActor.send({ type: 'DRAW_TOOL_ACTIVATE', tool })
        syncCanvasCursor(tool)
      }
    },
    [canvasActor, syncCanvasCursor],
  )

  const onRect = useCallback(() => toggleDrawTool('rect'), [toggleDrawTool])
  const onFrame = useCallback(() => toggleDrawTool('frame'), [toggleDrawTool])

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
        {toolBtn(drawTool === 'frame', onFrame, 'Draw frame (F)', <IconFrame className="shrink-0" />)}
        {toolBtn(drawTool === 'rect', onRect, 'Draw rectangle (R)', <IconRect className="shrink-0" />)}
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
