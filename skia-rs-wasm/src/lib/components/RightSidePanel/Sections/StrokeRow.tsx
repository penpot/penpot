import { useCallback } from 'react'
import type { Fill, Stroke } from 'penpot-exporter/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { fillSwatchBackground } from '../../FillEditor/fill-swatch-background'
import { isColorFill } from '../../../renderer/api/constants'
import { normalizeHex } from '../../../renderer/properties/panel-utils'
import { useStrokeEditor } from '../StrokeEditorContext'

const ALIGN_OPTIONS = ['center', 'inner', 'outer'] as const
const STYLE_OPTIONS = ['solid', 'dotted', 'dashed', 'mixed'] as const

/** Convert stroke color fields to a Fill so FillEditor can be reused. */
function strokeToFill(stroke: Stroke): Fill {
  if (stroke.strokeColorGradient) {
    return { fillColorGradient: stroke.strokeColorGradient }
  }
  return { fillColor: stroke.strokeColor ?? '#000000', fillOpacity: stroke.strokeOpacity ?? 1 }
}

/** Merge Fill color fields back into a Stroke. */
function fillToStrokeColor(fill: Fill, existing: Stroke): Stroke {
  if (fill.fillColorGradient) {
    const { strokeColor: _c, strokeOpacity: _o, strokeColorGradient: _g, ...rest } = existing
    return { ...rest, strokeColorGradient: fill.fillColorGradient }
  }
  const { strokeColorGradient: _g, ...rest } = existing
  return {
    ...rest,
    strokeColor: fill.fillColor ?? '#000000',
    strokeOpacity: fill.fillOpacity ?? 1,
  }
}

export interface StrokeRowProps {
  stroke: Stroke
  index: number
  readOnly: boolean
  onChange: (stroke: Stroke, index: number) => void
  onRemove: (index: number) => void
}

export function StrokeRow({ stroke, index, readOnly, onChange, onRemove }: StrokeRowProps) {
  const { activeStrokeIndex, openEditor, closeEditor } = useStrokeEditor()
  const expanded = activeStrokeIndex === index

  const fill = strokeToFill(stroke)
  const isSolid = isColorFill(fill)
  const swatchBg = fillSwatchBackground(fill)

  const hexDisplay = isSolid ? (stroke.strokeColor ?? '#000000') : 'Gradient'
  const opacity = stroke.strokeOpacity ?? 1
  const width = stroke.strokeWidth ?? 1
  const alignment = (stroke.strokeAlignment ?? 'center') as (typeof ALIGN_OPTIONS)[number]
  const style = (stroke.strokeStyle ?? 'solid') as (typeof STYLE_OPTIONS)[number]

  const handleHexChange = useCallback(
    (raw: string) => {
      if (!isSolid) return
      const v = raw.trim()
      if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v)) {
        onChange({ ...stroke, strokeColor: normalizeHex(v) }, index)
      }
    },
    [stroke, index, onChange, isSolid],
  )

  const handleOpacityChange = useCallback(
    (pct: number) => {
      if (!isSolid) return
      const v = Math.max(0, Math.min(100, pct)) / 100
      onChange({ ...stroke, strokeOpacity: v }, index)
    },
    [stroke, index, onChange, isSolid],
  )

  const toggleExpand = useCallback(
    (e: React.MouseEvent) => {
      if (readOnly) return
      if (expanded) {
        closeEditor()
      } else {
        const y = (e.currentTarget as HTMLElement).getBoundingClientRect().top
        openEditor(index, fill, y, (nextFill) => {
          onChange(fillToStrokeColor(nextFill, stroke), index)
        })
      }
    },
    [readOnly, expanded, closeEditor, openEditor, index, fill, stroke, onChange],
  )

  if (readOnly) {
    return (
      <div className="space-y-1">
        <div className="flex min-h-8 items-center gap-1.5">
          <div
            className="size-5 shrink-0 rounded border border-border"
            style={{ background: swatchBg }}
            aria-hidden
          />
          <span className="text-xs text-muted-foreground">Stroke {index + 1}</span>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-1">
      {/* Row 1: color swatch → opens floating editor, hex/label, opacity, remove */}
      <div className="flex min-h-8 items-center gap-1.5">
        <button
          type="button"
          onClick={toggleExpand}
          className={cn(
            'size-5 shrink-0 rounded border border-border',
            'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
            expanded && 'ring-2 ring-ring',
          )}
          style={{ background: swatchBg }}
          title={expanded ? 'Close stroke color editor' : 'Open stroke color editor'}
          aria-expanded={expanded}
          aria-label="Toggle stroke color editor"
        />
        <Input
          type="text"
          className="h-8 min-w-0 flex-1 font-mono text-xs"
          value={hexDisplay}
          placeholder={isSolid ? '#RRGGBB' : undefined}
          disabled={!isSolid}
          readOnly={!isSolid}
          onChange={(e) => handleHexChange(e.target.value)}
        />
        <span className="shrink-0 text-xs text-muted-foreground">%</span>
        <Input
          type="number"
          className="h-8 w-12 shrink-0 px-1 text-xs"
          min={0}
          max={100}
          value={isSolid ? Math.round(opacity * 100) : 100}
          disabled={!isSolid}
          onChange={(e) => handleOpacityChange(Number(e.target.value))}
        />
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          className="shrink-0"
          onClick={() => onRemove(index)}
          aria-label="Remove stroke"
        >
          ×
        </Button>
      </div>
      {/* Row 2: width + alignment + style */}
      <div className="flex min-h-7 items-center gap-1.5">
        <Input
          type="number"
          className="h-7 w-14 shrink-0 px-1 text-xs"
          min={0}
          step={0.5}
          value={Number.isFinite(width) ? width : 0}
          onChange={(e) =>
            onChange({ ...stroke, strokeWidth: Math.max(0, parseFloat(e.target.value) || 0) }, index)
          }
          title="Width"
        />
        <select
          className="border-input bg-background h-7 min-w-0 flex-1 rounded-md border px-1.5 text-xs"
          value={alignment}
          onChange={(e) =>
            onChange({ ...stroke, strokeAlignment: e.target.value as Stroke['strokeAlignment'] }, index)
          }
          title="Alignment"
        >
          {ALIGN_OPTIONS.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </select>
        <select
          className="border-input bg-background h-7 min-w-0 flex-1 rounded-md border px-1.5 text-xs"
          value={style}
          onChange={(e) =>
            onChange({ ...stroke, strokeStyle: e.target.value as Stroke['strokeStyle'] }, index)
          }
          title="Style"
        >
          {STYLE_OPTIONS.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
    </div>
  )
}
