;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.layout
  "Off-page layout on the shared worker WASM module.

  Loads a page's shapes into the worker scene, runs Rust
  `propagate_modifiers`, and returns the resulting transforms so the
  main thread can commit them into file data without touching the live
  workspace canvas."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.logging :as log]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.color :as cc]
   [app.render-wasm.api :as wasm.api]
   [app.worker.impl :as impl]
   [app.worker.wasm :as wwasm]
   [beicon.v2.core :as rx]))

(log/set-level! :info)

(def ^:private ^:const canvas-size 4)

(defn- coerce-geometry-entries
  "Transit may turn matrices into plain maps; WASM needs `Matrix`."
  [entries]
  (mapv (fn [[id data]]
          [id (update data :transform gmt/coerce)])
        entries))

(defn- run-propagate
  [{:keys [objects geometry-entries structure-modifiers snap-pixel?]}]
  ;; 1. `@wwasm/module` must resolve before init (same as exports/thumbnails).
  ;; 2. On failure, push `{:transforms {} :error ...}`, not `rx/error!`.
  ;;    Main-thread `ask!` only sees successful worker replies; errors go to
  ;;    the worker `on-error` handler and never complete the `ask!` stream,
  ;;    so the layout watch would wait forever.
  (->> (rx/from @wwasm/module)
       (rx/mapcat
        (fn [_]
          (rx/create
           (fn [subs]
             (let [done? (atom false)
                   finish!
                   (fn [payload]
                     (when (compare-and-set! done? false true)
                       (try (wasm.api/clear-canvas) (catch :default _))
                       (rx/push! subs payload)
                       (rx/end! subs)))

                   empty-result
                   (fn [hint data]
                     (log/warn :hint hint :data data)
                     (finish! {:transforms {}
                               :error hint
                               :data data}))

                   canvas (js/OffscreenCanvas. canvas-size canvas-size)
                   init?  (wasm.api/init-canvas-context canvas)]
               (cond
                 (not init?)
                 (empty-result "failed to init worker wasm canvas"
                               {:code :wasm-canvas-init-failed})

                 (not (wasm/live?))
                 (empty-result "worker wasm context is not live"
                               {:code :wasm-not-live})

                 (empty? objects)
                 (empty-result "no shapes to lay out"
                               {:code :empty-layout-objects})

                 :else
                 (try
                   (let [vbox {:x 0 :y 0 :width canvas-size :height canvas-size}
                         entries (coerce-geometry-entries geometry-entries)]
                     (reset! wasm/disable-request-render? true)
                     (wasm.api/initialize-viewport
                      objects 1 vbox
                      :background cc/canvas
                      :force-sync true
                      ;; `:on-render` runs after process-pending; `:on-shapes-ready`
                      ;; fires earlier and can see an incomplete scene.
                      :on-render
                      (fn []
                        (try
                          (wasm.api/clean-modifiers)
                          (when (seq structure-modifiers)
                            (wasm.api/set-structure-modifiers structure-modifiers))
                          (let [transforms
                                (into {}
                                      (wasm.api/propagate-modifiers
                                       entries
                                       (boolean snap-pixel?)
                                       nil))]
                            (finish! {:transforms transforms}))
                          (catch :default cause
                            (empty-result (ex-message cause)
                                          (ex-data cause)))))))
                   (catch :default cause
                     (empty-result (ex-message cause) (ex-data cause)))))
               (fn []
                 (try (wasm.api/clear-canvas) (catch :default _))))))))))

(defmethod impl/handler :layout/propagate-modifiers
  [message _]
  (wwasm/submit #(run-propagate message)))
