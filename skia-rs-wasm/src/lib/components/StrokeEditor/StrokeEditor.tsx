/**
 * Standalone stroke editor (single stroke). The sidebar uses StrokeRow instead;
 * this component is kept for contexts where an isolated stroke form is needed.
 */

import type { Stroke } from 'penpot-exporter/types'
import { StrokeRow } from '../RightSidePanel/Sections/StrokeRow'

export interface StrokeEditorProps {
  stroke: Stroke
  index?: number
  readOnly?: boolean
  onChange: (next: Stroke) => void
}

export function StrokeEditor({ stroke, index = 0, readOnly = false, onChange }: StrokeEditorProps) {
  return (
    <StrokeRow
      stroke={stroke}
      index={index}
      readOnly={readOnly}
      onChange={(next) => onChange(next)}
      onRemove={() => {
        /* no-op: standalone editor has no remove action */
      }}
    />
  )
}
