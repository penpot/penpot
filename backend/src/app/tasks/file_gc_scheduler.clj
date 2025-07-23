;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-gc-scheduler
  "A maintenance task that is responsible of properly scheduling the
  file-gc task for all files that matches the eligibility threshold."
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def ^:private
  sql:get-candidates
  "SELECT f.id,
          f.modified_at
     FROM file AS f
    WHERE f.has_media_trimmed IS false
      AND f.modified_at < now() - ?::interval
      AND f.deleted_at IS NULL
    ORDER BY f.modified_at DESC
      FOR UPDATE
     SKIP LOCKED")

(defn- get-candidates
  [{:keys [::db/conn ::min-age] :as cfg}]
  (let [min-age (db/interval min-age)]
    (db/cursor conn [sql:get-candidates min-age] {:chunk-size 10})))

(defn- schedule!
  [{:keys [::min-age] :as cfg}]
  (let [total (reduce (fn [total {:keys [id]}]
                        (let [params {:file-id id :min-age min-age}]
                          (wrk/submit! (assoc cfg ::wrk/params params))
                          (inc total)))
                      0
                      (get-candidates cfg))]

    {:processed total}))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (assoc v ::min-age (cf/get-deletion-delay))})

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [min-age (ct/duration (or (:min-age props) (::min-age cfg)))]
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (assoc ::min-age min-age)
          (assoc ::wrk/task :file-gc)
          (assoc ::wrk/priority 10)
          (assoc ::wrk/mark-retries 0)
          (assoc ::wrk/delay 1000)
          (db/tx-run! schedule!)))))
