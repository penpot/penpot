/**
 * Serializers to convert Penpot nodes to WASM format
 * Translation functions for converting TypeScript enums to WASM enum values
 */

import type { ShapeType, BoolType } from '@skia-rs-wasm/common'
import type { BlendMode, ConstraintH, ConstraintV } from '@penpot-exporter/types'

// Enum mappings from shared.js
const RawShapeType: Record<string, number> = {
  frame: 0,
  group: 1,
  bool: 2,
  rect: 3,
  path: 4,
  text: 5,
  circle: 6,
  'svg-raw': 7,
}

const RawBlendMode: Record<string, number> = {
  normal: 3,
  screen: 14,
  overlay: 15,
  darken: 16,
  lighten: 17,
  'color-dodge': 18,
  'color-burn': 19,
  'hard-light': 20,
  'soft-light': 21,
  difference: 22,
  exclusion: 23,
  multiply: 24,
  hue: 25,
  saturation: 26,
  color: 27,
  luminosity: 28,
}

const RawConstraintH: Record<string, number> = {
  left: 0,
  right: 1,
  leftright: 2,
  center: 3,
  scale: 4,
}

const RawConstraintV: Record<string, number> = {
  top: 0,
  bottom: 1,
  topbottom: 2,
  center: 3,
  scale: 4,
}

const RawBoolType: Record<string, number> = {
  union: 0,
  difference: 1,
  intersection: 2,
  exclusion: 3,
}

const RawStrokeStyle: Record<string, number> = {
  solid: 0,
  dotted: 1,
  dashed: 2,
  mixed: 3,
}

const RawStrokeCap: Record<string, number> = {
  none: 0,
  'line-arrow': 1,
  'triangle-arrow': 2,
  'square-marker': 3,
  'circle-marker': 4,
  'diamond-marker': 5,
  round: 6,
  square: 7,
}

const RawShadowStyle: Record<string, number> = {
  'drop-shadow': 0,
  'inner-shadow': 1,
}

const RawBlurType: Record<string, number> = {
  'layer-blur': 0,
  'background-blur': 1,
}

const RawVerticalAlign: Record<string, number> = {
  top: 0,
  center: 1,
  bottom: 2,
}

const RawGrowType: Record<string, number> = {
  fixed: 0,
  'auto-width': 1,
  'auto-height': 2,
}

const RawFillRule: Record<string, number> = {
  nonzero: 0,
  evenodd: 1,
}

const RawStrokeLineCap: Record<string, number> = {
  butt: 0,
  round: 1,
  square: 2,
}

const RawStrokeLineJoin: Record<string, number> = {
  miter: 0,
  round: 1,
  bevel: 2,
}

const RawFlexDirection: Record<string, number> = {
  row: 0,
  'row-reverse': 1,
  column: 2,
  'column-reverse': 3,
}

const RawGridDirection: Record<string, number> = {
  row: 0,
  column: 1,
}

const RawAlignItems: Record<string, number> = {
  start: 0,
  end: 1,
  center: 2,
  stretch: 3,
}

const RawAlignContent: Record<string, number> = {
  start: 0,
  end: 1,
  center: 2,
  'space-between': 3,
  'space-around': 4,
  'space-evenly': 5,
  stretch: 6,
}

const RawJustifyItems: Record<string, number> = {
  start: 0,
  end: 1,
  center: 2,
  stretch: 3,
}

const RawJustifyContent: Record<string, number> = {
  start: 0,
  end: 1,
  center: 2,
  'space-between': 3,
  'space-around': 4,
  'space-evenly': 5,
  stretch: 6,
}

const RawWrapType: Record<string, number> = {
  wrap: 0,
  nowrap: 1,
}

const RawGridTrackType: Record<string, number> = {
  percent: 0,
  flex: 1,
  auto: 2,
  fixed: 3,
}

const RawSizing: Record<string, number> = {
  fill: 0,
  fix: 1,
  auto: 2,
}

const RawAlignSelf: Record<string, number> = {
  none: 0,
  auto: 1,
  start: 2,
  end: 3,
  center: 4,
  stretch: 5,
}

const RawJustifySelf: Record<string, number> = {
  none: 0,
  auto: 1,
  start: 2,
  end: 3,
  center: 4,
  stretch: 5,
}

/**
 * Translates shape type to WASM enum
 */
export function translateShapeType(type: ShapeType): number {
  return RawShapeType[type] ?? RawShapeType.rect
}

/**
 * Translates blend mode to WASM enum
 */
export function translateBlendMode(mode: BlendMode | undefined): number {
  if (!mode) return RawBlendMode.normal
  return RawBlendMode[mode] ?? RawBlendMode.normal
}

/**
 * Translates horizontal constraint to WASM enum
 */
export function translateConstraintH(constraint: ConstraintH | undefined): number {
  if (!constraint) return 5 // None
  return RawConstraintH[constraint] ?? 5
}

/**
 * Translates vertical constraint to WASM enum
 */
export function translateConstraintV(constraint: ConstraintV | undefined): number {
  if (!constraint) return 5 // None
  return RawConstraintV[constraint] ?? 5
}

/**
 * Translates bool type to WASM enum
 */
export function translateBoolType(boolType: BoolType | undefined): number {
  if (!boolType) return RawBoolType.union
  return RawBoolType[boolType] ?? RawBoolType.union
}

/**
 * Translates stroke style to WASM enum
 */
export function translateStrokeStyle(style: string | undefined): number {
  if (!style) return RawStrokeStyle.solid
  return RawStrokeStyle[style] ?? RawStrokeStyle.solid
}

/**
 * Translates stroke cap to WASM enum
 */
export function translateStrokeCap(cap: string | undefined): number {
  if (!cap) return RawStrokeCap.none
  return RawStrokeCap[cap] ?? RawStrokeCap.none
}

/**
 * Translates shadow style to WASM enum
 */
export function translateShadowStyle(style: string | undefined): number {
  if (!style) return RawShadowStyle['drop-shadow']
  return RawShadowStyle[style] ?? RawShadowStyle['drop-shadow']
}

/**
 * Translates blur type to WASM enum
 */
export function translateBlurType(type: string | undefined): number {
  if (!type) return RawBlurType['layer-blur']
  return RawBlurType[type] ?? RawBlurType['layer-blur']
}

/**
 * Translates vertical align to WASM enum
 */
export function translateVerticalAlign(align: string | undefined): number {
  if (!align) return RawVerticalAlign.top
  return RawVerticalAlign[align] ?? RawVerticalAlign.top
}

/**
 * Translates grow type to WASM enum
 */
export function translateGrowType(growType: string | undefined): number {
  if (!growType) return RawGrowType.fixed
  // Map 'auto' to 'auto-width' for compatibility
  if (growType === 'auto') return RawGrowType['auto-width']
  return RawGrowType[growType] ?? RawGrowType.fixed
}

/**
 * Translates fill rule to WASM enum
 */
export function translateFillRule(fillRule: string | undefined): number {
  if (!fillRule) return RawFillRule.nonzero
  return RawFillRule[fillRule] ?? RawFillRule.nonzero
}

/**
 * Translates stroke linecap to WASM enum
 */
export function translateStrokeLinecap(strokeLinecap: string | undefined): number {
  if (!strokeLinecap) return RawStrokeLineCap.butt
  return RawStrokeLineCap[strokeLinecap] ?? RawStrokeLineCap.butt
}

/**
 * Translates stroke linejoin to WASM enum
 */
export function translateStrokeLinejoin(strokeLinejoin: string | undefined): number {
  if (!strokeLinejoin) return RawStrokeLineJoin.miter
  return RawStrokeLineJoin[strokeLinejoin] ?? RawStrokeLineJoin.miter
}

// Text attribute enums
const RawTextAlign: Record<string, number> = {
  left: 0,
  center: 1,
  right: 2,
  justify: 3,
}

const RawTextDirection: Record<string, number> = {
  ltr: 0,
  rtl: 1,
  auto: 2,
}

const RawFontStyle: Record<string, number> = {
  normal: 0,
  italic: 1,
}

const RawTextDecoration: Record<string, number> = {
  none: 0,
  underline: 1,
  'line-through': 2,
}

const RawTextTransform: Record<string, number> = {
  none: 0,
  uppercase: 1,
  lowercase: 2,
  capitalize: 3,
}

/**
 * Translates text-align to WASM enum
 */
export function translateTextAlign(align: string | undefined): number {
  if (!align) return RawTextAlign.left
  return RawTextAlign[align] ?? RawTextAlign.left
}

/**
 * Translates text-direction to WASM enum
 */
export function translateTextDirection(dir: string | undefined): number {
  if (!dir) return RawTextDirection.ltr
  return RawTextDirection[dir] ?? RawTextDirection.ltr
}

/**
 * Translates font-style to WASM enum
 */
export function translateFontStyle(style: string | undefined): number {
  if (!style) return RawFontStyle.normal
  return RawFontStyle[style] ?? RawFontStyle.normal
}

/**
 * Translates text-decoration to WASM enum
 */
export function translateTextDecoration(decoration: string | undefined): number {
  if (!decoration) return RawTextDecoration.none
  return RawTextDecoration[decoration] ?? RawTextDecoration.none
}

/**
 * Translates text-transform to WASM enum
 */
export function translateTextTransform(transform: string | undefined): number {
  if (!transform) return RawTextTransform.none
  return RawTextTransform[transform] ?? RawTextTransform.none
}

/**
 * Translates layout flex direction to WASM enum
 */
export function translateLayoutFlexDir(flexDir: string | undefined): number {
  if (!flexDir) return RawFlexDirection.row
  return RawFlexDirection[flexDir] ?? RawFlexDirection.row
}

/**
 * Translates layout grid direction to WASM enum
 */
export function translateLayoutGridDir(gridDir: string | undefined): number {
  if (!gridDir) return RawGridDirection.row
  return RawGridDirection[gridDir] ?? RawGridDirection.row
}

/**
 * Translates layout align items to WASM enum
 */
export function translateLayoutAlignItems(alignItems: string | undefined): number {
  if (!alignItems) return RawAlignItems.start
  return RawAlignItems[alignItems] ?? RawAlignItems.start
}

/**
 * Translates layout align content to WASM enum
 */
export function translateLayoutAlignContent(alignContent: string | undefined): number {
  if (!alignContent) return RawAlignContent.stretch
  return RawAlignContent[alignContent] ?? RawAlignContent.stretch
}

/**
 * Translates layout justify items to WASM enum
 */
export function translateLayoutJustifyItems(justifyItems: string | undefined): number {
  if (!justifyItems) return RawJustifyItems.start
  return RawJustifyItems[justifyItems] ?? RawJustifyItems.start
}

/**
 * Translates layout justify content to WASM enum
 */
export function translateLayoutJustifyContent(justifyContent: string | undefined): number {
  if (!justifyContent) return RawJustifyContent.stretch
  return RawJustifyContent[justifyContent] ?? RawJustifyContent.stretch
}

/**
 * Translates layout wrap type to WASM enum
 */
export function translateLayoutWrapType(wrapType: string | undefined): number {
  if (!wrapType) return RawWrapType.nowrap
  return RawWrapType[wrapType] ?? RawWrapType.nowrap
}

/**
 * Translates grid track type to WASM enum
 */
export function translateGridTrackType(type: string | undefined): number {
  if (!type) return RawGridTrackType.percent
  return RawGridTrackType[type] ?? RawGridTrackType.percent
}

/**
 * Translates layout sizing to WASM enum
 */
export function translateLayoutSizing(sizing: string | undefined): number {
  if (!sizing) return RawSizing.fix
  return RawSizing[sizing] ?? RawSizing.fix
}

/**
 * Translates align self to WASM enum
 */
export function translateAlignSelf(alignSelf: string | undefined): number {
  if (!alignSelf) return RawAlignSelf.none
  return RawAlignSelf[alignSelf] ?? RawAlignSelf.none
}

/**
 * Translates justify self to WASM enum
 */
export function translateJustifySelf(justifySelf: string | undefined): number {
  if (!justifySelf) return RawJustifySelf.none
  return RawJustifySelf[justifySelf] ?? RawJustifySelf.none
}

/**
 * Translates structure modifier type to WASM enum
 * TODO: Find/Create a Rust enum for this
 */
export function translateStructureModifierType(type: string | undefined): number {
  if (!type) return 0
  switch (type) {
    case 'remove-children':
      return 1
    case 'add-children':
      return 2
    case 'scale-content':
      return 3
    default:
      return 0
  }
}

/**
 * Translates browser type to WASM enum
 */
export function translateBrowser(browser: string | undefined): number {
  if (!browser) return 4 // Unknown
  switch (browser.toLowerCase()) {
    case 'firefox':
      return 0
    case 'chrome':
      return 1
    case 'safari':
      return 2
    case 'edge':
      return 3
    case 'unknown':
      return 4
    default:
      return 4
  }
}


