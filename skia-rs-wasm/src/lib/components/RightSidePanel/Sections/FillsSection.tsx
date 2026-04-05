import { useCallback, useEffect, useState } from 'react'
import type { Fill } from 'penpot-exporter/types'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import { DEFAULT_FILL, MAX_FILLS, type RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import { FillRow } from './FillRow'
import { useColorEditor } from '../use-color-editor'

export interface FillsSectionProps {
  nodeId: string
  readOnly: boolean
  initialNode: RectLikeNode
}

export function FillsSection({ nodeId, readOnly, initialNode }: FillsSectionProps) {
  const { activeTarget, closeEditor } = useColorEditor()
  const [fills, setFills] = useState<Fill[]>(() =>
    initialNode.fills ? [...initialNode.fills] : [],
  )
  const [collapsed, setCollapsed] = useState(false)

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- mirrors external document updates into controlled fields */
    setFills(initialNode.fills ? [...initialNode.fills] : [])
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialNode])

  const commitFills = useCallback(
    async (next: Fill[]) => {
      if (readOnly) return
      const before = getCommittedNodeOnActivePage(nodeId)
      const pid = getActiveOrSinglePageId()
      if (!before || !pid) return
      await commitNodePartialUpdate(
        nodeId,
        before,
        { fills: next },
        pid,
      )
    },
    [readOnly, nodeId],
  )

  const onFillChange = useCallback(
    (fill: Fill, index: number) => {
      const next = [...fills]
      if (index < 0 || index >= next.length) return
      next[index] = fill
      setFills(next)
      void commitFills(next)
    },
    [fills, commitFills],
  )

  const addFill = useCallback(() => {
    if (fills.length >= MAX_FILLS) return
    const next = [...fills, DEFAULT_FILL]
    setFills(next)
    void commitFills(next)
  }, [fills, commitFills])

  const removeFill = useCallback(
    (index: number) => {
      if (activeTarget?.kind === 'fill' && activeTarget.index === index) closeEditor()
      const next = fills.filter((_, i) => i !== index)
      setFills(next)
      void commitFills(next)
    },
    [fills, commitFills, activeTarget, closeEditor],
  )

  const hasFills = fills.length > 0
  const canAdd = !readOnly && fills.length < MAX_FILLS

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
            Fill
          </button>
          {!readOnly && (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={addFill}
              disabled={!canAdd}
              aria-label="Add fill"
              title={canAdd ? 'Add fill' : `Maximum ${MAX_FILLS} fills`}
            >
              +
            </Button>
          )}
        </div>

        {!collapsed && hasFills && (
          <div className="space-y-2 pl-0.5">
            {fills.map((fill, i) => (
              <FillRow
                key={i}
                fill={fill}
                index={i}
                readOnly={readOnly}
                onChange={onFillChange}
                onRemove={removeFill}
              />
            ))}
          </div>
        )}

        {!collapsed && !hasFills && !readOnly && (
          <p className="text-xs text-muted-foreground">No fills. Use + to add.</p>
        )}
      </div>
    </>
  )
}
