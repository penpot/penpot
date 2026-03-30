import { useCallback, useState } from 'react'
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
  const [draftName, setDraftName] = useState<string | null>(null)

  const name = draftName ?? initialNode.name ?? ''

  const commitName = useCallback(async () => {
    if (readOnly || draftName === null) return
    const before = getCommittedNodeOnActivePage(nodeId)
    const pid = getActiveOrSinglePageId()
    if (!before || !pid) return
    const trimmed = draftName.trim()
    if (trimmed !== (before.name ?? '')) {
      await commitNodePartialUpdate(nodeId, before, { name: trimmed || 'Shape' }, pid)
    }
    setDraftName(null)
  }, [readOnly, draftName, nodeId])

  return (
    <div className="space-y-2">
      <Label htmlFor="rsp-name">Name</Label>
      <Input
        id="rsp-name"
        value={name}
        disabled={readOnly}
        onChange={(e) => setDraftName(e.target.value)}
        onBlur={() => void commitName()}
      />
    </div>
  )
}
