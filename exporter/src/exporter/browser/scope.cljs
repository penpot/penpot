;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.browser.scope
  "One batch through browsers: every export gets its own checkout,
  all at once, bounded by the pool. No lease — a browser batch shares
  nothing but the pool, so this level owns fan-out only, the mirror of
  `exporter.wasm.scope` holding a lease instead. Task keys ride in
  `cfg` under `:exporter.renderer/*`, set by whoever built the batch."
  (:require
   [exporter.renderer.browser :as driver]))

(defn ^:async run
  "Renders every export through its own browser checkout. Rejects on
  the first failure; progress and cancel flow per export through the
  task's own callbacks."
  [cfg exports]
  (let [on-object       (:exporter.renderer/on-object cfg)
        check-cancelled (:exporter.renderer/check-cancelled cfg)]
    (await (js/Promise.all
            (mapv (fn [params]
                    (driver/render cfg params on-object check-cancelled))
                  exports)))))
