;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-xlog-gc
  "A maintenance task that performs a garbage collection of the file
  change (transaction) log."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.storage :as sto]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(def ^:private
  sql:delete-files-xlog
  "DELETE FROM file_change
    WHERE id IN (SELECT id FROM file_change
                  WHERE label IS NULL
                    AND created_at < ?
                  ORDER BY created_at LIMIT ?)
   RETURNING id, data_backend, data_ref_id")

(def xf:filter-offloded
  (comp
   (filter feat.fdata/offloaded?)
   (keep :data-ref-id)))

(defn- delete-in-chunks
  [{:keys [::chunk-size ::threshold] :as cfg}]
  (let [storage (sto/resolve cfg ::db/reuse-conn true)]
    (loop [total 0]
      (let [chunk  (db/exec! cfg [sql:delete-files-xlog threshold chunk-size])
            length (count chunk)]

        ;; touch all references on offloaded changes entries
        (doseq [data-ref-id (sequence xf:filter-offloded chunk)]
          (l/trc :hint "touching referenced storage object"
                 :storage-object-id (str data-ref-id))
          (sto/touch-object! storage data-ref-id))

        (if (pos? length)
          (recur (+ total length))
          total)))))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [min-age    (or (:min-age props)
                         (dt/duration "72h"))
          chunk-size (:chunk-size props 5000)
          threshold  (dt/minus (dt/now) min-age)]

      (-> cfg
          (assoc ::db/rollback (:rollback props false))
          (assoc ::threshold threshold)
          (assoc ::chunk-size chunk-size)
          (db/tx-run! (fn [cfg]
                        (let [total (delete-in-chunks cfg)]
                          (l/trc :hint "file xlog cleaned" :total total)
                          total)))))))
