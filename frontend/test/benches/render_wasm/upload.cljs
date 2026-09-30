;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.upload
  "Benchmark scene upload.

  In order to emulate editor behaviour, `upload-scene!` derives
  parent-before-child order from a validated scene
  snapshot and uploads it with `serialize-shapes-batch`

  Validation, timing, lifecycle (`_begin/_end_loading`), host font/image/grid
  sequencing and text layouts stay in the callers (tickets 02/05).

  This uses renderer code, so don't pull this from Node case collection
  (`benches.render-wasm.cases`)"
  (:require
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [benches.render-wasm.scenes.common :as common]))

(defn prepare-scene
  "Derives the parent-before-child shape vector from a validated `snapshot`.
  Call it outside measured regions, then pass the result to
  `serialize-shapes-batch` inside."
  [snapshot]
  (common/upload-order snapshot))

(defn upload-scene!
  "Uploads validated `snapshot` with `opts`.

  - `snapshot` is `{:objects {uuid shape} :refs {label uuid}}` per the ticket02
    contract; it must already be validated (see
    `benches.render-wasm.scenes.common/validate!`).
  - `opts` carries `:include-layout?` and `:include-fills-strokes?` through to
    the batch writer.

  Returns the prepared shape vector."
  [snapshot opts]
  (serialize-shape/serialize-shapes-batch (prepare-scene snapshot) opts))
