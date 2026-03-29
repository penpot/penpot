import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSelector } from '@xstate/react'
import type { PenpotNode } from 'penpot-exporter/types'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { applyTransformToNode } from '../../../renderer/geom/apply-transform-to-node'
import { rotationMatrixAroundPoint, translateMatrix } from '../../../renderer/geom/matrix'
import { useCanvasActor } from '../../../renderer/machine/canvas-actor-context'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
  rectLayoutPartial,
} from '../../../renderer/properties/commit-node-properties'
import type { RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import {
  movePreviewWorldDelta as movePreviewWorldDeltaSignal,
  rotatePreviewDeltaDeg as rotatePreviewDeltaDegSignal,
} from '../../../renderer/signals/pointer'
import { useSignalCoalesced } from '../../../renderer/signals/use-signal-coalesced'

export interface NodeLayoutSectionProps {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}

export function NodeLayoutSection({ nodeId, initialNode, readOnly }: NodeLayoutSectionProps) {
  const [x, setX] = useState(() => initialNode.x ?? 0)
  const [y, setY] = useState(() => initialNode.y ?? 0)
  const [width, setWidth] = useState(() => initialNode.width ?? 100)
  const [height, setHeight] = useState(() => initialNode.height ?? 100)
  const [rotation, setRotation] = useState(() => initialNode.rotation ?? 0)

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- mirrors external document updates into layout fields */
    setX(initialNode.x ?? 0)
    setY(initialNode.y ?? 0)
    setWidth(initialNode.width ?? 100)
    setHeight(initialNode.height ?? 100)
    setRotation(initialNode.rotation ?? 0)
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialNode])

  const canvasActor = useCanvasActor()
  const isMoving = useSelector(canvasActor, (s) => s.matches('moving'))
  const isRotating = useSelector(canvasActor, (s) => s.matches('rotating'))
  const rotatePreviewDeltaDeg = useSignalCoalesced(rotatePreviewDeltaDegSignal)
  const movePreviewCoalesced = useSignalCoalesced(movePreviewWorldDeltaSignal)

  const liveLayoutPartial = useMemo((): Partial<PenpotNode> | null => {
    if (readOnly) return null
    const node = initialNode as PenpotNode
    if (isRotating) {
      const sr = node.selrect as
        | { x?: number; y?: number; width?: number; height?: number }
        | undefined
      if (!sr) return null
      const x0 = sr.x ?? 0
      const y0 = sr.y ?? 0
      const w0 = sr.width ?? 0
      const h0 = sr.height ?? 0
      if (w0 <= 0 || h0 <= 0) return null
      const cx = x0 + w0 / 2
      const cy = y0 + h0 / 2
      return applyTransformToNode(node, rotationMatrixAroundPoint(cx, cy, rotatePreviewDeltaDeg))
    }
    if (isMoving) {
      return applyTransformToNode(
        node,
        translateMatrix(movePreviewCoalesced.x, movePreviewCoalesced.y),
      )
    }
    return null
  }, [readOnly, initialNode, isRotating, isMoving, rotatePreviewDeltaDeg, movePreviewCoalesced])

  const xDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.x === 'number' ? liveLayoutPartial.x : x
  const yDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.y === 'number' ? liveLayoutPartial.y : y
  const widthDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.width === 'number'
      ? liveLayoutPartial.width
      : width
  const heightDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.height === 'number'
      ? liveLayoutPartial.height
      : height
  const rotationDisplay =
    liveLayoutPartial != null && typeof liveLayoutPartial.rotation === 'number'
      ? liveLayoutPartial.rotation
      : rotation

  const layoutFieldsDisabled = readOnly || isMoving || isRotating

  const commitLayout = useCallback(async () => {
    if (readOnly) return
    const before = getCommittedNodeOnActivePage(nodeId)
    const pid = getActiveOrSinglePageId()
    if (!before || !pid) return
    const partial = rectLayoutPartial(x, y, width, height, rotation)
    await commitNodePartialUpdate(nodeId, before, partial, pid)
  }, [readOnly, nodeId, x, y, width, height, rotation])

  return (
    <>
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="rsp-x">X</Label>
          <Input
            id="rsp-x"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(xDisplay) ? xDisplay : 0}
            onChange={(e) => setX(parseFloat(e.target.value) || 0)}
            onBlur={() => void commitLayout()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="rsp-y">Y</Label>
          <Input
            id="rsp-y"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(yDisplay) ? yDisplay : 0}
            onChange={(e) => setY(parseFloat(e.target.value) || 0)}
            onBlur={() => void commitLayout()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="rsp-w">W</Label>
          <Input
            id="rsp-w"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(widthDisplay) ? widthDisplay : 0}
            onChange={(e) => setWidth(Math.max(1, parseFloat(e.target.value) || 1))}
            onBlur={() => void commitLayout()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="rsp-h">H</Label>
          <Input
            id="rsp-h"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(heightDisplay) ? heightDisplay : 0}
            onChange={(e) => setHeight(Math.max(1, parseFloat(e.target.value) || 1))}
            onBlur={() => void commitLayout()}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="rsp-rot">Rotation (deg)</Label>
        <Input
          id="rsp-rot"
          type="number"
          disabled={layoutFieldsDisabled}
          value={Number.isFinite(rotationDisplay) ? rotationDisplay : 0}
          onChange={(e) => setRotation(parseFloat(e.target.value) || 0)}
          onBlur={() => void commitLayout()}
        />
      </div>
    </>
  )
}
