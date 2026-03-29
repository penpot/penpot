import type { RectLikeNode } from '../../renderer/properties/panel-utils'
import { Separator } from '@/components/ui/separator'
import { NodeIdentitySection } from './Sections/NodeIdentitySection'
import { NodeLayoutSection } from './Sections/NodeLayoutSection'
import { FillsSection } from './Sections/FillsSection'
import { StrokesSection } from './Sections/StrokesSection'

export interface NodePropertyPanelProps {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}

export function NodePropertyPanel({ nodeId, initialNode, readOnly }: NodePropertyPanelProps) {
  return (
    <>
      {readOnly ? (
        <p className="text-xs text-muted-foreground">
          Root frame is read-only here. Use the canvas to navigate.
        </p>
      ) : null}

      <NodeIdentitySection nodeId={nodeId} readOnly={readOnly} initialNode={initialNode} />

      <Separator />

      <NodeLayoutSection nodeId={nodeId} initialNode={initialNode} readOnly={readOnly} />

      <FillsSection nodeId={nodeId} readOnly={readOnly} initialNode={initialNode} />

      <StrokesSection nodeId={nodeId} readOnly={readOnly} initialNode={initialNode} />
    </>
  )
}
