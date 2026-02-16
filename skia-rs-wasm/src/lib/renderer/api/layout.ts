/**
 * Layout functions (flex, grid, and layout data)
 */

import type { WasmModule } from '../wasm-types'
import type { PenpotNode, GridCell } from '../types'
import {
  allocBytes,
  freeBytes,
  writeUUIDToHeap,
  offset8To32,
  getAllocSize,
} from '../utils'
import {
  translateLayoutFlexDir,
  translateLayoutGridDir,
  translateLayoutAlignItems,
  translateLayoutAlignContent,
  translateLayoutJustifyItems,
  translateLayoutJustifyContent,
  translateLayoutWrapType,
  translateGridTrackType,
  translateLayoutSizing,
  translateAlignSelf,
  translateJustifySelf,
} from './serializers'
import { checkContext } from './context'
import { UUID_U8_SIZE, ZERO_UUID } from './constants'

/**
 * Set flex layout
 */
export function setFlexLayout(module: WasmModule, shape: any): void {
  checkContext(module)
  const dir = translateLayoutFlexDir(shape.layoutFlexDir || 'row')
  const gap = shape.layoutGap || {}
  const rowGap = gap.rowGap ?? 0
  const columnGap = gap.columnGap ?? 0

  const alignItems = translateLayoutAlignItems(shape.layoutAlignItems)
  const alignContent = translateLayoutAlignContent(shape.layoutAlignContent)
  const justifyItems = translateLayoutJustifyItems(shape.layoutJustifyItems)
  const justifyContent = translateLayoutJustifyContent(shape.layoutJustifyContent)
  const wrapType = translateLayoutWrapType(shape.layoutWrapType)

  const padding = shape.layoutPadding || {}
  const paddingTop = padding.p1 ?? 0
  const paddingRight = padding.p2 ?? 0
  const paddingBottom = padding.p3 ?? 0
  const paddingLeft = padding.p4 ?? 0

  module._set_flex_layout_data(
    dir,
    rowGap,
    columnGap,
    alignItems,
    alignContent,
    justifyItems,
    justifyContent,
    wrapType,
    paddingTop,
    paddingRight,
    paddingBottom,
    paddingLeft
  )
}

/**
 * Set grid layout data
 */
export function setGridLayoutData(module: WasmModule, shape: any): void {
  checkContext(module)
  const dir = translateLayoutGridDir(shape.layoutGridDir || 'row')
  const gap = shape.layoutGap || {}
  const rowGap = gap.rowGap ?? 0
  const columnGap = gap.columnGap ?? 0

  const alignItems = translateLayoutAlignItems(shape.layoutAlignItems)
  const alignContent = translateLayoutAlignContent(shape.layoutAlignContent)
  const justifyItems = translateLayoutJustifyItems(shape.layoutJustifyItems)
  const justifyContent = translateLayoutJustifyContent(shape.layoutJustifyContent)

  const padding = shape.layoutPadding || {}
  const paddingTop = padding.p1 ?? 0
  const paddingRight = padding.p2 ?? 0
  const paddingBottom = padding.p3 ?? 0
  const paddingLeft = padding.p4 ?? 0

  module._set_grid_layout_data(
    dir,
    rowGap,
    columnGap,
    alignItems,
    alignContent,
    justifyItems,
    justifyContent,
    paddingTop,
    paddingRight,
    paddingBottom,
    paddingLeft
  )
}

/**
 * Set grid layout rows
 */
export function setGridLayoutRows(module: WasmModule, entries: Array<{ type: string; value: number }>): void {
  checkContext(module)
  const GRID_LAYOUT_ROW_U8_SIZE = 8
  const size = getAllocSize(entries.length, GRID_LAYOUT_ROW_U8_SIZE)
  const offset = allocBytes(module, size)
  const heapU8 = module.HEAPU8
  const heapF32 = module.HEAPF32

  let currentOffset = offset
  for (const entry of entries) {
    heapU8[currentOffset] = translateGridTrackType(entry.type)
    // Padding (3 bytes)
    currentOffset += 4
    heapF32[currentOffset / 4] = entry.value
    currentOffset += 4
  }

  module._set_grid_rows()
  freeBytes(module)
}

/**
 * Set grid layout columns
 */
export function setGridLayoutColumns(module: WasmModule, entries: Array<{ type: string; value: number }>): void {
  checkContext(module)
  const GRID_LAYOUT_COLUMN_U8_SIZE = 8
  const size = getAllocSize(entries.length, GRID_LAYOUT_COLUMN_U8_SIZE)
  const offset = allocBytes(module, size)
  const heapU8 = module.HEAPU8
  const heapF32 = module.HEAPF32

  let currentOffset = offset
  for (const entry of entries) {
    heapU8[currentOffset] = translateGridTrackType(entry.type)
    // Padding (3 bytes)
    currentOffset += 4
    heapF32[currentOffset / 4] = entry.value
    currentOffset += 4
  }

  module._set_grid_columns()
  freeBytes(module)
}

/**
 * Set grid layout cells (exporter GridCell[] with camelCase)
 */
export function setGridLayoutCells(
  module: WasmModule,
  cells: GridCell[]
): void {
  checkContext(module)
  const GRID_LAYOUT_CELL_U8_SIZE = 36
  const size = getAllocSize(cells.length, GRID_LAYOUT_CELL_U8_SIZE)
  const offset = allocBytes(module, size)
  const heapU8 = module.HEAPU8
  const heapI32 = module.HEAP32
  const heapU32 = module.HEAPU32

  let currentOffset = offset
  for (const cell of cells) {
    const offset32 = offset8To32(currentOffset)
    heapI32[offset32] = cell.row
    heapI32[offset32 + 1] = cell.rowSpan
    heapI32[offset32 + 2] = cell.column
    heapI32[offset32 + 3] = cell.columnSpan

    heapU8[currentOffset + 16] = translateAlignSelf(cell.alignSelf)
    heapU8[currentOffset + 17] = translateJustifySelf(cell.justifySelf)
    // Padding (2 bytes)
    currentOffset += 20

    const shapeId = cell.shapes?.[0] || ZERO_UUID
    const offset32After = offset8To32(currentOffset)
    writeUUIDToHeap(offset32After, heapU32, shapeId)
    currentOffset += UUID_U8_SIZE
  }

  module._set_grid_cells()
  freeBytes(module)
}

/**
 * Set grid layout (complete)
 */
export function setGridLayout(module: WasmModule, shape: any): void {
  checkContext(module)
  setGridLayoutData(module, shape)
  if (shape.layoutGridRows?.length) {
    setGridLayoutRows(module, shape.layoutGridRows)
  }
  if (shape.layoutGridColumns?.length) {
    setGridLayoutColumns(module, shape.layoutGridColumns)
  }
  if (shape.layoutGridCells && typeof shape.layoutGridCells === 'object') {
    setGridLayoutCells(module, Object.values(shape.layoutGridCells))
  }
}

/**
 * Set layout data
 */
export function setLayoutData(module: WasmModule, shape: any): void {
  checkContext(module)
  const margins = shape.layoutItemMargin || {}
  const marginTop = margins.m1 ?? 0
  const marginRight = margins.m2 ?? 0
  const marginBottom = margins.m3 ?? 0
  const marginLeft = margins.m4 ?? 0

  const hSizing = translateLayoutSizing(shape['layoutItemH-Sizing'])
  const vSizing = translateLayoutSizing(shape['layoutItemV-Sizing'])
  const alignSelf = translateAlignSelf(shape.layoutItemAlignSelf)

  const maxH = shape.layoutItemMaxH
  const hasMaxH = maxH != null ? 1 : 0
  const minH = shape.layoutItemMinH
  const hasMinH = minH != null ? 1 : 0
  const maxW = shape.layoutItemMaxW
  const hasMaxW = maxW != null ? 1 : 0
  const minW = shape.layoutItemMinW
  const hasMinW = minW != null ? 1 : 0

  const isAbsolute = shape.layoutItemAbsolute ? 1 : 0
  const zIndex = shape.layoutItemZIndex ?? 0

  module._set_layout_data(
    marginTop,
    marginRight,
    marginBottom,
    marginLeft,
    hSizing,
    vSizing,
    hasMaxH,
    maxH || 0,
    hasMinH,
    minH || 0,
    hasMaxW,
    maxW || 0,
    hasMinW,
    minW || 0,
    alignSelf,
    isAbsolute,
    zIndex
  )
}

/**
 * Clear layout
 */
export function clearLayout(module: WasmModule): void {
  checkContext(module)
  module._clear_shape_layout()
}

/**
 * Checks if shape has any layout properties
 */
export function hasAnyLayoutProp(shape: PenpotNode): boolean {
  const layoutKeys = Object.keys(shape).filter(
    (key) => typeof key === 'string' && key.startsWith('layout')
  )
  return layoutKeys.length > 0
}

