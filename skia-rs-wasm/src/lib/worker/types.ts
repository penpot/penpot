/**
 * Re-export shared types and helpers from common.
 * Import exporter types from @penpot-exporter/types.
 */

export type {
  IndexedPage,
  QueryParams,
  SelectionIndex,
  WorkerState,
  WorkerMessage,
  SerializedMessage,
  Line,
} from '@skia-rs-wasm/common'

export {
  ZERO_UUID,
  makeSelrect,
  flattenPageToIndexed,
} from '@skia-rs-wasm/common'
