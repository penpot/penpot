import { useCallback, useEffect, useState } from 'react'
import type { PenpotNode } from 'penpot-exporter/types'
import {
  AlignEndHorizontal,
  AlignEndVertical,
  AlignHorizontalJustifyCenter,
  AlignHorizontalJustifyEnd,
  AlignHorizontalJustifyStart,
  AlignHorizontalSpaceAround,
  AlignHorizontalSpaceBetween,
  AlignStartHorizontal,
  AlignStartVertical,
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  StretchHorizontal,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  commitNodePartialUpdate,
  getCommittedNodeOnActivePage,
} from '@/lib/renderer/properties/commit-node-properties'
import { getActiveOrSinglePageId } from '@/lib/renderer/store/doc-proxy'
import type { RectLikeNode } from '@/lib/renderer/properties/panel-utils'

type FlexDir = 'row' | 'row-reverse' | 'column' | 'column-reverse'
type WrapType = 'wrap' | 'nowrap'
type JustifyContent =
  | 'start'
  | 'center'
  | 'end'
  | 'space-between'
  | 'space-around'
  | 'space-evenly'
type AlignItems = 'start' | 'center' | 'end' | 'stretch'
type Gap = { rowGap: number; columnGap: number }
type Padding = { p1: number; p2: number; p3: number; p4: number }

interface LayoutView {
  layoutFlexDir?: FlexDir
  layoutWrapType?: WrapType
  layoutJustifyContent?: JustifyContent
  layoutAlignItems?: AlignItems
  layoutGap?: Gap
  layoutPadding?: Padding
}

export interface LayoutFlexBodyProps {
  nodeId: string
  initialNode: RectLikeNode
  readOnly: boolean
}

const DIRECTIONS: ReadonlyArray<{ value: FlexDir; Icon: typeof ArrowRight; label: string }> = [
  { value: 'row', Icon: ArrowRight, label: 'Row' },
  { value: 'row-reverse', Icon: ArrowLeft, label: 'Row reverse' },
  { value: 'column', Icon: ArrowDown, label: 'Column' },
  { value: 'column-reverse', Icon: ArrowUp, label: 'Column reverse' },
]

const JUSTIFY_ROW: ReadonlyArray<{
  value: JustifyContent
  Icon: typeof ArrowRight
  label: string
}> = [
  { value: 'start', Icon: AlignHorizontalJustifyStart, label: 'Start' },
  { value: 'center', Icon: AlignHorizontalJustifyCenter, label: 'Center' },
  { value: 'end', Icon: AlignHorizontalJustifyEnd, label: 'End' },
  { value: 'space-between', Icon: AlignHorizontalSpaceBetween, label: 'Space between' },
  { value: 'space-around', Icon: AlignHorizontalSpaceAround, label: 'Space around' },
  { value: 'space-evenly', Icon: StretchHorizontal, label: 'Space evenly' },
]

const ALIGN_ROW: ReadonlyArray<{
  value: AlignItems
  Icon: typeof ArrowRight
  label: string
}> = [
  { value: 'start', Icon: AlignStartHorizontal, label: 'Start' },
  { value: 'center', Icon: AlignEndVertical, label: 'Center' },
  { value: 'end', Icon: AlignEndHorizontal, label: 'End' },
  { value: 'stretch', Icon: AlignStartVertical, label: 'Stretch' },
]

function readLayout(node: RectLikeNode): LayoutView {
  return node as LayoutView
}

export function LayoutFlexBody({ nodeId, initialNode, readOnly }: LayoutFlexBodyProps) {
  const initial = readLayout(initialNode)
  const [direction, setDirection] = useState<FlexDir>(initial.layoutFlexDir ?? 'row')
  const [wrap, setWrap] = useState<WrapType>(initial.layoutWrapType ?? 'nowrap')
  const [justify, setJustify] = useState<JustifyContent>(
    (initial.layoutJustifyContent as JustifyContent | undefined) ?? 'start',
  )
  const [align, setAlign] = useState<AlignItems>(initial.layoutAlignItems ?? 'start')
  const [gap, setGap] = useState<number>(initial.layoutGap?.rowGap ?? 0)
  const [padding, setPadding] = useState<number>(initial.layoutPadding?.p1 ?? 0)
  const [gapDraft, setGapDraft] = useState<string | null>(null)
  const [paddingDraft, setPaddingDraft] = useState<string | null>(null)

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- mirrors external document updates */
    const v = readLayout(initialNode)
    setDirection(v.layoutFlexDir ?? 'row')
    setWrap(v.layoutWrapType ?? 'nowrap')
    setJustify((v.layoutJustifyContent as JustifyContent | undefined) ?? 'start')
    setAlign(v.layoutAlignItems ?? 'start')
    setGap(v.layoutGap?.rowGap ?? 0)
    setPadding(v.layoutPadding?.p1 ?? 0)
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialNode])

  const commit = useCallback(
    async (partial: Partial<PenpotNode>) => {
      if (readOnly) return
      const before = getCommittedNodeOnActivePage(nodeId)
      const pid = getActiveOrSinglePageId()
      if (!before || !pid) return
      await commitNodePartialUpdate(nodeId, before, partial, pid)
    },
    [nodeId, readOnly],
  )

  const onDirection = (v: FlexDir) => {
    setDirection(v)
    void commit({ layoutFlexDir: v } as Partial<PenpotNode>)
  }
  const onWrap = (v: WrapType) => {
    setWrap(v)
    void commit({ layoutWrapType: v } as Partial<PenpotNode>)
  }
  const onJustify = (v: JustifyContent) => {
    setJustify(v)
    void commit({ layoutJustifyContent: v } as Partial<PenpotNode>)
  }
  const onAlign = (v: AlignItems) => {
    setAlign(v)
    void commit({ layoutAlignItems: v } as Partial<PenpotNode>)
  }
  const commitGap = (value: number) => {
    setGap(value)
    void commit({ layoutGap: { rowGap: value, columnGap: value } } as Partial<PenpotNode>)
  }
  const commitPadding = (value: number) => {
    setPadding(value)
    void commit({
      layoutPadding: { p1: value, p2: value, p3: value, p4: value },
    } as Partial<PenpotNode>)
  }

  return (
    <div className="min-w-0 space-y-3">
      <div className="flex min-w-0 items-center justify-between rounded-md bg-primary/10 px-2.5 py-1.5 text-xs font-medium text-primary">
        <span className="truncate">Flex layout</span>
        <span className="shrink-0 text-[10px] text-primary/70">flex</span>
      </div>

      <ControlBlock label="Direction">
        <IconRow
          items={DIRECTIONS}
          columns={4}
          value={direction}
          onChange={onDirection}
          disabled={readOnly}
        />
      </ControlBlock>

      <div className="flex w-full overflow-hidden rounded-lg border border-input">
        <WrapButton active={wrap === 'nowrap'} onClick={() => onWrap('nowrap')} disabled={readOnly}>
          No wrap
        </WrapButton>
        <WrapButton active={wrap === 'wrap'} onClick={() => onWrap('wrap')} disabled={readOnly}>
          Wrap
        </WrapButton>
      </div>

      <ControlBlock label="Justify content">
        <IconRow
          items={JUSTIFY_ROW}
          columns={6}
          value={justify}
          onChange={onJustify}
          disabled={readOnly}
        />
      </ControlBlock>

      <ControlBlock label="Align items">
        <IconRow
          items={ALIGN_ROW}
          columns={4}
          value={align}
          onChange={onAlign}
          disabled={readOnly}
        />
      </ControlBlock>

      <ControlBlock label="Gap">
        <NumberWithSuffix
          id="rsp-flex-gap"
          value={gapDraft ?? String(gap)}
          disabled={readOnly}
          suffix="px"
          onChange={(s) => setGapDraft(s)}
          onBlur={() => {
            const n = Math.max(0, parseFloat(gapDraft ?? String(gap)) || 0)
            setGapDraft(null)
            commitGap(n)
          }}
        />
      </ControlBlock>

      <ControlBlock label="Padding">
        <NumberWithSuffix
          id="rsp-flex-padding"
          value={paddingDraft ?? String(padding)}
          disabled={readOnly}
          suffix="px"
          onChange={(s) => setPaddingDraft(s)}
          onBlur={() => {
            const n = Math.max(0, parseFloat(paddingDraft ?? String(padding)) || 0)
            setPaddingDraft(null)
            commitPadding(n)
          }}
        />
      </ControlBlock>
    </div>
  )
}

function ControlBlock({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-[10px] font-medium tracking-wide text-muted-foreground uppercase">
        {label}
      </p>
      {children}
    </div>
  )
}

interface IconItem<V extends string> {
  value: V
  Icon: typeof ArrowRight
  label: string
}

function IconRow<V extends string>({
  items,
  columns,
  value,
  onChange,
  disabled,
}: {
  items: ReadonlyArray<IconItem<V>>
  columns: number
  value: V
  onChange: (v: V) => void
  disabled?: boolean
}) {
  return (
    <div
      className="grid w-full gap-1 rounded-md bg-muted/60 p-1"
      style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
    >
      {items.map(({ value: v, Icon, label }) => {
        const active = v === value
        return (
          <Button
            key={v}
            type="button"
            variant={active ? 'secondary' : 'ghost'}
            size="icon-sm"
            aria-pressed={active}
            aria-label={label}
            title={label}
            disabled={disabled}
            onClick={() => onChange(v)}
            className="w-full"
          >
            <Icon className="size-3.5" aria-hidden />
          </Button>
        )
      })}
    </div>
  )
}

function WrapButton({
  active,
  onClick,
  disabled,
  children,
}: {
  active: boolean
  onClick: () => void
  disabled?: boolean
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      disabled={disabled}
      onClick={onClick}
      className={
        'h-7 flex-1 px-3 text-xs font-medium transition-colors ' +
        (active
          ? 'bg-secondary text-foreground'
          : 'bg-transparent text-muted-foreground hover:bg-muted hover:text-foreground') +
        ' disabled:pointer-events-none disabled:opacity-50'
      }
    >
      {children}
    </button>
  )
}

function NumberWithSuffix({
  id,
  value,
  onChange,
  onBlur,
  disabled,
  suffix,
}: {
  id: string
  value: string
  onChange: (s: string) => void
  onBlur: () => void
  disabled?: boolean
  suffix: string
}) {
  return (
    <div className="relative">
      <Input
        id={id}
        type="number"
        min={0}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        className="pr-8"
      />
      <span className="pointer-events-none absolute inset-y-0 right-2.5 flex items-center text-xs text-muted-foreground">
        {suffix}
      </span>
    </div>
  )
}
