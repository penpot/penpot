import { useCallback, useRef, useState } from 'react'
import type { Stroke } from 'penpot-exporter/types'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import {
  DEFAULT_STROKE,
  MAX_STROKES,
  type RectLikeNode,
} from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import { StrokeRow } from './StrokeRow'
import { useColorEditor } from '../use-color-editor'

export interface StrokesSectionProps {
  nodeId: string
  readOnly: boolean
  initialNode: RectLikeNode
}

export function StrokesSection({ nodeId, readOnly, initialNode }: StrokesSectionProps) {
  const { activeTarget, closeEditor } = useColorEditor()
  const [strokes, setStrokes] = useState<Stroke[]>(() =>
    initialNode.strokes ? [...initialNode.strokes] : [],
  )
  const [collapsed, setCollapsed] = useState(false)

  // Render-phase sync: when initialNode changes externally, reset optimistic local state.
  const prevNodeRef = useRef(initialNode)
  if (prevNodeRef.current !== initialNode) {
    prevNodeRef.current = initialNode
    setStrokes(initialNode.strokes ? [...initialNode.strokes] : [])
  }

  const commitStrokes = useCallback(
    async (next: Stroke[]) => {
      if (readOnly) return
      const before = getCommittedNodeOnActivePage(nodeId)
      const pid = getActiveOrSinglePageId()
      if (!before || !pid) return
      await commitNodePartialUpdate(
        nodeId,
        before,
        { strokes: next.length > 0 ? next : undefined },
        pid,
      )
    },
    [readOnly, nodeId],
  )

  const onStrokeChange = useCallback(
    (stroke: Stroke, index: number) => {
      const next = [...strokes]
      if (index < 0 || index >= next.length) return
      next[index] = stroke
      setStrokes(next)
      void commitStrokes(next)
    },
    [strokes, commitStrokes],
  )

  const addStroke = useCallback(() => {
    if (strokes.length >= MAX_STROKES) return
    const next = [...strokes, DEFAULT_STROKE]
    setStrokes(next)
    void commitStrokes(next)
  }, [strokes, commitStrokes])

  const removeStroke = useCallback(
    (index: number) => {
      if (activeTarget?.kind === 'stroke' && activeTarget.index === index) closeEditor()
      const next = strokes.filter((_, i) => i !== index)
      setStrokes(next)
      void commitStrokes(next)
    },
    [strokes, commitStrokes, activeTarget, closeEditor],
  )

  const hasStrokes = strokes.length > 0
  const canAdd = !readOnly && strokes.length < MAX_STROKES

  return (
    <>
      <Separator />
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2 py-0.5">
          <button
            type="button"
            className="flex min-h-8 flex-1 items-center gap-1 text-left text-xs font-medium tracking-wide text-muted-foreground uppercase hover:text-foreground"
            onClick={() => setCollapsed((c) => !c)}
            aria-expanded={!collapsed}
          >
            {collapsed ? (
              <ChevronRight className="size-3.5 shrink-0" aria-hidden />
            ) : (
              <ChevronDown className="size-3.5 shrink-0" aria-hidden />
            )}
            Stroke
          </button>
          {!readOnly && (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={addStroke}
              disabled={!canAdd}
              aria-label="Add stroke"
              title={canAdd ? 'Add stroke' : `Maximum ${MAX_STROKES} strokes`}
            >
              +
            </Button>
          )}
        </div>

        {!collapsed && hasStrokes && (
          <div className="space-y-2 pl-0.5">
            {strokes.map((stroke, i) => (
              <StrokeRow
                key={i}
                stroke={stroke}
                index={i}
                readOnly={readOnly}
                onChange={onStrokeChange}
                onRemove={removeStroke}
              />
            ))}
          </div>
        )}

        {!collapsed && !hasStrokes && !readOnly && (
          <p className="text-xs text-muted-foreground">No strokes. Use + to add.</p>
        )}
      </div>
    </>
  )
}
