import { useCallback } from 'react'
import type { Blur, Fill, Glass, Shadow } from 'penpot-exporter/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { fillSwatchBackground } from '../../FillEditor/fill-swatch-background'
import { isColorFill } from '../../../renderer/api/constants'
import { normalizeHex } from '../../../renderer/properties/panel-utils'
import type { EffectItem, EffectKind } from '../../../renderer/properties/panel-utils'
import { DEFAULT_SHADOW, DEFAULT_BLUR, DEFAULT_GLASS } from '../../../renderer/properties/panel-utils'
import { useColorEditorFor } from '../use-color-editor'

const EFFECT_KIND_OPTIONS: { value: EffectKind; label: string }[] = [
  { value: 'drop-shadow', label: 'Drop shadow' },
  { value: 'inner-shadow', label: 'Inner shadow' },
  { value: 'layer-blur', label: 'Layer blur' },
  { value: 'glass', label: 'Glass' },
]

/** Convert Shadow color to a Fill so FillEditor can be reused. */
function shadowColorToFill(shadow: Shadow): Fill {
  if (shadow.color?.gradient) {
    return { fillColorGradient: shadow.color.gradient }
  }
  return {
    fillColor: shadow.color?.color ?? '#000000',
    fillOpacity: shadow.color?.opacity ?? 1,
  }
}

/** Merge Fill color fields back into a Shadow. */
function fillToShadowColor(fill: Fill, existing: Shadow): Shadow {
  if (fill.fillColorGradient) {
    return {
      ...existing,
      color: { gradient: fill.fillColorGradient },
    }
  }
  return {
    ...existing,
    color: {
      color: fill.fillColor ?? '#000000',
      opacity: fill.fillOpacity ?? 1,
    },
  }
}

/** Convert between effect kinds, preserving hidden state. */
function convertEffect(current: EffectItem, newKind: EffectKind): EffectItem {
  const hidden =
    current.kind === 'layer-blur'
      ? current.blur.hidden
      : current.kind === 'glass'
        ? current.glass.hidden
        : current.shadow.hidden

  if (newKind === 'layer-blur') {
    return { kind: 'layer-blur', blur: { ...DEFAULT_BLUR, hidden } }
  }
  if (newKind === 'glass') {
    return { kind: 'glass', glass: { ...DEFAULT_GLASS, hidden } }
  }
  if (current.kind === 'layer-blur' || current.kind === 'glass') {
    return { kind: newKind, shadow: { ...DEFAULT_SHADOW, style: newKind, hidden } }
  }
  // Shadow → Shadow (different style)
  return { kind: newKind, shadow: { ...current.shadow, style: newKind } }
}

export interface EffectRowProps {
  effect: EffectItem
  index: number
  readOnly: boolean
  onChange: (effect: EffectItem, index: number) => void
  onRemove: (index: number) => void
}

export function EffectRow({ effect, index, readOnly, onChange, onRemove }: EffectRowProps) {
  const isShadow = effect.kind === 'drop-shadow' || effect.kind === 'inner-shadow'
  const isBlur = effect.kind === 'layer-blur'
  const isGlass = effect.kind === 'glass'
  const shadow = isShadow ? effect.shadow : null
  const blur = isBlur ? effect.blur : null
  const glass = isGlass ? effect.glass : null

  const { isActive: expanded, openEditor, closeEditor } = useColorEditorFor('shadow', index)

  const fill = shadow ? shadowColorToFill(shadow) : null
  const isSolid = fill ? isColorFill(fill) : false
  const swatchBg = fill ? fillSwatchBackground(fill) : '#94a3b8'

  const hexDisplay = shadow
    ? isSolid
      ? (shadow.color?.color ?? '#000000')
      : 'Gradient'
    : ''
  const opacity = shadow?.color?.opacity ?? 1

  const handleKindChange = useCallback(
    (newKind: EffectKind) => {
      if (newKind === effect.kind) return
      // Close shadow color editor if switching away from shadow
      if (expanded && newKind !== 'drop-shadow' && newKind !== 'inner-shadow') closeEditor()
      onChange(convertEffect(effect, newKind), index)
    },
    [effect, index, onChange, expanded, closeEditor],
  )

  const handleShadowUpdate = useCallback(
    (partial: Partial<Shadow>) => {
      if (!shadow) return
      onChange({ kind: effect.kind as 'drop-shadow' | 'inner-shadow', shadow: { ...shadow, ...partial } }, index)
    },
    [shadow, effect.kind, index, onChange],
  )

  const handleGlassUpdate = useCallback(
    (partial: Partial<Glass>) => {
      if (!glass) return
      onChange({ kind: 'glass', glass: { ...glass, ...partial } }, index)
    },
    [glass, index, onChange],
  )

  const handleHexChange = useCallback(
    (raw: string) => {
      if (!shadow || !isSolid) return
      const v = raw.trim()
      if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v)) {
        handleShadowUpdate({ color: { ...shadow.color, color: normalizeHex(v) } })
      }
    },
    [shadow, isSolid, handleShadowUpdate],
  )

  const handleOpacityChange = useCallback(
    (pct: number) => {
      if (!shadow || !isSolid) return
      const v = Math.max(0, Math.min(100, pct)) / 100
      handleShadowUpdate({ color: { ...shadow.color, opacity: v } })
    },
    [shadow, isSolid, handleShadowUpdate],
  )

  const toggleExpand = useCallback(
    (e: React.MouseEvent) => {
      if (readOnly || !shadow) return
      if (expanded) {
        closeEditor()
      } else {
        const y = (e.currentTarget as HTMLElement).getBoundingClientRect().top
        openEditor(fill!, y, `Shadow ${index + 1} color`, (nextFill) => {
          onChange(
            { kind: effect.kind as 'drop-shadow' | 'inner-shadow', shadow: fillToShadowColor(nextFill, shadow) },
            index,
          )
        })
      }
    },
    [readOnly, shadow, expanded, closeEditor, openEditor, fill, index, effect.kind, onChange],
  )

  if (readOnly) {
    return (
      <div className="space-y-1">
        <div className="flex min-h-8 items-center gap-1.5">
          {isShadow && (
            <div
              className="size-5 shrink-0 rounded border border-border"
              style={{ background: swatchBg }}
              aria-hidden
            />
          )}
          <span className="text-xs text-muted-foreground">
            {EFFECT_KIND_OPTIONS.find((o) => o.value === effect.kind)?.label ?? 'Effect'}
          </span>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-1">
      {/* Row 1: swatch (shadow only) + type dropdown + remove */}
      <div className="flex min-h-8 items-center gap-1.5">
        {isShadow && (
          <button
            type="button"
            onClick={toggleExpand}
            className={cn(
              'size-5 shrink-0 rounded border border-border',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              expanded && 'ring-2 ring-ring',
            )}
            style={{ background: swatchBg }}
            title={expanded ? 'Close shadow color editor' : 'Open shadow color editor'}
            aria-expanded={expanded}
            aria-label="Toggle shadow color editor"
          />
        )}
        <select
          className="border-input bg-background h-8 min-w-0 flex-1 rounded-md border px-1.5 text-xs"
          value={effect.kind}
          onChange={(e) => handleKindChange(e.target.value as EffectKind)}
          title="Effect type"
        >
          {EFFECT_KIND_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          className="shrink-0"
          onClick={() => onRemove(index)}
          aria-label="Remove effect"
        >
          ×
        </Button>
      </div>

      {/* Row 2 for shadows: hex + opacity */}
      {isShadow && shadow && (
        <div className="flex min-h-8 items-center gap-1.5">
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
        </div>
      )}

      {/* Row 3 for shadows: X, Y, blur, spread */}
      {isShadow && shadow && (
        <div className="flex min-h-7 items-center gap-1.5">
          <Input
            type="number"
            className="h-7 w-14 shrink-0 px-1 text-xs"
            step={1}
            value={shadow.offsetX}
            onChange={(e) => handleShadowUpdate({ offsetX: parseFloat(e.target.value) || 0 })}
            title="X offset"
          />
          <Input
            type="number"
            className="h-7 w-14 shrink-0 px-1 text-xs"
            step={1}
            value={shadow.offsetY}
            onChange={(e) => handleShadowUpdate({ offsetY: parseFloat(e.target.value) || 0 })}
            title="Y offset"
          />
          <Input
            type="number"
            className="h-7 w-14 shrink-0 px-1 text-xs"
            min={0}
            step={1}
            value={shadow.blur}
            onChange={(e) => handleShadowUpdate({ blur: Math.max(0, parseFloat(e.target.value) || 0) })}
            title="Blur"
          />
          <Input
            type="number"
            className="h-7 w-14 shrink-0 px-1 text-xs"
            step={1}
            value={shadow.spread}
            onChange={(e) => handleShadowUpdate({ spread: parseFloat(e.target.value) || 0 })}
            title="Spread"
          />
        </div>
      )}

      {/* Row 2 for blur: value */}
      {isBlur && blur && (
        <div className="flex min-h-7 items-center gap-1.5">
          <span className="shrink-0 text-xs text-muted-foreground">Value</span>
          <Input
            type="number"
            className="h-7 w-20 shrink-0 px-1 text-xs"
            min={0}
            step={1}
            value={blur.value}
            onChange={(e) =>
              onChange(
                { kind: 'layer-blur', blur: { ...blur, value: Math.max(0, parseFloat(e.target.value) || 0) } },
                index,
              )
            }
            title="Blur value"
          />
        </div>
      )}

      {/* Rows for glass: radius, refraction, depth, dispersion, lightIntensity, lightAngle */}
      {isGlass && glass && (
        <>
          <div className="flex min-h-7 items-center gap-1.5">
            <span className="w-20 shrink-0 text-xs text-muted-foreground">Radius</span>
            <Input
              type="number"
              className="h-7 w-20 shrink-0 px-1 text-xs"
              min={0}
              step={1}
              value={glass.radius ?? 10}
              onChange={(e) => handleGlassUpdate({ radius: Math.max(0, parseFloat(e.target.value) || 0) })}
              title="Corner radius"
            />
          </div>
          <div className="flex min-h-7 items-center gap-1.5">
            <span className="w-20 shrink-0 text-xs text-muted-foreground">Refraction</span>
            <Input
              type="number"
              className="h-7 w-20 shrink-0 px-1 text-xs"
              min={1}
              max={3}
              step={0.1}
              value={glass.refraction ?? 1.5}
              onChange={(e) => handleGlassUpdate({ refraction: Math.min(3, Math.max(1, parseFloat(e.target.value) || 1)) })}
              title="Index of refraction (1–3)"
            />
          </div>
          <div className="flex min-h-7 items-center gap-1.5">
            <span className="w-20 shrink-0 text-xs text-muted-foreground">Depth</span>
            <Input
              type="number"
              className="h-7 w-20 shrink-0 px-1 text-xs"
              min={0}
              max={100}
              step={1}
              value={glass.depth ?? 10}
              onChange={(e) => handleGlassUpdate({ depth: Math.min(100, Math.max(0, parseFloat(e.target.value) || 0)) })}
              title="Lens depth (0–100)"
            />
          </div>
          <div className="flex min-h-7 items-center gap-1.5">
            <span className="w-20 shrink-0 text-xs text-muted-foreground">Dispersion</span>
            <Input
              type="number"
              className="h-7 w-20 shrink-0 px-1 text-xs"
              min={0}
              max={1}
              step={0.01}
              value={glass.dispersion ?? 0.03}
              onChange={(e) => handleGlassUpdate({ dispersion: Math.min(1, Math.max(0, parseFloat(e.target.value) || 0)) })}
              title="Chromatic aberration (0–1)"
            />
          </div>
          <div className="flex min-h-7 items-center gap-1.5">
            <span className="w-20 shrink-0 text-xs text-muted-foreground">Light</span>
            <Input
              type="number"
              className="h-7 w-20 shrink-0 px-1 text-xs"
              min={0}
              max={1}
              step={0.05}
              value={glass.lightIntensity ?? 0.5}
              onChange={(e) => handleGlassUpdate({ lightIntensity: Math.min(1, Math.max(0, parseFloat(e.target.value) || 0)) })}
              title="Light intensity (0–1)"
            />
          </div>
          <div className="flex min-h-7 items-center gap-1.5">
            <span className="w-20 shrink-0 text-xs text-muted-foreground">Angle</span>
            <Input
              type="number"
              className="h-7 w-20 shrink-0 px-1 text-xs"
              min={0}
              max={360}
              step={1}
              value={glass.lightAngle ?? 45}
              onChange={(e) => handleGlassUpdate({ lightAngle: parseFloat(e.target.value) || 0 })}
              title="Light angle (degrees)"
            />
          </div>
        </>
      )}
    </div>
  )
}
