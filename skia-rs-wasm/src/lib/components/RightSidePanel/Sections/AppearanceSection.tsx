import { useCallback, useMemo, useState } from 'react'
import { useSelector } from '@xstate/react'
import type { PenpotNode } from 'penpot-exporter/types'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { applyTransformToNode } from '../../../renderer/geom/apply-transform-to-node'
import { rotationMatrixAroundPoint } from '../../../renderer/geom/matrix'
import { useCanvasActor } from '../../../renderer/machine/canvas-actor-context'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
  rectLayoutPartial,
} from '../../../renderer/properties/commit-node-properties'
import type { RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import { rotatePreviewDeltaDeg as rotatePreviewDeltaDegSignal } from '../../../renderer/signals/pointer'
import { useSignalCoalesced } from '../../../renderer/signals/use-signal-coalesced'

type SizeDraft = { width: number; height: number; rotation: number }

export interface AppearanceSectionProps {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}

export function AppearanceSection({ nodeId, initialNode, readOnly }: AppearanceSectionProps) {
  const [collapsed, setCollapsed] = useState(false)
  const [draft, setDraft] = useState<SizeDraft | null>(null)

  const committed: SizeDraft = {
    width: initialNode.width ?? 100,
    height: initialNode.height ?? 100,
    rotation: initialNode.rotation ?? 0,
  }

  const { width, height, rotation } = draft ?? committed

  const canvasActor = useCanvasActor()
  const isMoving = useSelector(canvasActor, (s) => s.matches('moving'))
  const isRotating = useSelector(canvasActor, (s) => s.matches('rotating'))
  const rotatePreviewDeltaDeg = useSignalCoalesced(rotatePreviewDeltaDegSignal)

  const liveRotationPartial = useMemo((): Partial<PenpotNode> | null => {
    if (readOnly || !isRotating) return null
    const node = initialNode as PenpotNode
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
  }, [readOnly, initialNode, isRotating, rotatePreviewDeltaDeg])

  const rotationDisplay =
    liveRotationPartial != null && typeof liveRotationPartial.rotation === 'number'
      ? liveRotationPartial.rotation
      : rotation

  const fieldsDisabled = readOnly || isMoving || isRotating

  const commit = useCallback(async () => {
    if (readOnly || !draft) return
    const before = getCommittedNodeOnActivePage(nodeId)
    const pid = getActiveOrSinglePageId()
    if (!before || !pid) return
    const x = (before as { x?: number }).x ?? 0
    const y = (before as { y?: number }).y ?? 0
    await commitNodePartialUpdate(
      nodeId,
      before,
      rectLayoutPartial(x, y, draft.width, draft.height, draft.rotation),
      pid,
    )
    setDraft(null)
  }, [readOnly, nodeId, draft])

  const patchDraft = (patch: Partial<SizeDraft>) =>
    setDraft((d) => ({ ...(d ?? committed), ...patch }))

  return (
    <>
      <Separator />
      <div className="min-w-0 space-y-2">
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
            Appearance
          </button>
        </div>

        {!collapsed && (
          <>
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <Label htmlFor="rsp-w">W</Label>
                <Input
                  id="rsp-w"
                  type="number"
                  disabled={fieldsDisabled}
                  value={Number.isFinite(width) ? width : 0}
                  onChange={(e) =>
                    patchDraft({ width: Math.max(1, parseFloat(e.target.value) || 1) })
                  }
                  onBlur={() => void commit()}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="rsp-h">H</Label>
                <Input
                  id="rsp-h"
                  type="number"
                  disabled={fieldsDisabled}
                  value={Number.isFinite(height) ? height : 0}
                  onChange={(e) =>
                    patchDraft({ height: Math.max(1, parseFloat(e.target.value) || 1) })
                  }
                  onBlur={() => void commit()}
                />
              </div>
            </div>
            <div className="space-y-1">
              <Label htmlFor="rsp-rot">Rotation (deg)</Label>
              <Input
                id="rsp-rot"
                type="number"
                disabled={fieldsDisabled}
                value={Number.isFinite(rotationDisplay) ? rotationDisplay : 0}
                onChange={(e) => patchDraft({ rotation: parseFloat(e.target.value) || 0 })}
                onBlur={() => void commit()}
              />
            </div>
          </>
        )}
      </div>
    </>
  )
}
