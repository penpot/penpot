import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export interface NodeIdentitySectionProps {
  readOnly: boolean
  name: string
  onNameChange: (name: string) => void
  onNameCommit: () => void
}

export function NodeIdentitySection({
  readOnly,
  name,
  onNameChange,
  onNameCommit,
}: NodeIdentitySectionProps) {
  return (
    <div className="space-y-2">
      <Label htmlFor="rsp-name">Name</Label>
      <Input
        id="rsp-name"
        value={name}
        disabled={readOnly}
        onChange={(e) => onNameChange(e.target.value)}
        onBlur={() => void onNameCommit()}
      />
    </div>
  )
}
