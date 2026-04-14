import { useCallback } from 'react'
import type { Shadow } from 'penpot-exporter/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { fillSwatchBackground } from '../../FillEditor/fill-swatch-background'
import type { EffectItem, EffectKind } from '@/lib/renderer/properties/panel-utils'
import { DEFAULT_SHADOW, DEFAULT_BLUR, DEFAULT_BACKGROUND_BLUR, DEFAULT_GLASS } from '../../../renderer/properties/panel-utils'
import type { ColorEditorKind } from '../color-editor-context'
import { useEffectEditorFor } from '../use-color-editor'

const EFFECT_KIND_OPTIONS: { value: EffectKind; label: string }[] = [
  { value: 'drop-shadow', label: 'Drop shadow' },
  { value: 'inner-shadow', label: 'Inner shadow' },
  { value: 'layer-blur', label: 'Layer blur' },
  { value: 'background-blur', label: 'Background blur' },
  { value: 'glass', label: 'Glass' },
]

/** Convert Shadow color to a Fill so swatch background can be computed. */
function shadowColorToFill(shadow: Shadow): Fill {
  if (shadow.color?.gradient) {
    return { fillColorGradient: shadow.color.gradient }
  }
  return {
    fillColor: shadow.color?.color ?? '#000000',
    fillOpacity: shadow.color?.opacity ?? 1,
  }
}

/** Convert between effect kinds, preserving hidden state. */
function convertEffect(current: EffectItem, newKind: EffectKind): EffectItem {
  const hidden =
    current.kind === 'layer-blur' || current.kind === 'background-blur'
      ? current.blur.hidden
      : current.kind === 'glass'
        ? current.glass.hidden
        : current.shadow.hidden

  if (newKind === 'layer-blur') {
    return { kind: 'layer-blur', blur: { ...DEFAULT_BLUR, hidden } }
  }
  if (newKind === 'background-blur') {
    return { kind: 'background-blur', blur: { ...DEFAULT_BACKGROUND_BLUR, hidden } }
  }
  if (newKind === 'glass') {
    return { kind: 'glass', glass: { ...DEFAULT_GLASS, hidden } }
  }
  if (current.kind === 'layer-blur' || current.kind === 'background-blur' || current.kind === 'glass') {
    return { kind: newKind, shadow: { ...DEFAULT_SHADOW, style: newKind, hidden } }
  }
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
  const isBlur = effect.kind === 'layer-blur' || effect.kind === 'background-blur'
  const isGlass = effect.kind === 'glass'

  const { isActive: effectExpanded, openEffectEditor, closeEditor } = useEffectEditorFor(effect.kind as ColorEditorKind, index)

  // Shadow color swatch background
  const swatchBg = isShadow
    ? fillSwatchBackground(shadowColorToFill(effect.shadow))
    : undefined

  const handleKindChange = useCallback(
    (newKind: EffectKind) => {
      if (newKind === effect.kind) return
      if (effectExpanded) closeEditor()
      onChange(convertEffect(effect, newKind), index)
    },
    [effect, index, onChange, effectExpanded, closeEditor],
  )

  const toggleEffectExpand = useCallback(
    (e: React.MouseEvent) => {
      if (readOnly) return
      if (effectExpanded) {
        closeEditor()
      } else {
        const y = (e.currentTarget as HTMLElement).getBoundingClientRect().top
        const label = EFFECT_KIND_OPTIONS.find((o) => o.value === effect.kind)?.label ?? 'Effect'
        openEffectEditor(effect, y, label, (nextEffect) => {
          onChange(nextEffect, index)
        })
      }
    },
    [readOnly, effectExpanded, closeEditor, openEffectEditor, effect, index, onChange],
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
      {/* Row: indicator button + type dropdown + remove */}
      <div className="flex min-h-8 items-center gap-1.5">
        {/* Shadow indicator: color swatch */}
        {isShadow && (
          <button
            type="button"
            onClick={toggleEffectExpand}
            className={cn(
              'size-5 shrink-0 rounded border border-border',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              effectExpanded && 'ring-2 ring-ring',
            )}
            style={{ background: swatchBg }}
            title={effectExpanded ? 'Close shadow editor' : 'Open shadow editor'}
            aria-expanded={effectExpanded}
            aria-label="Toggle shadow editor"
          />
        )}

        {/* Blur indicator: dashed circle icon */}
        {isBlur && (
          <button
            type="button"
            onClick={toggleEffectExpand}
            className={cn(
              'flex size-5 shrink-0 items-center justify-center rounded border',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              effectExpanded
                ? 'border-ring bg-accent ring-2 ring-ring'
                : 'border-border bg-muted',
            )}
            title={effectExpanded ? 'Close blur editor' : 'Open blur editor'}
            aria-expanded={effectExpanded}
            aria-label="Toggle blur editor"
          >
            <svg viewBox="0 0 12 12" className="size-3 text-muted-foreground" fill="none" stroke="currentColor" strokeWidth="1.2">
              <circle cx="6" cy="6" r="4" strokeDasharray="1.5 1.5" />
            </svg>
          </button>
        )}

        {/* Glass indicator: glass icon */}
        {isGlass && (
          <button
            type="button"
            onClick={toggleEffectExpand}
            className={cn(
              'flex size-5 shrink-0 items-center justify-center rounded border',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              effectExpanded
                ? 'border-ring bg-accent ring-2 ring-ring'
                : 'border-border bg-gradient-to-br from-blue-100 to-purple-100',
            )}
            title={effectExpanded ? 'Close glass editor' : 'Open glass editor'}
            aria-expanded={effectExpanded}
            aria-label="Toggle glass editor"
          >
            <svg viewBox="0 0 12 12" className="size-3 text-muted-foreground" fill="none" stroke="currentColor" strokeWidth="1.2">
              <path d="M2 9 Q2 4 6 4 Q10 4 10 9" />
              <line x1="2" y1="9" x2="10" y2="9" />
            </svg>
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
