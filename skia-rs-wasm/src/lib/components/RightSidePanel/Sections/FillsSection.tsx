import { useCallback, useRef, useState } from 'react'
import type { Fill } from 'penpot-exporter/types'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { FillEditor } from '../../FillEditor/FillEditor'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import { DEFAULT_FILL, type RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'

export interface FillsSectionProps {
  nodeId: string
  readOnly: boolean
  initialNode: RectLikeNode
}

export function FillsSection({ nodeId, readOnly, initialNode }: FillsSectionProps) {
  const [fills, setFills] = useState<Fill[]>(() =>
    initialNode.fills ? [...initialNode.fills] : [],
  )

  // Render-phase sync: when initialNode changes externally, reset optimistic local state.
  // React re-renders immediately when setState is called during render, and the ref
  // prevents the condition from firing again on that follow-up render.
  const prevNodeRef = useRef(initialNode)
  if (prevNodeRef.current !== initialNode) {
    prevNodeRef.current = initialNode
    setFills(initialNode.fills ? [...initialNode.fills] : [])
  }

  const commitFills = useCallback(
    async (next: Fill[]) => {
      if (readOnly) return
      const before = getCommittedNodeOnActivePage(nodeId)
      const pid = getActiveOrSinglePageId()
      if (!before || !pid) return
      await commitNodePartialUpdate(
        nodeId,
        before,
        { fills: next.length > 0 ? next : undefined },
        pid,
      )
    },
    [readOnly, nodeId],
  )

  const onFillChange = useCallback(
    (fill: Fill) => {
      const next = [...fills]
      if (next.length === 0) next.push(fill)
      else next[0] = fill
      setFills(next)
      void commitFills(next)
    },
    [fills, commitFills],
  )

  const addFill = useCallback(() => {
    const next = [...fills, DEFAULT_FILL]
    setFills(next)
    void commitFills(next)
  }, [fills, commitFills])

  const removeFills = useCallback(() => {
    setFills([])
    void commitFills([])
  }, [commitFills])

  return (
    <>
      <Separator />
      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <Label>Fill</Label>
          {!readOnly && (
            <div className="flex gap-1">
              {fills.length === 0 ? (
                <Button type="button" variant="outline" size="sm" onClick={addFill}>
                  Add fill
                </Button>
              ) : (
                <Button type="button" variant="ghost" size="sm" onClick={removeFills}>
                  Clear
                </Button>
              )}
            </div>
          )}
        </div>
        {!readOnly && fills.length > 0 && <FillEditor fill={fills[0]!} onChange={onFillChange} />}
        {readOnly && fills.length > 0 && (
          <p className="text-xs text-muted-foreground">Fill present (read-only)</p>
        )}
      </div>
    </>
  )
}
