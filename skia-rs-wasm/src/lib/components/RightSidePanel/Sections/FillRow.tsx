import { useCallback, useState } from 'react'
import type { Fill } from 'penpot-exporter/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { fillSwatchBackground } from '../../FillEditor/fill-swatch-background'
import { FillEditor } from '../../FillEditor/FillEditor'
import { isColorFill, isImageFill } from '../../../renderer/api/constants'
import { normalizeHex } from '../../../renderer/properties/panel-utils'

export interface FillRowProps {
  fill: Fill
  index: number
  readOnly: boolean
  onChange: (fill: Fill, index: number) => void
  onRemove: (index: number) => void
}

export function FillRow({ fill, index, readOnly, onChange, onRemove }: FillRowProps) {
  const [expanded, setExpanded] = useState(false)

  const isSolid = isColorFill(fill)
  const isImage = isImageFill(fill)
  const swatchBg = fillSwatchBackground(fill)

  const hexDisplay = isSolid
    ? (fill.fillColor ?? '#000000')
    : isImage
      ? 'Image'
      : 'Gradient'

  const handleHexChange = useCallback(
    (raw: string) => {
      if (!isSolid) return
      const v = raw.trim()
      if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v)) {
        onChange({ ...fill, fillColor: normalizeHex(v) }, index)
      }
    },
    [fill, index, onChange, isSolid],
  )

  const handleOpacityChange = useCallback(
    (pct: number) => {
      if (!isSolid) return
      const v = Math.max(0, Math.min(100, pct)) / 100
      onChange({ ...fill, fillOpacity: v }, index)
    },
    [fill, index, onChange, isSolid],
  )

  const toggleExpand = useCallback(() => {
    if (readOnly) return
    setExpanded((e) => !e)
  }, [readOnly])

  if (readOnly) {
    return (
      <div className="flex min-h-8 items-center gap-2 py-0.5">
        <div
          className="size-5 shrink-0 rounded border border-border"
          style={{ background: swatchBg }}
          aria-hidden
        />
        <span className="text-xs text-muted-foreground">Fill {index + 1}</span>
      </div>
    )
  }

  return (
    <div className="space-y-1">
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
          title={expanded ? 'Collapse fill editor' : 'Expand fill editor'}
          aria-expanded={expanded}
          aria-label="Toggle fill editor"
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
          value={isSolid ? Math.round((fill.fillOpacity ?? 1) * 100) : 100}
          disabled={!isSolid}
          onChange={(e) => handleOpacityChange(Number(e.target.value))}
        />
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          className="shrink-0"
          onClick={() => onRemove(index)}
          aria-label="Remove fill"
        >
          ×
        </Button>
      </div>
      {expanded && (
        <FillEditor
          fill={fill}
          embeddedInRow
          onChange={(next) => onChange(next, index)}
        />
      )}
    </div>
  )
}
