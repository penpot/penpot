import { useCallback, useRef, useState } from 'react'
import type { Blur, PenpotNode, Shadow } from 'penpot-exporter/types'
import type { Texture } from '../../../renderer/properties/panel-utils'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '../../../renderer/properties/commit-node-properties'
import {
  DEFAULT_SHADOW,
  DEFAULT_TEXTURE,
  MAX_EFFECTS,
  type EffectItem,
  type RectLikeNode,
} from '../../../renderer/properties/panel-utils'
import { getActiveOrSinglePageId } from '../../../renderer/store/doc-proxy'
import { EffectRow } from './EffectRow'
import { useColorEditor } from '../use-color-editor'

/** Merge shape shadow[] + blur + texture into a unified EffectItem list. */
function mergeEffects(node: RectLikeNode): EffectItem[] {
  const items: EffectItem[] = []
  for (const s of (node as Record<string, unknown>).shadow as Shadow[] ?? []) {
    items.push({ kind: s.style, shadow: s })
  }
  const blur = (node as Record<string, unknown>).blur as Blur | undefined
  if (blur) {
    items.push({ kind: 'layer-blur', blur })
  }
  const texture = (node as Record<string, unknown>).texture as Texture | undefined
  if (texture) {
    items.push({ kind: 'texture', texture })
  }
  return items
}

/** Split EffectItem list back into shadow[], blur, and texture for committing. */
function splitEffects(effects: EffectItem[]): { shadow: Shadow[]; blur: Blur | undefined; texture: Texture | undefined } {
  const shadows: Shadow[] = []
  let blur: Blur | undefined
  let texture: Texture | undefined
  for (const e of effects) {
    if (e.kind === 'layer-blur') {
      blur = e.blur
    } else if (e.kind === 'texture') {
      texture = e.texture
    } else {
      shadows.push(e.shadow)
    }
  }
  return { shadow: shadows, blur, texture }
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
      const { shadow, blur, texture } = splitEffects(next)
      const partial: Record<string, unknown> = { shadow }
      // Include blur when it has a value or when clearing a previously set blur.
      // Use null (not undefined) to clear, since commitNodePartialUpdate skips undefined.
      const hadBlur = (before as Record<string, unknown>).blur != null
      if (blur !== undefined || hadBlur) {
        partial.blur = blur ?? null
      }
      // Include texture when it has a value or when clearing a previously set texture.
      const hadTexture = (before as Record<string, unknown>).texture != null
      if (texture !== undefined || hadTexture) {
        partial.texture = texture ?? null
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
