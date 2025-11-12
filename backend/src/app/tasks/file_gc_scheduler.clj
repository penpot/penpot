;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-gc-scheduler
  "A maintenance task that is responsible of properly scheduling the
  file-gc task for all files that matches the eligibility threshold."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def ^:private
  sql:get-candidates
  "SELECT f.id,
          f.revn,
          f.modified_at
     FROM file AS f
    WHERE f.has_media_trimmed IS false
      AND f.modified_at < ?
      AND f.deleted_at IS NULL
    ORDER BY f.modified_at DESC
      FOR UPDATE OF f
     SKIP LOCKED")

(defn- schedule!
  [{:keys [::db/conn] :as cfg} threshold]
  (let [total (reduce (fn [total {:keys [id modified-at revn]}]
                        (let [params {:file-id id :revn revn}]
                          (l/trc :hint "schedule"
                                 :file-id (str id)
                                 :revn revn
                                 :modified-at (ct/format-inst modified-at))
                          (wrk/submit! (assoc cfg ::wrk/params params))
                          (inc total)))
                      0
                      (db/plan conn [sql:get-candidates threshold] {:fetch-size 10}))]
    {:processed total}))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (assoc v ::min-age (cf/get-file-clean-delay))})

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [threshold (-> (ct/duration (or (:min-age props) (::min-age cfg)))
                        (ct/in-past))]
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (assoc ::wrk/task :file-gc)
          (assoc ::wrk/priority 10)
          (assoc ::wrk/mark-retries 0)
          (assoc ::wrk/delay 10000)
          (db/tx-run! schedule! threshold)))))
