;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.index-file-change-refs-scheduler
  (:require
   [app.common.schema :as sm]
   [app.db :as db]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [integrant.core :as ig]))

(defn execute
  "Scan uncovered changes and count newly queued jobs."
  [cfg {:keys [file-id]}]
  (db/tx-run!
   cfg
   (fn [cfg]
     (let [scheduled
           (reduce (fn [scheduled {change-id :id}]
                     (jobs/heartbeat cfg)
                     (if (jobs/submit cfg {::jobs/name :index-file-change-refs
                                           ::jobs/params {:file-id file-id :change-id change-id}
                                           ::jobs/label (str file-id "/" change-id)
                                           ::jobs/skip-if-active true})
                       (inc scheduled)
                       scheduled))
                   0
                   (refs/plan-missing-change-ids cfg file-id))]
       {:file-id file-id :scheduled scheduled}))))

(def ^:private schema:params
  [:map {:closed true}
   [:file-id ::sm/uuid]])

(def ^:private schema:result
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:scheduled [:int {:min 0}]]])

(def ^:private valid-config?
  (sm/validator [:map ::db/pool ::mtx/metrics]))

(defmethod ig/init-key ::job-def
  [_ cfg]
  (assert (valid-config? cfg) "expected a database pool and metrics")
  {::jobs/name          :index-file-change-refs-scheduler
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [_context params]
                          (execute cfg params))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)
   ::jobs/result-schema schema:result})
