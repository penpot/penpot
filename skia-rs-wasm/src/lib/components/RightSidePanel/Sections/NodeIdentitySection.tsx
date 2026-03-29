import { useCallback, useEffect, useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import type { RectLikeNode } from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'

export interface NodeIdentitySectionProps {
  nodeId: string
  readOnly: boolean
  initialNode: RectLikeNode
}

export function NodeIdentitySection({ nodeId, readOnly, initialNode }: NodeIdentitySectionProps) {
  const [name, setName] = useState(() => initialNode.name ?? '')

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- mirrors external document updates */
    setName(initialNode.name ?? '')
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialNode])

  const commitName = useCallback(async () => {
    if (readOnly) return
    const before = getCommittedNodeOnActivePage(nodeId)
    const pid = getActiveOrSinglePageId()
    if (!before || !pid) return
    const trimmed = name.trim()
    if (trimmed === (before.name ?? '')) return
    await commitNodePartialUpdate(nodeId, before, { name: trimmed || 'Shape' }, pid)
  }, [readOnly, name, nodeId])

  return (
    <div className="space-y-2">
      <Label htmlFor="rsp-name">Name</Label>
      <Input
        id="rsp-name"
        value={name}
        disabled={readOnly}
        onChange={(e) => setName(e.target.value)}
        onBlur={() => void commitName()}
      />
    </div>
  )
}
