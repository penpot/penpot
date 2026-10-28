;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.wasm
  "Shared render-wasm loader and serial work queue for the web worker.

  Thumbnails and local multi-export share one WASM module instance. Jobs that
  touch that module (initialize-viewport, render, clear-canvas) must run through
  `submit` so they never interleave."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [app.config :as cf]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

;; Delay that resolves to the worker's WasmModule instance.
(defonce module
  (delay
    (let [module  (unchecked-get js/globalThis "WasmModule")
          init-fn (unchecked-get module "default")
          href    (cf/resolve-href "js/render-wasm.wasm")]
      (->> (init-fn #js {:locateFile (constantly href)})
           (p/fnly (fn [module cause]
                     (if cause
                       (js/console.error "worker wasm module init failed" cause)
                       (set! wasm/internal-module module))))))))

(defonce ^:private work-queue
  (rx/subject))

(defonce ^:private cancelled-jobs*
  (atom #{}))

(defonce ^:private executor-sub*
  (atom nil))

(defn- ensure-executor!
  "Starts the serial drain once. Must be reachable from `submit` so release
  builds cannot DCE the subscription (a lone unused `defonce` is removed)."
  []
  (when (nil? @executor-sub*)
    (reset! executor-sub*
            (->> work-queue
                 (rx/mapcat
                  (fn [{:keys [bus work]}]
                    (->> (work)
                         (rx/tap (fn [value]
                                   (rx/push! bus value)))
                         (rx/catch (fn [cause]
                                     (rx/error! bus cause)
                                     (rx/empty)))
                         (rx/finalize
                          (fn []
                            (rx/end! bus))))))
                 (rx/subs!
                  (fn [_])
                  (fn [cause]
                    (js/console.error "worker wasm queue error" cause)))))))

(defn cancel-job
  "Marks `job-id` so an in-flight export stops between items."
  [job-id]
  (swap! cancelled-jobs* conj job-id)
  nil)

(defn cancelled?
  [job-id]
  (contains? @cancelled-jobs* job-id))

(defn clear-cancel
  [job-id]
  (swap! cancelled-jobs* disj job-id))

(defn submit
  "Runs `work` (zero-arg → observable) on the serial WASM queue.
  Returns a subject that mirrors `work` emissions and errors.

  Pushes onto the queue immediately (does not wait for a subscriber) so the
  job starts even if the caller is slow to subscribe."
  [work]
  (ensure-executor!)
  (let [bus (rx/subject)]
    (rx/push! work-queue {:bus bus :work work})
    bus))
