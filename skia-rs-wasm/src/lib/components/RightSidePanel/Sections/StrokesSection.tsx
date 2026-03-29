import { useCallback, useEffect, useState } from 'react'
import type { Stroke } from 'penpot-exporter/types'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { StrokeEditor } from '../../StrokeEditor/StrokeEditor'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import type { RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'

export interface StrokesSectionProps {
  nodeId: string
  readOnly: boolean
  initialNode: RectLikeNode
}

export function StrokesSection({ nodeId, readOnly, initialNode }: StrokesSectionProps) {
  const [strokes, setStrokes] = useState<Stroke[]>(() =>
    initialNode.strokes ? [...initialNode.strokes] : [],
  )

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- mirrors external document updates */
    setStrokes(initialNode.strokes ? [...initialNode.strokes] : [])
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialNode])

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

  const onFirstStrokeChange = useCallback(
    (stroke: Stroke) => {
      const next = [...strokes]
      if (next.length === 0) next.push(stroke)
      else next[0] = stroke
      setStrokes(next)
      void commitStrokes(next)
    },
    [strokes, commitStrokes],
  )

  if (strokes.length === 0) return null

  const first = strokes[0]!

  return (
    <>
      <Separator />
      <div className="space-y-2">
        <Label>Stroke</Label>
        <StrokeEditor stroke={first} readOnly={readOnly} onChange={onFirstStrokeChange} />
      </div>
    </>
  )
}
