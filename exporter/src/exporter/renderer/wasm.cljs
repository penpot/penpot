;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer.wasm
  "Single-shot headless renders: builds the renderer task keys and
  delegates the lease to `exporter.wasm.scope`."
  (:require
   [app.common.logging :as l]
   [exporter.wasm.scope :as scope]))

(defn ^:async render
  "Same shape as `exporter.renderer.browser/render`: one export as a
  batch of one."
  [cfg params on-object check-cancelled]
  (l/info :hint "render" :type (:type params) :backend "wasm")
  (await (scope/run (assoc cfg
                           :exporter.renderer/on-object on-object
                           :exporter.renderer/check-cancelled check-cancelled)
                    (fn [render-fn] (render-fn params)))))
