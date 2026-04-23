/**
 * Shared shape icons. The custom SVGs are used both in the bottom toolbar
 * and in the layers panel so a "rect" layer shows the same pictogram that
 * was clicked to create it.
 */

import {
  Circle,
  Component,
  Hexagon,
  Image,
  Layers,
  Pencil,
  Square,
  Type,
} from 'lucide-react'

interface IconProps {
  className?: string
}

export function IconSelect({ className }: IconProps) {
  return (
    <svg className={className} width="20" height="20" viewBox="0 0 20 20" aria-hidden>
      <path
        fill="currentColor"
        d="M4.5 3.5L15 11h-4.25L12 16.5 4.5 9.25H8.75L4.5 3.5z"
      />
    </svg>
  )
}

export function IconRect({ className }: IconProps) {
  return (
    <svg className={className} width="20" height="20" viewBox="0 0 20 20" aria-hidden>
      <rect
        x="3.5"
        y="4.5"
        width="13"
        height="12"
        rx="1.5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.75"
      />
    </svg>
  )
}

export function IconFrame({ className }: IconProps) {
  return (
    <svg className={className} width="20" height="20" viewBox="0 0 20 20" aria-hidden>
      <path
        fill="none"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        d="M6 3v14M14 3v14M3 6h14M3 14h14"
      />
    </svg>
  )
}

export function ShapeIcon({ type, className }: { type: string; className?: string }) {
  switch (type) {
    case 'frame':
      return <IconFrame className={className} />
    case 'rect':
      return <IconRect className={className} />
    case 'circle':
      return <Circle className={className} />
    case 'text':
      return <Type className={className} />
    case 'image':
      return <Image className={className} />
    case 'path':
      return <Pencil className={className} />
    case 'bool':
      return <Hexagon className={className} />
    case 'group':
      return <Layers className={className} />
    case 'component':
    case 'instance':
      return <Component className={className} />
    default:
      return <Square className={className} />
  }
}
