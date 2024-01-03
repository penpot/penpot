;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.svgo
  "A SVG Optimizer service"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.jsrt :as jsrt]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as bh]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(def ^:dynamic *semaphore*
  "A dynamic variable that can optionally contain a traffic light to
  appropriately delimit the use of resources, managed externally."
  nil)

(defn optimize
  [system data]
  (dm/assert! "expect data to be a string" (string? data))

  (letfn [(optimize-fn [pool]
            (jsrt/run! pool
                       (fn [context]
                         (jsrt/set! context "svgData" data)
                         (jsrt/eval! context "penpotSvgo.optimize(svgData, {plugins: ['safeAndFastPreset']})"))))]
    (try
      (some-> *semaphore* ps/acquire!)
      (let [{:keys [::jsrt/pool ::wrk/executor]} (::optimizer system)]
        (dm/assert! "expect optimizer instance" (jsrt/pool? pool))
        (px/invoke! executor (partial optimize-fn pool)))
      (finally
        (some-> *semaphore* ps/release!)))))

(s/def ::max-procs (s/nilable ::us/integer))

(defmethod ig/pre-init-spec ::optimizer [_]
  (s/keys :req [::wrk/executor ::max-procs]))

(defmethod ig/prep-key ::optimizer
  [_ cfg]
  (merge {::max-procs 20} (d/without-nils cfg)))

(defmethod ig/init-key ::optimizer
  [_ {:keys [::wrk/executor ::max-procs]}]
  (l/inf :hint "initializing svg optimizer pool" :max-procs max-procs)
  (let [init     (jsrt/resource->source "app/common/svg/optimizer.js")
        executor (bh/create :type :executor :executor executor :permits max-procs)]
    {::jsrt/pool (jsrt/pool :init init)
     ::wrk/executor executor}))

(defmethod ig/halt-key! ::optimizer
  [_ {:keys [::jsrt/pool]}]
  (l/info :hint "stopping svg optimizer pool")
  (pu/close! pool))
