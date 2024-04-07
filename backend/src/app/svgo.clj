;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.svgo
  "A SVG Optimizer service"
  (:require
   [app.common.jsrt :as jsrt]
   [app.common.logging :as l]
   [app.worker :as-alias wrk]
   [integrant.core :as ig]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(def ^:dynamic *semaphore*
  "A dynamic variable that can optionally contain a traffic light to
  appropriately delimit the use of resources, managed externally."
  nil)

(defn optimize
  [{pool ::optimizer} data]
  (try
    (some-> *semaphore* ps/acquire!)
    (jsrt/run! pool
               (fn [context]
                 (jsrt/set! context "svgData" data)
                 (jsrt/eval! context "penpotSvgo.optimize(svgData, {plugins: ['safeAndFastPreset']})")))
    (finally
      (some-> *semaphore* ps/release!))))

(defmethod ig/init-key ::optimizer
  [_ _]
  (l/inf :hint "initializing svg optimizer pool")
  (let [init (jsrt/resource->source "app/common/svg/optimizer.js")]
    (jsrt/pool :init init)))

(defmethod ig/halt-key! ::optimizer
  [_ pool]
  (l/info :hint "stopping svg optimizer pool")
  (pu/close! pool))
