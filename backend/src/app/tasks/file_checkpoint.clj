;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-checkpoint
  "A maintenance task that is responsible of performing a checkpoint on a recently edited file.

  The checkpoint process right now consist on the following task:

  - Check all file-media-object references and create new if some of
    them are missing.

  "
  (:require
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.logic.shapes :as cls]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.storage :as sto]
   [app.tasks.file-gc :as fgc]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [integrant.core :as ig]))

(def ^:dynamic *stats*
  "A test specific dynamic var for collect statistics of different
  operations performed in this task"
  nil)

(def ^:private conj*
  (fnil conj #{}))

(def ^:private sql:get-missing-media-references
  "SELECT fmo.*
     FROM file_media_object AS fmo
    WHERE fmo.id = ANY(?::uuid[])
      AND file_id != ?")

(defn- fix-media-references
  [{:keys [::db/conn] :as cfg} {file-id :id :as file}]
  (let [used-refs
        (into #{} fgc/xf:collect-used-media (cons file nil))

        _ (println "----------------")
        _ (prn "AAAA" 0 file-id (:snapshot-id file))
        _ (prn "AAAA" 1 used-refs)

        missing-refs-index
        (reduce (fn [result {:keys [id] :as fmo}]
                  (assoc result id
                         (-> fmo
                             (assoc :id (uuid/next))
                             (assoc :file-id file-id)
                             (dissoc :created-at)
                             (dissoc :deleted-at))))
                {}
                (db/plan conn [sql:get-missing-media-references
                               (db/create-array conn "uuid" used-refs)
                               file-id]))

        _ (prn "AAAA" (map (fn [[k v]] [k "->" (:id v)]) missing-refs-index))


        lookup-index
        (fn [id]
          (if-let [mobj (get missing-refs-index id)]
            (do
              (l/trc :hint "lookup index" :id (str id) :result (str (get mobj :id)))
              (some-> *stats* (swap! update :lookups conj* id))
              (get mobj :id))

            id))

        file
        (update file :data cls/relink-shapes lookup-index)]

    (doseq [[old-id item] missing-refs-index]
      (some-> *stats* (swap! update :missing (fnil conj []) item))
      (l/trc :hint "create missing references" :snap-id (str (:snapshot-id file)) :old-id (str old-id) :id (str (:id item)))
      (db/insert! conn :file-media-object item
                  {::db/return-keys false}))

    file))

(defn- process-file-snapshots!
  [{:keys [::fgc/file-id ::db/conn ::sto/storage] :as cfg}]
  (run! (fn [{:keys [snapshot-id] :as file}]
          (let [file (fgc/decode-file cfg file)
                file (fix-media-references cfg file)
                file (cfv/validate-file-schema! file)
                data (blob/encode (:data file))]

            (some-> *stats* (swap! update :snapshots conj* snapshot-id))

            ;; If file was already offloaded, we touch the underlying storage
            ;; object for properly trigger storage-gc-touched task
            (when (feat.fdata/offloaded? file)
              (some->> (:data-ref-id file) (sto/touch-object! storage)))

            (let [result (db/update! conn :file-change
                                     {:data data
                                      :data-backend nil
                                      :data-ref-id nil}
                                     {:id snapshot-id}
                                     {::db/return-keys false})]
              (prn "AAAA" result))))

        (db/plan cfg [fgc/sql:get-snapshots file-id] fgc/plan-opts)))

(defn- process-file!
  [cfg]
  (if-let [file (fgc/get-file cfg)]
    (do
      #_(->> file
           (fgc/decode-file cfg)
           (fix-media-references cfg)
           (cfv/validate-file-schema!)
           (fgc/persist-file! cfg))

      (process-file-snapshots! cfg)

      true)

    (do
      (l/dbg :hint "skip" :file-id (str (::fgc/file-id cfg)))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [cfg (-> cfg
                  (assoc ::db/rollback (:rollback? props))
                  (assoc ::fgc/file-id (:file-id props))
                  (assoc ::fgc/min-age (db/interval 0)))]

      (try
        (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                          (let [cfg (update cfg ::sto/storage sto/configure conn)]
                            (process-file! cfg))))

        (catch Throwable cause
          (l/err :hint "error on cleaning file"
                 :file-id (str (:file-id props))
                 :cause cause))))))
