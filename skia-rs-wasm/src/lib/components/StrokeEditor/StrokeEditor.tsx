/**
 * Minimal stroke editor: solid color, width, opacity, alignment, style (first stroke UX matches FillEditor scope).
 */

import type { Stroke } from 'penpot-exporter/types'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { normalizeHex } from '../../renderer/properties/panel-utils'

const ALIGN_OPTIONS = ['center', 'inner', 'outer'] as const
const STYLE_OPTIONS = ['solid', 'dotted', 'dashed', 'mixed'] as const

export interface StrokeEditorProps {
  stroke: Stroke
  readOnly?: boolean
  onChange: (next: Stroke) => void
}

export function StrokeEditor({ stroke, readOnly, onChange }: StrokeEditorProps) {
  const color = stroke.strokeColor ?? '#000000'
  const width = stroke.strokeWidth ?? 1
  const opacity = stroke.strokeOpacity ?? 1
  const alignment = (stroke.strokeAlignment ?? 'center') as (typeof ALIGN_OPTIONS)[number]
  const style = (stroke.strokeStyle ?? 'solid') as (typeof STYLE_OPTIONS)[number]

  return (
    <div className="space-y-3">
      <div className="flex min-w-0 items-center gap-2">
        <Label className="shrink-0">Color</Label>
        <input
          type="color"
          className="h-8 w-10 shrink-0 cursor-pointer rounded border border-border bg-transparent p-0.5"
          value={normalizeHex(typeof color === 'string' ? color : '#000000')}
          disabled={readOnly}
          onChange={(e) => onChange({ ...stroke, strokeColor: e.target.value })}
          aria-label="Stroke color"
        />
        <Input
          className="h-8 min-w-0 flex-1 font-mono text-xs"
          value={typeof color === 'string' ? color : ''}
          disabled={readOnly}
          onChange={(e) => onChange({ ...stroke, strokeColor: e.target.value })}
        />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="se-width">Width</Label>
          <Input
            id="se-width"
            type="number"
            min={0}
            step={0.5}
            disabled={readOnly}
            value={Number.isFinite(width) ? width : 0}
            onChange={(e) => onChange({ ...stroke, strokeWidth: Math.max(0, parseFloat(e.target.value) || 0) })}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="se-opacity">Opacity</Label>
          <Input
            id="se-opacity"
            type="number"
            min={0}
            max={1}
            step={0.05}
            disabled={readOnly}
            value={Number.isFinite(opacity) ? opacity : 1}
            onChange={(e) =>
              onChange({
                ...stroke,
                strokeOpacity: Math.min(1, Math.max(0, parseFloat(e.target.value) || 0)),
              })
            }
          />
        </div>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="se-align">Alignment</Label>
          <select
            id="se-align"
            className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
            disabled={readOnly}
            value={alignment}
            onChange={(e) =>
              onChange({
                ...stroke,
                strokeAlignment: e.target.value as Stroke['strokeAlignment'],
              })
            }
          >
            {ALIGN_OPTIONS.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="se-style">Style</Label>
          <select
            id="se-style"
            className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
            disabled={readOnly}
            value={style}
            onChange={(e) =>
              onChange({
                ...stroke,
                strokeStyle: e.target.value as Stroke['strokeStyle'],
              })
            }
          >
            {STYLE_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>
    </div>
  )
}
