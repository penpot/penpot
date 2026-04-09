import { useCallback, useState } from 'react'
import type { Fill, Shadow } from 'penpot-exporter/types'
import { Eye, EyeOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { FillEditor } from '../FillEditor/FillEditor'
import { fillSwatchBackground } from '../FillEditor/fill-swatch-background'
import { isColorFill } from '../../renderer/api/constants'
import { normalizeHex, type EffectItem } from '../../renderer/properties/panel-utils'
import { useColorEditor } from './use-color-editor'
import { FloatingPanelShell } from './FloatingPanelShell'

type ShadowStyle = 'drop-shadow' | 'inner-shadow'

const EFFECT_KIND_OPTIONS: { value: string; label: string }[] = [
  { value: 'drop-shadow', label: 'Drop shadow' },
  { value: 'inner-shadow', label: 'Inner shadow' },
  { value: 'layer-blur', label: 'Layer blur' },
]

/** Build a Fill-compatible swatch background from shadow color. */
function shadowSwatchBg(shadow: Shadow): string {
  if (shadow.color?.gradient) {
    return fillSwatchBackground({ fillColorGradient: shadow.color.gradient })
  }
  return shadow.color?.color ?? '#000000'
}

export function FloatingEffectEditorPanel() {
  const {
    activeTarget,
    activeFill,
    activeEffect,
    anchorY,
    closeEditor,
    onChangeRef,
    onEffectChangeRef,
    setActiveEffect,
  } = useColorEditor()

  const [showColorPicker, setShowColorPicker] = useState(false)

  const targetKey =
    activeTarget?.kind === 'shadow'
      ? `effect-${activeTarget.index}`
      : null

  const handleEffectChange = useCallback(
    (next: EffectItem) => {
      setActiveEffect(next)
      onEffectChangeRef.current?.(next)
    },
    [setActiveEffect, onEffectChangeRef],
  )

  const handleColorPickerChange = useCallback(
    (next: Fill) => {
      onChangeRef.current?.(next)
    },
    [onChangeRef],
  )

  if (!targetKey || !activeEffect) return null

  const isShadow = activeEffect.kind !== 'layer-blur'
  const shadow = isShadow ? activeEffect.shadow : null
  const blur = !isShadow ? activeEffect.blur : null

  const handleShadowUpdate = (partial: Partial<Shadow>) => {
    if (!shadow) return
    handleEffectChange({
      kind: activeEffect.kind as ShadowStyle,
      shadow: { ...shadow, ...partial },
    })
  }

  const handleKindChange = (newKind: string) => {
    if (newKind === activeEffect.kind) return
    if (newKind === 'layer-blur') {
      const hidden = shadow?.hidden ?? false
      handleEffectChange({ kind: 'layer-blur', blur: { type: 'layer-blur', value: 4, hidden } })
      setShowColorPicker(false)
    } else if (activeEffect.kind === 'layer-blur') {
      const hidden = blur!.hidden
      handleEffectChange({
        kind: newKind as ShadowStyle,
        shadow: {
          id: null, style: newKind as ShadowStyle,
          offsetX: 4, offsetY: 4, blur: 4, spread: 0, hidden,
          color: { color: '#000000', opacity: 0.2 },
        },
      })
    } else {
      handleEffectChange({
        kind: newKind as ShadowStyle,
        shadow: { ...shadow!, style: newKind as ShadowStyle },
      })
    }
  }

  // Color display
  const isSolid = shadow
    ? isColorFill({ fillColor: shadow.color?.color, fillOpacity: shadow.color?.opacity })
    : false
  const hexDisplay = shadow ? (isSolid ? (shadow.color?.color ?? '#000000') : 'Gradient') : ''
  const opacity = shadow?.color?.opacity ?? 1
  const swatchBg = shadow ? shadowSwatchBg(shadow) : '#94a3b8'

  // Visibility (both shadow and blur have hidden)
  const isHidden = isShadow ? shadow!.hidden : blur!.hidden
  const toggleHidden = () => {
    if (isShadow && shadow) {
      handleShadowUpdate({ hidden: !shadow.hidden })
    } else if (blur) {
      handleEffectChange({ kind: 'layer-blur', blur: { ...blur, hidden: !blur.hidden } })
    }
  }

  const headerContent = (
    <div className="flex min-w-0 flex-1 items-center gap-1.5">
      <select
        className="border-input bg-background h-7 min-w-0 flex-1 rounded-md border px-1.5 text-xs font-medium"
        value={activeEffect.kind}
        onChange={(e) => handleKindChange(e.target.value)}
        onPointerDown={(e) => e.stopPropagation()}
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
        onClick={toggleHidden}
        onPointerDown={(e) => e.stopPropagation()}
        aria-label={isHidden ? 'Show effect' : 'Hide effect'}
        title={isHidden ? 'Show effect' : 'Hide effect'}
      >
        {isHidden ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
      </Button>
    </div>
  )

  return (
    <FloatingPanelShell
      targetKey={targetKey}
      anchorY={anchorY}
      title={headerContent}
      onClose={closeEditor}
    >
      {/* ---- Shadow controls ---- */}
      {isShadow && shadow && (
        <div className="space-y-3">
          {/* Position */}
          <div className="space-y-1.5">
            <span className="text-[11px] font-medium text-muted-foreground">Position</span>
            <div className="flex items-center gap-2">
              <span className="w-4 shrink-0 text-center text-[11px] text-muted-foreground">X</span>
              <Input
                type="number"
                className="h-7 min-w-0 flex-1 px-1.5 text-xs"
                step={1}
                value={shadow.offsetX}
                onChange={(e) => handleShadowUpdate({ offsetX: parseFloat(e.target.value) || 0 })}
              />
            </div>
            <div className="flex items-center gap-2">
              <span className="w-4 shrink-0 text-center text-[11px] text-muted-foreground">Y</span>
              <Input
                type="number"
                className="h-7 min-w-0 flex-1 px-1.5 text-xs"
                step={1}
                value={shadow.offsetY}
                onChange={(e) => handleShadowUpdate({ offsetY: parseFloat(e.target.value) || 0 })}
              />
            </div>
          </div>

          {/* Blur */}
          <div className="flex items-center gap-2">
            <span className="w-14 shrink-0 text-[11px] font-medium text-muted-foreground">Blur</span>
            <Input
              type="number"
              className="h-7 min-w-0 flex-1 px-1.5 text-xs"
              min={0}
              step={1}
              value={shadow.blur}
              onChange={(e) => handleShadowUpdate({ blur: Math.max(0, parseFloat(e.target.value) || 0) })}
            />
          </div>

          {/* Spread */}
          <div className="flex items-center gap-2">
            <span className="w-14 shrink-0 text-[11px] font-medium text-muted-foreground">Spread</span>
            <Input
              type="number"
              className="h-7 min-w-0 flex-1 px-1.5 text-xs"
              step={1}
              value={shadow.spread}
              onChange={(e) => handleShadowUpdate({ spread: parseFloat(e.target.value) || 0 })}
            />
          </div>

          {/* Color */}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <span className="w-14 shrink-0 text-[11px] font-medium text-muted-foreground">Color</span>
              <button
                type="button"
                onClick={() => setShowColorPicker((v) => !v)}
                className={cn(
                  'size-5 shrink-0 rounded border border-border',
                  'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                  showColorPicker && 'ring-2 ring-ring',
                )}
                style={{ background: swatchBg }}
                aria-expanded={showColorPicker}
                aria-label="Toggle color picker"
                title={showColorPicker ? 'Close color picker' : 'Open color picker'}
              />
              <Input
                type="text"
                className="h-7 min-w-0 flex-1 font-mono text-xs px-1.5"
                value={hexDisplay}
                placeholder={isSolid ? '#RRGGBB' : undefined}
                disabled={!isSolid}
                readOnly={!isSolid}
                onChange={(e) => {
                  if (!isSolid) return
                  const v = e.target.value.trim()
                  if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v)) {
                    handleShadowUpdate({ color: { ...shadow.color, color: normalizeHex(v) } })
                  }
                }}
              />
              <Input
                type="number"
                className="h-7 w-10 shrink-0 px-1 text-xs"
                min={0}
                max={100}
                value={isSolid ? Math.round(opacity * 100) : 100}
                disabled={!isSolid}
                onChange={(e) => {
                  if (!isSolid) return
                  const v = Math.max(0, Math.min(100, Number(e.target.value))) / 100
                  handleShadowUpdate({ color: { ...shadow.color, opacity: v } })
                }}
              />
              <span className="shrink-0 text-[11px] text-muted-foreground">%</span>
            </div>

            {showColorPicker && activeFill && (
              <div className="rounded-md border border-border p-2">
                <FillEditor fill={activeFill} onChange={handleColorPickerChange} />
              </div>
            )}
          </div>
        </div>
      )}

      {/* ---- Layer blur controls ---- */}
      {!isShadow && blur && (
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <span className="w-14 shrink-0 text-[11px] font-medium text-muted-foreground">Value</span>
            <Input
              type="number"
              className="h-7 min-w-0 flex-1 px-1.5 text-xs"
              min={0}
              step={1}
              value={blur.value}
              onChange={(e) =>
                handleEffectChange({
                  kind: 'layer-blur',
                  blur: { ...blur, value: Math.max(0, parseFloat(e.target.value) || 0) },
                })
              }
            />
          </div>
        </div>
      )}
    </FloatingPanelShell>
  )
}
