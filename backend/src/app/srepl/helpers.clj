;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.helpers
  "A  main namespace for server repl."
  (:refer-clojure :exclude [parse-uuid])
  #_:clj-kondo/ignore
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.files.repair :as repair]
   [app.common.files.validate :as cfv]
   [app.common.files.validate :as validate]
   [app.common.logging :as l]
   [app.common.pprint :refer [pprint]]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as feat.fdata]
   [app.main :as main]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as files-update]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.stacktrace :as strace]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(def ^:dynamic *system* nil)

(defn println!
  [& params]
  (locking println
    (apply println params)))

(defn parse-uuid
  [v]
  (if (uuid? v)
    v
    (d/parse-uuid v)))

(defn reset-file-data!
  "Hardcode replace of the data of one file."
  [id data]
  (db/tx-run! main/system
              (fn [system]
                (db/update! system :file
                            {:data data}
                            {:id id}))))

(defn- get-file*
  "Get the migrated data of one file."
  [system id]
  (db/run! system
           (fn [system]
             (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
               (-> (files/get-file system id :migrate? false)
                   (update :data feat.fdata/process-pointers deref)
                   (update :data feat.fdata/process-objects (partial into {}))
                   (fmg/migrate-file))))))

(defn get-file
  "Get the migrated data of one file."
  [id]
  (get-file* main/system id))

(defn validate
  "Validate structure, referencial integrity and semantic coherence of
  all contents of a file. Returns a list of errors."
  [id]
  (db/tx-run! main/system
              (fn [{:keys [::db/conn] :as system}]
                (let [id   (if (string? id) (parse-uuid id) id)
                      file (get-file* system id)
                      libs (->> (files/get-file-libraries conn id)
                                (into [file] (map (fn [{:keys [id]}]
                                                    (get-file* system id))))
                                (d/index-by :id))]
                  (validate/validate-file file libs)))))

(defn repair!
  "Repair the list of errors detected by validation."
  [id]
  (db/tx-run! main/system
              (fn [{:keys [::db/conn] :as system}]
                (let [id      (if (string? id) (parse-uuid id) id)
                      file    (get-file* system id)
                      libs    (->> (files/get-file-libraries conn id)
                                   (into [file] (map (fn [{:keys [id]}]
                                                       (get-file* system id))))
                                   (d/index-by :id))
                      errors  (validate/validate-file file libs)
                      changes (repair/repair-file file libs errors)

                      file    (-> file
                                  (update :revn inc)
                                  (update :data cpc/process-changes changes))

                      file (if (contains? (:features file) "fdata/objects-map")
                             (feat.fdata/enable-objects-map file)
                             file)

                      file (if (contains? (:features file) "fdata/pointer-map")
                             (binding [pmap/*tracked* (pmap/create-tracked)]
                               (let [file (feat.fdata/enable-pointer-map file)]
                                 (feat.fdata/persist-pointers! system id)
                                 file))
                             file)]

                  (db/update! conn :file
                              {:revn (:revn file)
                               :data (blob/encode (:data file))
                               :data-backend nil
                               :modified-at (dt/now)
                               :has-media-trimmed false}
                              {:id (:id file)})

                  :repaired))))


(defn update-file!
  "Apply a function to the data of one file. Optionally save the changes or not.
  The function receives the decoded and migrated file data."
  [& {:keys [update-fn id rollback? inc-revn?]
      :or {rollback? true inc-revn? true}}]
  (letfn [(process-file [{:keys [::db/conn] :as system} file-id]
            (let [file (get-file* system file-id)
                  file (cond-> (update-fn file)
                         inc-revn? (update :revn inc))

                  _    (cfv/validate-file-schema! file)

                  file (if (contains? (:features file) "fdata/objects-map")
                         (feat.fdata/enable-objects-map file)
                         file)

                  file (if (contains? (:features file) "fdata/pointer-map")
                         (binding [pmap/*tracked* (pmap/create-tracked)]
                           (let [file (feat.fdata/enable-pointer-map file)]
                             (feat.fdata/persist-pointers! system id)
                             file))
                         file)]

              (db/update! conn :file
                          {:data (blob/encode (:data file))
                           :features (db/create-array conn "text" (:features file))
                           :revn (:revn file)}
                          {:id (:id file)})

              (dissoc file :data)))]

    (db/tx-run! (or *system* (assoc main/system ::db/rollback rollback?))
                (fn [system]
                  (binding [*system* system]
                    (process-file system id))))))


(def ^:private sql:get-file-ids
  "SELECT id FROM file
    WHERE created_at < ? AND deleted_at is NULL
    ORDER BY created_at DESC")

(defn analyze-files
  "Apply a function to all files in the database, reading them in
  batches. Do not change data.

  The `on-file` parameter should be a function that receives the file
  and the previous state and returns the new state.

  Emits rollback at the end of operation."
  [& {:keys [max-items start-at on-file on-error on-end on-init with-libraries?]}]
  (letfn [(get-candidates [conn]
            (cond->> (db/cursor conn [sql:get-file-ids (or start-at (dt/now))])
              (some? max-items)
              (take max-items)))

          (on-error* [cause file]
            (println "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))

          (process-file [{:keys [::db/conn] :as system} file-id]
            (let [file (get-file* system file-id)
                  libs (when with-libraries?
                         (->> (files/get-file-libraries conn file-id)
                              (into [file] (map (fn [{:keys [id]}]
                                                  (get-file* system id))))
                              (d/index-by :id)))]
              (try
                (if with-libraries?
                  (on-file file libs)
                  (on-file file))
                (catch Throwable cause
                  ((or on-error on-error*) cause file)))))]

    (db/tx-run! (assoc main/system ::db/rollback true)
                (fn [{:keys [::db/conn] :as system}]
                  (try
                    (binding [*system* system]
                      (when (fn? on-init) (on-init))
                      (run! (partial process-file system)
                            (get-candidates conn)))
                    (finally
                      (when (fn? on-end)
                        (ex/ignoring (on-end)))))))))

(defn repair-file-media
  [{:keys [id data] :as file}]
  (let [conn  (db/get-connection *system*)
        used  (bfc/collect-used-media data)
        ids   (db/create-array conn "uuid" used)
        sql   (str "SELECT * FROM file_media_object WHERE id = ANY(?)")
        rows  (db/exec! conn [sql ids])
        index (reduce (fn [index media]
                        (if (not= (:file-id media) id)
                          (let [media-id (uuid/next)]
                            (l/wrn :hint "found not referenced media"
                                   :file-id (str id)
                                   :media-id (str (:id media)))

                            (db/insert! *system* :file-media-object
                                        (-> media
                                            (assoc :file-id id)
                                            (assoc :id media-id)))
                            (assoc index (:id media) media-id))
                          index))
                      {}
                      rows)]

    (when (seq index)
      (binding [bfc/*state* (atom {:index index})]
        (update file :data (fn [fdata]
                             (-> fdata
                                 (update :pages-index #'bfc/relink-shapes)
                                 (update :components #'bfc/relink-shapes)
                                 (update :media #'bfc/relink-media)
                                 (d/without-nils))))))))

(defn process-files!
  "Apply a function to all files in the database"
  [& {:keys [max-items
             max-jobs
             start-at
             on-file
             validate?
             rollback?]
      :or {max-jobs 1
           max-items Long/MAX_VALUE
           validate? true
           rollback? true}}]

  (l/dbg :hint "process:start"
         :rollback rollback?
         :max-jobs max-jobs
         :max-items max-items)

  (let [tpoint    (dt/tpoint)
        factory   (px/thread-factory :virtual false :prefix "penpot/file-process/")
        executor  (px/cached-executor :factory factory)
        sjobs     (ps/create :permits max-jobs)

        process-file
        (fn [file-id idx tpoint]
          (try
            (l/trc :hint "process:file:start" :file-id (str file-id) :index idx)
            (db/tx-run! (assoc main/system ::db/rollback rollback?)
                        (fn [{:keys [::db/conn] :as system}]
                          (let [file' (get-file* system file-id)
                                file  (binding [*system* system]
                                        (on-file file'))]

                            (when (and (some? file) (not (identical? file file')))

                              (when validate?
                                (cfv/validate-file-schema! file))

                              (let [file (if (contains? (:features file) "fdata/objects-map")
                                           (feat.fdata/enable-objects-map file)
                                           file)

                                    file (if (contains? (:features file) "fdata/pointer-map")
                                           (binding [pmap/*tracked* (pmap/create-tracked)]
                                             (let [file (feat.fdata/enable-pointer-map file)]
                                               (feat.fdata/persist-pointers! system file-id)
                                               file))
                                           file)]

                                (db/update! conn :file
                                            {:data (blob/encode (:data file))
                                             :deleted-at (:deleted-at file)
                                             :created-at (:created-at file)
                                             :modified-at (:modified-at file)
                                             :features (db/create-array conn "text" (:features file))
                                             :revn (:revn file)}
                                            {:id file-id}))))))
            (catch Throwable cause
              (l/wrn :hint "unexpected error on processing file (skiping)"
                     :file-id (str file-id)
                     :index idx
                     :cause cause))
            (finally
              (ps/release! sjobs)
              (let [elapsed (dt/format-duration (tpoint))]
                (l/trc :hint "process:file:end"
                       :file-id (str file-id)
                       :index idx
                       :elapsed elapsed)))))]

    (try
      (db/tx-run! main/system
                  (fn [{:keys [::db/conn] :as system}]
                    (db/exec! conn ["SET statement_timeout = 0"])
                    (db/exec! conn ["SET idle_in_transaction_session_timeout = 0"])

                    (try
                      (reduce (fn [idx file-id]
                                (ps/acquire! sjobs)
                                (px/run! executor (partial process-file file-id idx (dt/tpoint)))
                                (inc idx))
                              0
                              (->> (db/cursor conn [sql:get-file-ids (or start-at (dt/now))])
                                   (take max-items)
                                   (map :id)))
                      (finally
                        ;; Close and await tasks
                        (pu/close! executor)))))

      (catch Throwable cause
        (l/dbg :hint "process:error" :cause cause))

      (finally
        (let [elapsed (dt/format-duration (tpoint))]
          (l/dbg :hint "process:end"
                 :rollback rollback?
                 :elapsed elapsed))))))

