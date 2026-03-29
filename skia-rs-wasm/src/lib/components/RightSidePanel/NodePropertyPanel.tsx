import { useEffect, useState } from 'react'
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

  return (
    <>
      {readOnly ? (
        <p className="text-xs text-muted-foreground">
          Root frame is read-only here. Use the canvas to navigate.
        </p>
      ) : null}

      <NodeIdentitySection nodeId={nodeId} readOnly={readOnly} initialNode={initialNode} />

      <Separator />

      <NodeLayoutSection
        nodeId={nodeId}
        initialNode={initialNode}
        readOnly={readOnly}
        x={x}
        y={y}
        width={width}
        height={height}
        rotation={rotation}
        onXChange={setX}
        onYChange={setY}
        onWidthChange={setWidth}
        onHeightChange={setHeight}
        onRotationChange={setRotation}
      />

      <FillsSection nodeId={nodeId} readOnly={readOnly} initialNode={initialNode} />

      <StrokesSection nodeId={nodeId} readOnly={readOnly} initialNode={initialNode} />
    </>
  )
}
