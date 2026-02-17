/**
 * Shape management functions
 */

import type { WasmModule } from '../wasm-types'
import type {
  BoolType,
  ShapeType,
} from '../types'
import {
  uuidToU32Tuple,
  allocBytes,
  freeBytes,
  writeUUIDToHeap,
  offset8To32,
  getAllocSize,
} from '../utils'
import {
  translateShapeType,
  translateBlendMode,
  translateConstraintH,
  translateConstraintV,
  translateBoolType,
  translateBlurType,
  translateVerticalAlign,
  translateGrowType,
} from './serializers'
import { checkContext } from './context'
import { UUID_U8_SIZE } from './constants'
import type { BlendMode, Blur, ConstraintH, ConstraintV, Matrix, Selrect } from '@penpot-exporter/types'

/**
 * Set active shape
 */
export function moduleUseShape(module: WasmModule, id: string): void {
  checkContext(module)
  const [a, b, c, d] = uuidToU32Tuple(id)
  module._use_shape(a, b, c, d)
}

/**
 * Set parent ID
 */
export function setParentId(module: WasmModule, id: string | null | undefined): void {
  checkContext(module)
  // Handle null/undefined by using zero UUID (all zeros)
  const [a, b, c, d] = uuidToU32Tuple(id || null)
  module._set_parent(a, b, c, d)
}

/**
 * Set shape type
 */
export function setShapeType(module: WasmModule, type: ShapeType): void {
  checkContext(module)
  module._set_shape_type(translateShapeType(type))
}

/**
 * Set shape clip content
 */
export function setShapeClipContent(module: WasmModule, clipContent: boolean): void {
  checkContext(module)
  module._set_shape_clip_content(clipContent ? 1 : 0)
}

/**
 * Set masked group
 */
export function setMasked(module: WasmModule, masked: boolean): void {
  checkContext(module)
  module._set_shape_masked_group(masked ? 1 : 0)
}

/**
 * Set shape selection rectangle
 */
export function setShapeSelrect(module: WasmModule, selrect: Selrect): void {
  checkContext(module)
  module._set_shape_selrect(selrect.x1, selrect.y1, selrect.x2, selrect.y2)
}

/**
 * Set shape transform matrix
 */
export function setShapeTransform(module: WasmModule, transform: Matrix | undefined): void {
  checkContext(module)
  if (transform) {
    module._set_shape_transform(transform.a, transform.b, transform.c, transform.d, transform.e, transform.f)
  }
}

/**
 * Set shape rotation
 */
export function setShapeRotation(module: WasmModule, rotation: number | undefined): void {
  checkContext(module)
  if (rotation !== undefined) {
    module._set_shape_rotation(rotation)
  }
}

/**
 * Set shape opacity
 */
export function setShapeOpacity(module: WasmModule, opacity: number | undefined): void {
  checkContext(module)
  module._set_shape_opacity(opacity ?? 1)
}

/**
 * Set shape hidden state
 */
export function setShapeHidden(module: WasmModule, hidden: boolean): void {
  checkContext(module)
  module._set_shape_hidden(hidden ? 1 : 0)
}

/**
 * Set shape blend mode
 */
export function setShapeBlendMode(module: WasmModule, blendMode: BlendMode | undefined): void {
  checkContext(module)
  if (blendMode) {
    module._set_shape_blend_mode(translateBlendMode(blendMode))
  }
}

/**
 * Set shape vertical align
 */
export function setShapeVerticalAlign(module: WasmModule, verticalAlign: string | undefined): void {
  checkContext(module)
  module._set_shape_vertical_align(translateVerticalAlign(verticalAlign))
}

/**
 * Set shape children
 */
export function setShapeChildren(module: WasmModule, children: string[]): void {
  checkContext(module)
  const filtered = children.filter((id) => id != null)
  const count = filtered.length

  if (count === 0) {
    module._set_children_0()
  } else if (count === 1) {
    const [a, b, c, d] = uuidToU32Tuple(filtered[0])
    module._set_children_1(a, b, c, d)
  } else if (count === 2) {
    const [a1, b1, c1, d1] = uuidToU32Tuple(filtered[0])
    const [a2, b2, c2, d2] = uuidToU32Tuple(filtered[1])
    module._set_children_2(a1, b1, c1, d1, a2, b2, c2, d2)
  } else if (count === 3) {
    const [a1, b1, c1, d1] = uuidToU32Tuple(filtered[0])
    const [a2, b2, c2, d2] = uuidToU32Tuple(filtered[1])
    const [a3, b3, c3, d3] = uuidToU32Tuple(filtered[2])
    module._set_children_3(a1, b1, c1, d1, a2, b2, c2, d2, a3, b3, c3, d3)
  } else if (count === 4) {
    const [a1, b1, c1, d1] = uuidToU32Tuple(filtered[0])
    const [a2, b2, c2, d2] = uuidToU32Tuple(filtered[1])
    const [a3, b3, c3, d3] = uuidToU32Tuple(filtered[2])
    const [a4, b4, c4, d4] = uuidToU32Tuple(filtered[3])
    module._set_children_4(a1, b1, c1, d1, a2, b2, c2, d2, a3, b3, c3, d3, a4, b4, c4, d4)
  } else if (count === 5) {
    const [a1, b1, c1, d1] = uuidToU32Tuple(filtered[0])
    const [a2, b2, c2, d2] = uuidToU32Tuple(filtered[1])
    const [a3, b3, c3, d3] = uuidToU32Tuple(filtered[2])
    const [a4, b4, c4, d4] = uuidToU32Tuple(filtered[3])
    const [a5, b5, c5, d5] = uuidToU32Tuple(filtered[4])
    module._set_children_5(a1, b1, c1, d1, a2, b2, c2, d2, a3, b3, c3, d3, a4, b4, c4, d4, a5, b5, c5, d5)
  } else {
    // Dynamic allocation for more than 5 children
    const size = getAllocSize(filtered.length, UUID_U8_SIZE)
    const offset = offset8To32(allocBytes(module, size))
    const heap = module.HEAPU32

    let currentOffset = offset
    for (const id of filtered) {
      currentOffset = writeUUIDToHeap(currentOffset, heap, id)
    }

    module._set_children()
    freeBytes(module)
  }
}

/**
 * Set shape corners (border radius)
 */
export function setShapeCorners(module: WasmModule, corners: [number?, number?, number?, number?]): void {
  checkContext(module)
  const [r1 = 0, r2 = 0, r3 = 0, r4 = 0] = corners
  module._set_shape_corners(r1, r2, r3, r4)
}

/**
 * Set shape blur
 */
export function setShapeBlur(module: WasmModule, blur: Blur | null | undefined): void {
  checkContext(module)
  if (blur) {
    module._set_shape_blur(translateBlurType(blur.type), blur.hidden ? 1 : 0, blur.value)
  } else {
    module._clear_shape_blur()
  }
}

/**
 * Set shape bool type
 */
export function setShapeBoolType(module: WasmModule, boolType: BoolType | undefined): void {
  checkContext(module)
  if (boolType) {
    module._set_shape_bool_type(translateBoolType(boolType))
  }
}

/**
 * Set shape grow type
 */
export function setShapeGrowType(module: WasmModule, growType: string | undefined): void {
  checkContext(module)
  module._set_shape_grow_type(translateGrowType(growType))
}

/**
 * Set shape constraints
 */
export function setShapeConstraints(
  module: WasmModule,
  constraintH: ConstraintH | undefined,
  constraintV: ConstraintV | undefined
): void {
  checkContext(module)
  module._clear_shape_constraints()
  if (constraintH) {
    module._set_shape_constraint_h(translateConstraintH(constraintH))
  }
  if (constraintV) {
    module._set_shape_constraint_v(translateConstraintV(constraintV))
  }
}

