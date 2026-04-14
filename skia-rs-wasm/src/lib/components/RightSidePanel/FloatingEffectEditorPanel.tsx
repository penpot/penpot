import { useCallback } from 'react'
import type { Blur, Fill, Glass, Shadow } from 'penpot-exporter/types'
import { Eye, EyeOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import type { EffectItem, EffectKind } from '../../renderer/properties/panel-utils'
import { normalizeHex } from '../../renderer/properties/panel-utils'
import { isColorFill } from '@/lib/renderer/verification'
import { fillSwatchBackground } from '../FillEditor/fill-swatch-background'
import { useColorEditor } from './use-color-editor'
import { FloatingPanelShell } from './FloatingPanelShell'

const EFFECT_KIND_OPTIONS: { value: EffectKind; label: string }[] = [
  { value: 'drop-shadow', label: 'Drop shadow' },
  { value: 'inner-shadow', label: 'Inner shadow' },
  { value: 'layer-blur', label: 'Layer blur' },
  { value: 'background-blur', label: 'Background blur' },
  { value: 'glass', label: 'Glass' },
]

// ── Surface profile SVG icons (16×16 viewBox, cross-section curves) ──

/** Circle — spherical dome */
function IconCircle({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" className={className} fill="none" stroke="currentColor" strokeWidth="1.5">
      <path d="M2 12 Q2 4 8 4 Q14 4 14 12" />
      <line x1="2" y1="12" x2="14" y2="12" />
    </svg>
  )
}

/** Squircle — flatter superellipse dome */
function IconSquircle({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" className={className} fill="none" stroke="currentColor" strokeWidth="1.5">
      <path d="M2 12 Q2 6 5 5 Q8 4.5 8 4.5 Q8 4.5 11 5 Q14 6 14 12" />
      <line x1="2" y1="12" x2="14" y2="12" />
    </svg>
  )
}

/** Concave — bowl/dip */
function IconConcave({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" className={className} fill="none" stroke="currentColor" strokeWidth="1.5">
      <path d="M2 5 Q2 12 8 12 Q14 12 14 5" />
      <line x1="2" y1="5" x2="14" y2="5" />
    </svg>
  )
}

/** Lip — raised rim with center depression */
function IconLip({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" className={className} fill="none" stroke="currentColor" strokeWidth="1.5">
      <path d="M2 12 Q2 5 5 5 Q7 5 7 8 Q8 9.5 9 8 Q11 5 14 5 Q14 5 14 12" />
      <line x1="2" y1="12" x2="14" y2="12" />
    </svg>
  )
}

const SURFACE_TABS = [
  { value: 0, label: 'Circle', icon: IconCircle },
  { value: 1, label: 'Squircle', icon: IconSquircle },
  { value: 2, label: 'Concave', icon: IconConcave },
  { value: 3, label: 'Lip', icon: IconLip },
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

/** Labeled number input for effect controls */
function EffectField({
  label,
  title,
  value,
  min,
  max,
  step,
  onChange,
}: {
  label: string
  title: string
  value: number
  min: number
  max: number
  step: number
  onChange: (v: number) => void
}) {
  return (
    <div className="flex items-center gap-2" title={title}>
      <span className="w-28 shrink-0 text-[11px] font-medium text-muted-foreground">{label}</span>
      <Input
        type="number"
        className="h-7 min-w-0 flex-1 px-1.5 text-xs"
        min={min}
        max={max}
        step={step}
        value={value % 1 !== 0 ? parseFloat(value.toFixed(2)) : value}
        onChange={(e) => onChange(Math.min(max, Math.max(min, parseFloat(e.target.value) || 0)))}
      />
    </div>
  )
}

export function FloatingEffectEditorPanel() {
  const { activeTarget, activeEffect, anchorY, title, closeEditor, onEffectChangeRef } = useColorEditor()

  const targetKey =
    activeTarget && activeEffect
      ? `effect-${activeTarget.kind}-${activeTarget.index}`
      : null

  const handleEffectChange = useCallback(
    (next: EffectItem) => {
      onEffectChangeRef.current?.(next)
    },
    [onEffectChangeRef],
  )

  if (!targetKey || !activeEffect) return null

  const isShadow = activeEffect.kind === 'drop-shadow' || activeEffect.kind === 'inner-shadow'
  const isBlur = activeEffect.kind === 'layer-blur' || activeEffect.kind === 'background-blur'
  const isGlass = activeEffect.kind === 'glass'

  const shadow = isShadow ? activeEffect.shadow : null
  const blur = isBlur ? activeEffect.blur : null
  const glass = isGlass ? activeEffect.glass : null

  // ── Shadow color helpers ──
  const shadowFill = shadow ? shadowColorToFill(shadow) : null
  const isSolid = shadowFill ? isColorFill(shadowFill) : false
  const swatchBg = shadowFill ? fillSwatchBackground(shadowFill) : '#94a3b8'
  const hexDisplay = shadow
    ? isSolid
      ? (shadow.color?.color ?? '#000000')
      : 'Gradient'
    : ''
  const opacity = shadow?.color?.opacity ?? 1

  // ── Update handlers ──
  const handleGlassUpdate = (partial: Partial<Glass>) => {
    if (!glass) return
    handleEffectChange({ kind: 'glass', glass: { ...glass, ...partial } })
  }

  const handleShadowUpdate = (partial: Partial<Shadow>) => {
    if (!shadow || !isShadow) return
    handleEffectChange({ kind: activeEffect.kind as 'drop-shadow' | 'inner-shadow', shadow: { ...shadow, ...partial } })
  }

  const handleBlurUpdate = (partial: Partial<Blur>) => {
    if (!blur || !isBlur) return
    handleEffectChange({ kind: activeEffect.kind as 'layer-blur' | 'background-blur', blur: { ...blur, ...partial } })
  }

  const handleHexChange = (raw: string) => {
    if (!shadow || !isSolid) return
    const v = raw.trim()
    if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v)) {
      handleShadowUpdate({ color: { ...shadow.color, color: normalizeHex(v) } })
    }
  }

  const handleOpacityChange = (pct: number) => {
    if (!shadow || !isSolid) return
    const v = Math.max(0, Math.min(100, pct)) / 100
    handleShadowUpdate({ color: { ...shadow.color, opacity: v } })
  }

  // ── Visibility toggle (all effect types) ──
  const isHidden = isGlass
    ? glass?.hidden
    : isBlur
      ? blur?.hidden
      : shadow?.hidden

  const toggleHidden = () => {
    if (isGlass && glass) {
      handleGlassUpdate({ hidden: !glass.hidden })
    } else if (isBlur && blur) {
      handleBlurUpdate({ hidden: !blur.hidden })
    } else if (isShadow && shadow) {
      handleShadowUpdate({ hidden: !shadow.hidden })
    }
  }

  const headerContent = (
    <div className="flex min-w-0 flex-1 items-center gap-1.5">
      <select
        className="border-input bg-background h-7 min-w-0 flex-1 rounded-md border px-1.5 text-xs font-medium"
        value={activeEffect.kind}
        disabled
        title="Effect type"
      >
        {EFFECT_KIND_OPTIONS.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
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
      width={300}
      onClose={closeEditor}
    >
      {/* ── Shadow controls ── */}
      {isShadow && shadow && (
        <div className="space-y-3">
          {/* Color row: swatch + hex + opacity */}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <span className="w-28 shrink-0 text-[11px] font-medium text-muted-foreground">Color</span>
              <div
                className="size-5 shrink-0 rounded border border-border"
                style={{ background: swatchBg }}
                aria-hidden
              />
              <Input
                type="text"
                className="h-7 min-w-0 flex-1 font-mono text-xs"
                value={hexDisplay}
                placeholder={isSolid ? '#RRGGBB' : undefined}
                disabled={!isSolid}
                readOnly={!isSolid}
                onChange={(e) => handleHexChange(e.target.value)}
              />
              <span className="shrink-0 text-[11px] text-muted-foreground">%</span>
              <Input
                type="number"
                className="h-7 w-12 shrink-0 px-1 text-xs"
                min={0}
                max={100}
                value={isSolid ? Math.round(opacity * 100) : 100}
                disabled={!isSolid}
                onChange={(e) => handleOpacityChange(Number(e.target.value))}
              />
            </div>
          </div>

          {/* Property fields */}
          <div className="space-y-2">
            <EffectField label="Horizontal Offset" title="Shadow horizontal offset (px)" value={shadow.offsetX} min={-999} max={999} step={1} onChange={(v) => handleShadowUpdate({ offsetX: v })} />
            <EffectField label="Vertical Offset" title="Shadow vertical offset (px)" value={shadow.offsetY} min={-999} max={999} step={1} onChange={(v) => handleShadowUpdate({ offsetY: v })} />
            <EffectField label="Blur Radius" title="Shadow blur radius (px)" value={shadow.blur} min={0} max={999} step={1} onChange={(v) => handleShadowUpdate({ blur: v })} />
            <EffectField label="Spread Distance" title="Shadow spread distance (px)" value={shadow.spread} min={-999} max={999} step={1} onChange={(v) => handleShadowUpdate({ spread: v })} />
          </div>
        </div>
      )}

      {/* ── Blur controls ── */}
      {isBlur && blur && (
        <div className="space-y-2">
          <EffectField label="Blur Amount" title="Gaussian blur amount (px)" value={blur.value} min={0} max={999} step={1} onChange={(v) => handleBlurUpdate({ value: v })} />
        </div>
      )}

      {/* ── Glass controls ── */}
      {isGlass && glass && (
        <div className="space-y-3">
          {/* Surface type icon tabs */}
          <div className="space-y-1.5">
            <span className="text-[11px] font-medium text-muted-foreground">Surface</span>
            <div className="flex gap-1">
              {SURFACE_TABS.map((s) => {
                const Icon = s.icon
                const active = (glass.surfaceType ?? 1) === s.value
                return (
                  <button
                    key={s.value}
                    type="button"
                    onClick={() => handleGlassUpdate({ surfaceType: s.value })}
                    className={cn(
                      'flex size-8 items-center justify-center rounded-md border transition-colors',
                      active
                        ? 'border-ring bg-accent text-accent-foreground'
                        : 'border-transparent text-muted-foreground hover:bg-muted',
                    )}
                    title={s.label}
                    aria-label={s.label}
                  >
                    <Icon className="size-5" />
                  </button>
                )
              })}
            </div>
          </div>

          {/* Labeled inputs */}
          <div className="space-y-2">
            <EffectField label="Bezel Width" title="Edge transition zone (px)" value={glass.bezelWidth ?? 40} min={5} max={100} step={1} onChange={(v) => handleGlassUpdate({ bezelWidth: v })} />
            <EffectField label="Glass Thickness" title="Surface height multiplier" value={glass.glassThickness ?? 1.2} min={0.2} max={3} step={0.1} onChange={(v) => handleGlassUpdate({ glassThickness: v })} />
            <EffectField label="Refractive Index" title="Refractive index (1=air, 1.5=glass, 2.4=diamond)" value={glass.refractiveIndex ?? 1.5} min={1} max={2.5} step={0.05} onChange={(v) => handleGlassUpdate({ refractiveIndex: v })} />
            <EffectField label="Chromatic Aberration" title="Prismatic fringing (px)" value={glass.chromaticAberration ?? 3} min={0} max={10} step={0.5} onChange={(v) => handleGlassUpdate({ chromaticAberration: v })} />
            <EffectField label="Splay" title="0 = flat pane, 1 = dome lens" value={glass.splay ?? 1} min={0} max={1} step={0.05} onChange={(v) => handleGlassUpdate({ splay: v })} />
            <EffectField label="Tilt Angle" title="Pane tilt direction (degrees)" value={glass.tiltAngle ?? 0} min={-180} max={180} step={5} onChange={(v) => handleGlassUpdate({ tiltAngle: v })} />
            <EffectField label="Edge Boost" title="Bevel steepness at rim" value={glass.edgeBoost ?? 0} min={0} max={15} step={0.25} onChange={(v) => handleGlassUpdate({ edgeBoost: v })} />
            <EffectField label="Zoom %" title="Magnification — 100% = no zoom, >100% = zoom in" value={glass.zoom ?? 100} min={50} max={300} step={5} onChange={(v) => handleGlassUpdate({ zoom: v })} />
            <EffectField label="Internal Blur" title="Gaussian blur sigma" value={glass.blur ?? 0} min={0} max={20} step={0.5} onChange={(v) => handleGlassUpdate({ blur: v })} />
            <EffectField label="Frost" title="Frosted glass intensity" value={glass.frost ?? 0} min={0} max={1} step={0.05} onChange={(v) => handleGlassUpdate({ frost: v })} />
            <EffectField label="Specular Angle" title="Light direction (degrees)" value={glass.specularAngle ?? -60} min={-180} max={180} step={5} onChange={(v) => handleGlassUpdate({ specularAngle: v })} />
            <EffectField label="Specular Opacity" title="Highlight brightness" value={glass.specularOpacity ?? 0.5} min={0} max={1} step={0.05} onChange={(v) => handleGlassUpdate({ specularOpacity: v })} />
            <EffectField label="Specular Saturation" title="Prismatic highlight (0=white, 9=vivid)" value={glass.specularSaturation ?? 4} min={0} max={12} step={0.5} onChange={(v) => handleGlassUpdate({ specularSaturation: v })} />
          </div>
        </div>
      )}
    </FloatingPanelShell>
  )
}
