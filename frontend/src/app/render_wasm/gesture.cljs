;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.gesture
  "WASM-linked pointer gestures (interactive transforms, like D&D)")

(defonce ^:private interactive-transform-active? (atom false))

(defn reset-after-wasm-reload!
  "Call after `_clean_up` + `_init` (new GL context). WASM interactive_transform /
  fast_mode are reset to defaults; this atom must match or compare-and-set helpers in
  modifiers.cljs will skip `_set_modifiers_start` / `_set_modifiers_end` incorrectly."
  []
  (reset! interactive-transform-active? false))

(defn try-begin-interactive-transform!
  "Returns true iff we transitioned inactive → active and native `_set_modifiers_start`
  must run."
  []
  (compare-and-set! interactive-transform-active? false true))

(defn try-end-interactive-transform!
  "Returns true iff we transitioned active → inactive and native `_set_modifiers_end`
  must run."
  []
  (compare-and-set! interactive-transform-active? true false))
