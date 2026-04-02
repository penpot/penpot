/**
 * Fill editor: solid color or gradient (linear/radial/angular) with preview bar, type selector, and stops list.
 * Matches frontend colorpicker layout; uses inline styles and parent form classes.
 */

import { useCallback, useMemo, useRef, useState } from 'react'
import type { Fill, Gradient } from 'penpot-exporter/types'
import { isAngularGradient, isColorFill, isLinearGradient, isRadialGradient } from '../../renderer/api/constants'
import { MAX_GRADIENT_STOPS } from '../../renderer/api/constants'

export type FillEditorMode = 'solid' | 'linear' | 'radial' | 'angular'

const DEFAULT_SOLID: Fill = { fillColor: '#3B82F6', fillOpacity: 1 }
const defaultStops = [
  { offset: 0, color: '#3B82F6', opacity: 1 },
  { offset: 1, color: '#1E40AF', opacity: 1 },
]
const defaultLinearGradient: Gradient = {
  type: 'linear',
  startX: 0,
  startY: 0,
  endX: 1,
  endY: 0,
  width: 0,
  stops: [...defaultStops],
}
const defaultRadialGradient: Gradient = {
  type: 'radial',
  startX: 0.5,
  startY: 0.5,
  endX: 0.5,
  endY: 0.2,
  width: 0.5,
  stops: [...defaultStops],
}
const defaultAngularGradient: Gradient = {
  type: 'angular',
  startX: 0.5,
  startY: 0.5,
  endX: 1,
  endY: 0.5,
  width: 1,
  stops: [...defaultStops],
}

function getMode(fill: Fill): FillEditorMode {
  if (isColorFill(fill)) return 'solid'
  if (isLinearGradient(fill)) return 'linear'
  if (isRadialGradient(fill)) return 'radial'
  if (isAngularGradient(fill)) return 'angular'
  return 'solid'
}

function fillToStops(fill: Fill): { offset: number; color: string; opacity?: number }[] {
  const g = fill.fillColorGradient
  if (!g?.stops?.length) return [...defaultStops]
  return g.stops.slice(0, MAX_GRADIENT_STOPS).map((s) => ({
    offset: s.offset,
    color: s.color,
    opacity: s.opacity ?? 1,
  }))
}

function stopsToGradientCss(stops: { offset: number; color: string; opacity?: number }[]): string {
  const sorted = [...stops].sort((a, b) => a.offset - b.offset)
  const parts = sorted.map((s) => {
    const o = Math.round(s.offset * 100)
    const opacity = s.opacity ?? 1
    const hex = s.color
    const r = parseInt(hex.slice(1, 3), 16)
    const g = parseInt(hex.slice(3, 5), 16)
    const b = parseInt(hex.slice(5, 7), 16)
    return `rgba(${r},${g},${b},${opacity}) ${o}%`
  })
  return `linear-gradient(90deg, ${parts.join(', ')})`
}

export interface FillEditorProps {
  fill: Fill
  onChange: (fill: Fill) => void
  maxGradientStops?: number
  /** When true, the Type dropdown is hidden (e.g. when parent provides its own type selector). */
  hideTypeSelector?: boolean
  /** Sidebar row shows swatch/hex; hide duplicate chrome and wrap in a bordered panel. */
  embeddedInRow?: boolean
}

export function FillEditor({
  fill,
  onChange,
  maxGradientStops = MAX_GRADIENT_STOPS,
  hideTypeSelector = false,
  embeddedInRow = false,
}: FillEditorProps) {
  const formGroupClass = 'mb-3 space-y-1'
  const labelClass = 'block text-xs font-medium text-muted-foreground'
  const inputClass = 'w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm'

  const [selectedStopIndex, setSelectedStopIndex] = useState<number | null>(null)
  const barRef = useRef<HTMLDivElement>(null)
  const [dragState, setDragState] = useState<{ sortedIndex: number; offset: number } | null>(null)
  const dragStartRef = useRef<{ startX: number; startOffset: number; sortedIndex: number } | null>(null)

  const mode = getMode(fill)
  const isGradient = mode === 'linear' || mode === 'radial' || mode === 'angular'
  const gradient = fill.fillColorGradient
  const stops = useMemo(() => fillToStops(fill), [fill])
  const sortedStops = useMemo(() => [...stops].sort((a, b) => a.offset - b.offset), [stops])

  const setMode = useCallback(
    (newMode: FillEditorMode) => {
      if (newMode === 'solid') {
        onChange({ ...DEFAULT_SOLID })
        return
      }
      if (newMode === 'linear') onChange({ fillColorGradient: { ...defaultLinearGradient } })
      else if (newMode === 'radial') onChange({ fillColorGradient: { ...defaultRadialGradient } })
      else if (newMode === 'angular') onChange({ fillColorGradient: { ...defaultAngularGradient } })
    },
    [onChange]
  )

  const setStops = useCallback(
    (newStops: { offset: number; color: string; opacity?: number }[]) => {
      if (!gradient) return
      const sorted = [...newStops].sort((a, b) => a.offset - b.offset)
      onChange({ fillColorGradient: { ...gradient, stops: sorted } })
    },
    [gradient, onChange]
  )



  const updateStop = useCallback(
    (sortedIndex: number, patch: Partial<{ offset: number; color: string; opacity: number }>) => {
      const newStops = sortedStops.map((s, i) => (i === sortedIndex ? { ...s, ...patch } : s))
      setStops(newStops)
    },
    [sortedStops, setStops]
  )

  const addStop = useCallback(() => {
    if (sortedStops.length >= maxGradientStops) return
    let bestT = 0.5
    if (sortedStops.length >= 2) {
      let maxGap = 0
      for (let i = 0; i < sortedStops.length; i++) {
        const next = sortedStops[i + 1]
        const end = next !== undefined ? next.offset : 1
        const start = sortedStops[i].offset
        const gap = end - start
        if (gap > maxGap) {
          maxGap = gap
          bestT = (start + end) / 2
        }
      }
    }
    const newStop = { offset: Math.round(bestT * 100) / 100, color: '#6B7280', opacity: 1 }
    setStops([...sortedStops, newStop])
    setSelectedStopIndex(sortedStops.length)
  }, [sortedStops, maxGradientStops, setStops])

  const removeStop = useCallback(
    (index: number) => {
      if (sortedStops.length <= 1) return
      const newStops = sortedStops.filter((_, i) => i !== index)
      setStops(newStops)
      setSelectedStopIndex(null)
    },
    [sortedStops, setStops]
  )

  const rotateStops = useCallback(() => {
    if (sortedStops.length < 2) return
    const [first, ...rest] = sortedStops
    const rotated = [...rest, first]
    const reoffset = rotated.map((s, i) => ({ ...s, offset: i / (rotated.length - 1) }))
    setStops(reoffset)
  }, [sortedStops, setStops])

  const reverseStops = useCallback(() => {
    const reversed = [...sortedStops].reverse()
    const reoffset = reversed.map((s, i) => ({ ...s, offset: i / (reversed.length - 1) || 0 }))
    setStops(reoffset)
  }, [sortedStops, setStops])

  const handleBarClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (!e.currentTarget) return
      const rect = e.currentTarget.getBoundingClientRect()
      const t = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
      if (sortedStops.length >= maxGradientStops) return
      const newStop = { offset: Math.round(t * 100) / 100, color: '#6B7280', opacity: 1 }
      setStops([...sortedStops, newStop])
      setSelectedStopIndex(sortedStops.length)
    },
    [sortedStops, maxGradientStops, setStops]
  )

  const fillOpacity = isGradient ? 1 : (fill.fillOpacity ?? 1)
  const setFillOpacity = useCallback(
    (value: number) => {
      if (isGradient) return
      onChange({ ...fill, fillOpacity: value })
    },
    [fill, isGradient, onChange]
  )

  const solidColor = fill.fillColor ?? '#3B82F6'
  const setSolidColor = useCallback(
    (color: string) => {
      onChange({ ...fill, fillColor: color })
    },
    [fill, onChange]
  )

  const shellClass = embeddedInRow
    ? 'mt-1 rounded-md border border-border bg-muted/20 p-2'
    : `${formGroupClass} mt-2`

  return (
    <div className={shellClass}>
      {!embeddedInRow && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <span style={{ fontSize: 12, fontWeight: 500, color: '#6B7280' }}>
            {isGradient ? 'Gradient' : 'Solid'}
          </span>
          {!isGradient && (
            <>
              <label style={{ fontSize: 11, color: '#9CA3AF' }}>Opacity (%)</label>
              <input
                type="number"
                value={Math.round(fillOpacity * 100)}
                onChange={(e) => setFillOpacity(Number(e.target.value) / 100)}
                min={0}
                max={100}
                style={{ width: 48, padding: '4px 6px', fontSize: 12 }}
              />
            </>
          )}
        </div>
      )}

      {!hideTypeSelector && (
        <div className={formGroupClass}>
          <label className={labelClass}>Type</label>
          <select
            value={mode}
            onChange={(e) => setMode(e.target.value as FillEditorMode)}
            className={inputClass}
          >
            <option value="solid">Solid color</option>
            <option value="linear">Linear</option>
            <option value="radial">Radial</option>
            <option value="angular">Angular</option>
          </select>
        </div>
      )}

      {mode === 'solid' && !embeddedInRow && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div
              style={{
                width: 32,
                height: 24,
                borderRadius: 4,
                background: solidColor,
                border: '1px solid #D1D5DB',
              }}
            />
            <input
              type="color"
              value={solidColor}
              onChange={(e) => setSolidColor(e.target.value)}
              style={{ width: 36, height: 28, padding: 0, border: '1px solid #D1D5DB', borderRadius: 4 }}
            />
            <input
              type="text"
              value={solidColor}
              onChange={(e) => {
                const v = e.target.value
                if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v)) setSolidColor(v)
              }}
              style={{ flex: 1, padding: '4px 8px', fontSize: 12 }}
              placeholder="#hex"
            />
          </div>
        </div>
      )}

      {isGradient && gradient && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 8 }}>
            <span style={{ fontSize: 12, color: '#6B7280', flex: 1 }}>
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </span>
            <button
              type="button"
              onClick={rotateStops}
              title="Rotate gradient"
              style={{ padding: '4px 8px', fontSize: 14 }}
            >
              ↻
            </button>
            <button
              type="button"
              onClick={reverseStops}
              title="Reverse"
              style={{ padding: '4px 8px', fontSize: 14 }}
            >
              ⇄
            </button>
            <button
              type="button"
              onClick={addStop}
              disabled={sortedStops.length >= maxGradientStops}
              title="Add stop"
              style={{ padding: '4px 8px', fontSize: 14 }}
            >
              +
            </button>
          </div>

          <div style={{ marginBottom: 8 }}>
            <div
              ref={barRef}
              role="button"
              tabIndex={0}
              onClick={handleBarClick}
              onKeyDown={(e) => e.key === 'Enter' && handleBarClick(e as unknown as React.MouseEvent<HTMLDivElement>)}
              style={{
                height: 24,
                borderRadius: 4,
                background: stopsToGradientCss(sortedStops),
                border: '1px solid #D1D5DB',
                cursor: 'pointer',
                position: 'relative',
              }}
            >
              {sortedStops.map((stop, i) => {
                const isDragging = dragState?.sortedIndex === i
                const displayOffset = isDragging ? dragState.offset : stop.offset
                return (
                  <div
                    key={i}
                    role="button"
                    tabIndex={0}
                    data-index={i}
                    onClick={(e) => {
                      e.stopPropagation()
                      setSelectedStopIndex(i)
                    }}
                    onPointerDown={(e) => {
                      e.stopPropagation()
                      setSelectedStopIndex(i)
                      const bar = barRef.current?.getBoundingClientRect()
                      if (!bar) return
                      dragStartRef.current = { startX: e.clientX, startOffset: stop.offset, sortedIndex: i }
                      setDragState({ sortedIndex: i, offset: stop.offset })
                        ; (e.currentTarget as HTMLDivElement).setPointerCapture(e.pointerId)
                    }}
                    onPointerMove={(e) => {
                      if (!dragStartRef.current || dragStartRef.current.sortedIndex !== i) return
                      const bar = barRef.current?.getBoundingClientRect()
                      if (!bar) return
                      const delta = (e.clientX - dragStartRef.current.startX) / bar.width
                      const newOffset = Math.max(
                        0,
                        Math.min(1, Math.round((dragStartRef.current.startOffset + delta) * 100) / 100)
                      )
                      setDragState({ sortedIndex: i, offset: newOffset })
                      updateStop(i, { offset: newOffset })
                    }}
                    onPointerUp={(e) => {
                      setDragState((current) => {
                        if (current) {
                          updateStop(current.sortedIndex, { offset: current.offset })
                          dragStartRef.current = null
                        }
                        return null
                      })
                        ; (e.currentTarget as HTMLDivElement).releasePointerCapture(e.pointerId)
                    }}
                    onLostPointerCapture={() => {
                      setDragState((current) => {
                        if (current) {
                          updateStop(current.sortedIndex, { offset: current.offset })
                          dragStartRef.current = null
                        }
                        return null
                      })
                    }}
                    style={{
                      position: 'absolute',
                      left: `${displayOffset * 100}%`,
                      top: -2,
                      transform: 'translateX(-50%)',
                      width: 20,
                      height: 20,
                      borderRadius: 4,
                      border: selectedStopIndex === i ? '2px solid #3B82F6' : '1px solid #9CA3AF',
                      background: stop.color,
                      boxSizing: 'border-box',
                      cursor: isDragging ? 'grabbing' : 'grab',
                    }}
                  />
                )
              })}
            </div>
          </div>

          <div style={{ fontSize: 11, color: '#6B7280', marginBottom: 6, fontWeight: 500 }}>
            Stops
          </div>
          <div style={{ maxHeight: 180, overflowY: 'auto' }}>
            {sortedStops.map((stop, i) => (
              <div
                key={i}
                role="button"
                tabIndex={0}
                onClick={() => setSelectedStopIndex(i)}
                onKeyDown={(e) => e.key === 'Enter' && setSelectedStopIndex(i)}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '4px 0',
                  borderBottom: '1px solid #E5E7EB',
                  backgroundColor: selectedStopIndex === i ? 'rgba(59, 130, 246, 0.08)' : undefined,
                  cursor: 'pointer',
                }}
              >
                <span style={{ fontSize: 11, color: '#6B7280', width: 28 }}>Pos</span>
                <input
                  type="number"
                  value={Math.round(stop.offset * 100)}
                  onChange={(e) => {
                    e.stopPropagation()
                    updateStop(i, { offset: Number(e.target.value) / 100 })
                  }}
                  onClick={(e) => e.stopPropagation()}
                  min={0}
                  max={100}
                  style={{ width: 44, padding: '2px 4px', fontSize: 12 }}
                />
                <label
                  style={{
                    position: 'relative',
                    width: 24,
                    height: 20,
                    borderRadius: 2,
                    background: stop.color,
                    border: '1px solid #D1D5DB',
                    cursor: 'pointer',
                    margin: 0,
                    flexShrink: 0,
                  }}
                  onClick={(e) => {
                    e.stopPropagation()
                    setSelectedStopIndex(i)
                  }}
                >
                  <input
                    type="color"
                    value={stop.color}
                    onChange={(e) => updateStop(i, { color: e.target.value })}
                    style={{
                      position: 'absolute',
                      inset: 0,
                      width: '100%',
                      height: '100%',
                      opacity: 0,
                      cursor: 'pointer',
                    }}
                    tabIndex={-1}
                  />
                </label>
                <input
                  type="text"
                  value={stop.color}
                  onChange={(e) => {
                    const v = e.target.value
                    if (/^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{3}$/.test(v))
                      updateStop(i, { color: v })
                  }}
                  onClick={(e) => e.stopPropagation()}
                  style={{ flex: 1, padding: '2px 4px', fontSize: 11, minWidth: 0 }}
                />
                <span style={{ fontSize: 11, color: '#9CA3AF' }}>%</span>
                <input
                  type="number"
                  value={Math.round((stop.opacity ?? 1) * 100)}
                  onChange={(e) => {
                    e.stopPropagation()
                    updateStop(i, { opacity: Number(e.target.value) / 100 })
                  }}
                  onClick={(e) => e.stopPropagation()}
                  min={0}
                  max={100}
                  style={{ width: 40, padding: '2px 4px', fontSize: 12 }}
                />
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation()
                    removeStop(i)
                  }}
                  disabled={sortedStops.length <= 1}
                  style={{ padding: '2px 6px', fontSize: 12 }}
                  title="Remove stop"
                >
                  −
                </button>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
