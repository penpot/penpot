import { useCallback, useRef } from 'react'
import type { Blur, Fill, Shadow } from 'penpot-exporter/types'
import { Eye, EyeOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { fillSwatchBackground } from '../../FillEditor/fill-swatch-background'
import type { EffectItem, EffectKind } from '../../../renderer/properties/panel-utils'
import { DEFAULT_SHADOW, DEFAULT_BLUR } from '../../../renderer/properties/panel-utils'
import { useColorEditorFor } from '../use-color-editor'

const EFFECT_KIND_OPTIONS: { value: EffectKind; label: string }[] = [
  { value: 'drop-shadow', label: 'Drop shadow' },
  { value: 'inner-shadow', label: 'Inner shadow' },
  { value: 'layer-blur', label: 'Layer blur' },
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
  const hidden = current.kind === 'layer-blur' ? current.blur.hidden : current.shadow.hidden

  if (newKind === 'layer-blur') {
    return { kind: 'layer-blur', blur: { ...DEFAULT_BLUR, hidden } }
  }
  if (current.kind === 'layer-blur') {
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
  const isShadow = effect.kind !== 'layer-blur'
  const shadow = isShadow ? effect.shadow : null
  const blur = !isShadow ? effect.blur : null

  const { isActive: expanded, openEditor, closeEditor, setActiveEffect, onEffectChangeRef } =
    useColorEditorFor('shadow', index)

  const fill = shadow ? shadowColorToFill(shadow) : null
  const swatchBg = fill ? fillSwatchBackground(fill) : '#94a3b8'

  // Keep refs to latest data so callbacks never go stale
  const effectRef = useRef(effect)
  effectRef.current = effect

  const handleKindChange = useCallback(
    (newKind: EffectKind) => {
      if (newKind === effect.kind) return
      if (expanded && newKind === 'layer-blur') closeEditor()
      onChange(convertEffect(effect, newKind), index)
    },
    [effect, index, onChange, expanded, closeEditor],
  )

  const openEffectEditor = useCallback(
    (e: React.MouseEvent) => {
      if (readOnly) return
      if (expanded) {
        closeEditor()
        return
      }

      const y = (e.currentTarget as HTMLElement).getBoundingClientRect().top
      const currentEffect = effectRef.current
      const currentShadow = currentEffect.kind !== 'layer-blur' ? currentEffect.shadow : null

      // For shadows, pass fill for the color picker; for blur, pass a dummy fill
      const currentFill = currentShadow
        ? shadowColorToFill(currentShadow)
        : { fillColor: '#000000', fillOpacity: 1 }

      openEditor(currentFill, y, `Effect ${index + 1}`, (nextFill) => {
        // Color change callback — only relevant for shadow effects
        const latest = effectRef.current
        if (latest.kind === 'layer-blur') return
        const updatedShadow = fillToShadowColor(nextFill, latest.shadow)
        const updatedEffect: EffectItem = { kind: latest.kind, shadow: updatedShadow }
        effectRef.current = updatedEffect
        setActiveEffect(updatedEffect)
        onChange(updatedEffect, index)
      })

      // Set effect data for the floating effect editor panel
      setActiveEffect(currentEffect)

      // Register callback for effect changes from the floating panel
      onEffectChangeRef.current = (next: EffectItem) => {
        effectRef.current = next
        onChange(next, index)
      }
    },
    [readOnly, expanded, closeEditor, openEditor, index, onChange, setActiveEffect, onEffectChangeRef],
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

  const isHidden = isShadow ? shadow!.hidden : blur!.hidden

  return (
    <div className="space-y-1">
      {/* Compact row: swatch/label + type dropdown + visibility + remove */}
      <div className="flex min-h-8 items-center gap-1.5">
        {/* Clickable trigger — swatch for shadows, text label for blur */}
        {isShadow ? (
          <button
            type="button"
            onClick={openEffectEditor}
            className={cn(
              'size-5 shrink-0 rounded border border-border',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              expanded && 'ring-2 ring-ring',
            )}
            style={{ background: swatchBg }}
            title={expanded ? 'Close effect editor' : 'Open effect editor'}
            aria-expanded={expanded}
            aria-label="Toggle effect editor"
          />
        ) : (
          <button
            type="button"
            onClick={openEffectEditor}
            className={cn(
              'size-5 shrink-0 flex items-center justify-center rounded border border-border text-[10px] text-muted-foreground',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              expanded && 'ring-2 ring-ring',
            )}
            title={expanded ? 'Close effect editor' : 'Open effect editor'}
            aria-expanded={expanded}
            aria-label="Toggle effect editor"
          >
            B
          </button>
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
          className="shrink-0 text-muted-foreground"
          onClick={() => {
            if (isShadow && shadow) {
              onChange({ kind: effect.kind as 'drop-shadow' | 'inner-shadow', shadow: { ...shadow, hidden: !shadow.hidden } }, index)
            } else if (blur) {
              onChange({ kind: 'layer-blur', blur: { ...blur, hidden: !blur.hidden } }, index)
            }
          }}
          aria-label={isHidden ? 'Show effect' : 'Hide effect'}
          title={isHidden ? 'Show effect' : 'Hide effect'}
        >
          {isHidden ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
        </Button>
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
    </div>
  )
}
