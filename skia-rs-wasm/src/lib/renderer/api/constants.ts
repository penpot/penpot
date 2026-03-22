/**
 * Constants for the WASM Render API.
 * ZERO_UUID from renderer types; fill type guards from verification for api convenience.
 */

export { ZERO_UUID } from '../types'
export {
  isColorFill,
  isLinearGradient,
  isRadialGradient,
  isAngularGradient,
  isImageFill,
} from '../verification'

// Constants
export const UUID_U8_SIZE = 16
export const UUID_U32_SIZE = UUID_U8_SIZE / 4
export const MODIFIER_U8_SIZE = 40
export const MODIFIER_U32_SIZE = MODIFIER_U8_SIZE / 4
/** Entry size for propagate_modifiers input (Rust RawTransformEntry: uuid 16 + matrix 24 + kind 1 + padding 3 = 44) */
export const PROPAGATE_MODIFIER_ENTRY_U8_SIZE = 44
export const MAX_BUFFER_CHUNK_SIZE = 256 * 1024
export const DEBOUNCE_DELAY_MS = 100
export const THROTTLE_DELAY_MS = 10
export const POSITION_DATA_U8_SIZE = 36
export const POSITION_DATA_U32_SIZE = POSITION_DATA_U8_SIZE / 4

// Fill-related constants
export const FILL_U8_SIZE = 160 // Max size for any fill type
export const GRADIENT_STOP_U8_SIZE = 8
export const MAX_GRADIENT_STOPS = 16

// Text-related constants
export const PARAGRAPH_ATTR_U8_SIZE = 12
export const SPAN_ATTR_U8_SIZE = 64
export const MAX_TEXT_FILLS = 8 // Must match Rust MAX_TEXT_FILLS = 8 in render-wasm/src/wasm/text.rs
