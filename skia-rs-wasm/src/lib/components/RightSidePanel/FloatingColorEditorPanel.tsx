import { useCallback, useEffect, useRef, useState } from 'react'
import type { Fill } from 'penpot-exporter/types'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { FillEditor } from '../FillEditor/FillEditor'
import { useColorEditor } from './use-color-editor'

export function FloatingColorEditorPanel() {
  const { activeTarget, activeFill, anchorY, title, closeEditor, onChangeRef } = useColorEditor()
  const panelRef = useRef<HTMLDivElement>(null)

  // Stable key for effect dependencies
  const targetKey = activeTarget && activeFill ? `${activeTarget.kind}-${activeTarget.index}` : null

  // Drag state
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  const dragRef = useRef<{ startX: number; startY: number; origX: number; origY: number } | null>(null)

  // Reset position when a new editor opens
  useEffect(() => {
    setPos(null)
  }, [targetKey])

  // Close on click-outside (ignore clicks inside the right panel)
  useEffect(() => {
    if (!targetKey) return

    function handleMouseDown(e: MouseEvent) {
      const target = e.target as Node
      if (panelRef.current?.contains(target)) return
      const rightPanel = document.querySelector('[data-right-side-panel]')
      if (rightPanel?.contains(target)) return
      closeEditor()
    }

    document.addEventListener('mousedown', handleMouseDown)
    return () => document.removeEventListener('mousedown', handleMouseDown)
  }, [targetKey, closeEditor])

  const handleChange = useCallback(
    (next: Fill) => {
      onChangeRef.current?.(next)
    },
    [onChangeRef],
  )

  const onDragStart = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const panel = panelRef.current
      if (!panel) return
      const rect = panel.getBoundingClientRect()
      dragRef.current = { startX: e.clientX, startY: e.clientY, origX: rect.left, origY: rect.top }
      e.currentTarget.setPointerCapture(e.pointerId)
    },
    [],
  )

  const onDragMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current) return
    const dx = e.clientX - dragRef.current.startX
    const dy = e.clientY - dragRef.current.startY
    const w = 284
    const minH = 200
    const rawX = dragRef.current.origX + dx
    const rawY = dragRef.current.origY + dy
    setPos({
      x: Math.max(0, Math.min(rawX, window.innerWidth - w)),
      y: Math.max(0, Math.min(rawY, window.innerHeight - minH)),
    })
  }, [])

  const onDragEnd = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    dragRef.current = null
    e.currentTarget.releasePointerCapture(e.pointerId)
  }, [])

  if (!activeTarget || !activeFill) return null

  const defaultTop = Math.max(12, Math.min(anchorY, window.innerHeight - 400))

  const positionStyle: React.CSSProperties = pos
    ? { left: pos.x, top: pos.y }
    : {
        right: 'calc(0.75rem + var(--properties-panel-width, 280px) + 0.5rem)',
        top: defaultTop,
      }

  return (
    <div
      ref={panelRef}
      className="pointer-events-auto fixed z-100 flex flex-col overflow-hidden rounded-lg border border-border/80 bg-white text-card-foreground shadow-md"
      style={{
        width: 284,
        minHeight: 200,
        maxHeight: `calc(100vh - ${pos ? pos.y : defaultTop}px - 0.75rem)`,
        ...positionStyle,
      }}
    >
      <div
        className="flex shrink-0 cursor-grab items-center justify-between gap-1 border-b border-border px-3 py-2 active:cursor-grabbing"
        onPointerDown={onDragStart}
        onPointerMove={onDragMove}
        onPointerUp={onDragEnd}
        onLostPointerCapture={onDragEnd}
      >
        <h2 className="min-w-0 flex-1 truncate text-sm font-semibold tracking-tight text-foreground select-none">
          {title}
        </h2>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0 text-muted-foreground"
          aria-label="Close color editor"
          onClick={closeEditor}
          onPointerDown={(e) => e.stopPropagation()}
        >
          <X className="size-4" />
        </Button>
      </div>
      <div className="overflow-y-auto p-3">
        <FillEditor fill={activeFill} onChange={handleChange} />
      </div>
    </div>
  )
}
