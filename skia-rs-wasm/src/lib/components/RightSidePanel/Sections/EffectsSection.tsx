import { useCallback, useRef, useState } from 'react'
import type { Blur, Glass, PenpotNode, Shadow } from 'penpot-exporter/types'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import {
  DEFAULT_SHADOW,
  MAX_EFFECTS,
  type EffectItem,
  type RectLikeNode,
} from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import { EffectRow } from './EffectRow'
import { useColorEditor } from '../use-color-editor'

/** Merge shape shadow[] + blur + glass into a unified EffectItem list. */
function mergeEffects(node: RectLikeNode): EffectItem[] {
  const items: EffectItem[] = []
  for (const s of (node as Record<string, unknown>).shadow as Shadow[] ?? []) {
    items.push({ kind: s.style, shadow: s })
  }
  const blur = (node as Record<string, unknown>).blur as Blur | undefined
  if (blur) {
    const kind = blur.type === 'background-blur' ? 'background-blur' : 'layer-blur'
    items.push({ kind, blur })
  }
  const glass = (node as Record<string, unknown>).glass as Glass | undefined
  if (glass) {
    items.push({ kind: 'glass', glass })
  }
  return items
}

/** Split EffectItem list back into shadow[], blur, and glass for committing. */
function splitEffects(effects: EffectItem[]): { shadow: Shadow[]; blur: Blur | undefined; glass: Glass | undefined } {
  const shadows: Shadow[] = []
  let blur: Blur | undefined
  let glass: Glass | undefined
  for (const e of effects) {
    if (e.kind === 'layer-blur' || e.kind === 'background-blur') {
      blur = e.blur
    } else if (e.kind === 'glass') {
      glass = e.glass
    } else {
      shadows.push(e.shadow)
    }
  }
  return { shadow: shadows, blur, glass }
}

export interface EffectsSectionProps {
  nodeId: string
  readOnly: boolean
  initialNode: RectLikeNode
}

export function EffectsSection({ nodeId, readOnly, initialNode }: EffectsSectionProps) {
  const { activeTarget, closeEditor } = useColorEditor()
  const [effects, setEffects] = useState<EffectItem[]>(() => mergeEffects(initialNode))
  const [collapsed, setCollapsed] = useState(false)

  // Render-phase sync: when initialNode changes externally, reset local state.
  const prevNodeRef = useRef(initialNode)
  if (prevNodeRef.current !== initialNode) {
    prevNodeRef.current = initialNode
    setEffects(mergeEffects(initialNode))
  }

  const commitEffects = useCallback(
    async (next: EffectItem[]) => {
      if (readOnly) return
      const before = getCommittedNodeOnActivePage(nodeId)
      const pid = getActiveOrSinglePageId()
      if (!before || !pid) return
      const { shadow, blur, glass } = splitEffects(next)
      const partial: Record<string, unknown> = { shadow }
      // Include blur when it has a value or when clearing a previously set blur.
      // Use null (not undefined) to clear, since commitNodePartialUpdate skips undefined.
      const hadBlur = (before as Record<string, unknown>).blur != null
      if (blur !== undefined || hadBlur) {
        partial.blur = blur ?? null
      }
      const hadGlass = (before as Record<string, unknown>).glass != null
      if (glass !== undefined || hadGlass) {
        partial.glass = glass ?? null
      }
      await commitNodePartialUpdate(nodeId, before, partial as Partial<PenpotNode>, pid)
    },
    [readOnly, nodeId],
  )

  const onEffectChange = useCallback(
    (effect: EffectItem, index: number) => {
      const next = [...effects]
      if (index < 0 || index >= next.length) return
      next[index] = effect
      setEffects(next)
      void commitEffects(next)
    },
    [effects, commitEffects],
  )

  const addEffect = useCallback(() => {
    if (effects.length >= MAX_EFFECTS) return
    const next: EffectItem[] = [...effects, { kind: 'drop-shadow', shadow: { ...DEFAULT_SHADOW } }]
    setEffects(next)
    void commitEffects(next)
  }, [effects, commitEffects])

  const removeEffect = useCallback(
    (index: number) => {
      // Close color editor if removing the shadow being edited
      if (activeTarget?.kind === 'shadow' && activeTarget.index === index) closeEditor()
      const next = effects.filter((_, i) => i !== index)
      setEffects(next)
      void commitEffects(next)
    },
    [effects, commitEffects, activeTarget, closeEditor],
  )

  const hasEffects = effects.length > 0
  const canAdd = !readOnly && effects.length < MAX_EFFECTS

  return (
    <>
      <Separator />
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2 py-0.5">
          <button
            type="button"
            className="flex min-h-8 flex-1 items-center gap-1 text-left text-xs font-medium tracking-wide text-muted-foreground uppercase hover:text-foreground"
            onClick={() => setCollapsed((c) => !c)}
            aria-expanded={!collapsed}
          >
            {collapsed ? (
              <ChevronRight className="size-3.5 shrink-0" aria-hidden />
            ) : (
              <ChevronDown className="size-3.5 shrink-0" aria-hidden />
            )}
            Effects
          </button>
          {!readOnly && (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={addEffect}
              disabled={!canAdd}
              aria-label="Add effect"
              title={canAdd ? 'Add effect' : `Maximum ${MAX_EFFECTS} effects`}
            >
              +
            </Button>
          )}
        </div>

        {!collapsed && hasEffects && (
          <div className="space-y-2 pl-0.5">
            {effects.map((effect, i) => (
              <EffectRow
                key={i}
                effect={effect}
                index={i}
                readOnly={readOnly}
                onChange={onEffectChange}
                onRemove={removeEffect}
              />
            ))}
          </div>
        )}

        {!collapsed && !hasEffects && !readOnly && (
          <p className="text-xs text-muted-foreground">No effects. Use + to add.</p>
        )}
      </div>
    </>
  )
}
