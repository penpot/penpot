import type { Fill } from 'penpot-exporter/types'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { FillEditor } from '../../fill-editor/FillEditor'

export interface FillsSectionProps {
  readOnly: boolean
  fills: Fill[]
  onFillChange: (fill: Fill) => void
  onAddFill: () => void
  onClearFills: () => void
}

export function FillsSection({
  readOnly,
  fills,
  onFillChange,
  onAddFill,
  onClearFills,
}: FillsSectionProps) {
  return (
    <>
      <Separator />
      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <Label>Fill</Label>
          {!readOnly && (
            <div className="flex gap-1">
              {fills.length === 0 ? (
                <Button type="button" variant="outline" size="sm" onClick={onAddFill}>
                  Add fill
                </Button>
              ) : (
                <Button type="button" variant="ghost" size="sm" onClick={onClearFills}>
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
