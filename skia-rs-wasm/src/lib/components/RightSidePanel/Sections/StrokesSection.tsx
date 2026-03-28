import type { Stroke } from 'penpot-exporter/types'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { StrokeEditor } from '../../StrokeEditor/StrokeEditor'

export interface StrokesSectionProps {
  readOnly: boolean
  strokes: Stroke[]
  onFirstStrokeChange: (stroke: Stroke) => void
}

export function StrokesSection({ readOnly, strokes, onFirstStrokeChange }: StrokesSectionProps) {
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
