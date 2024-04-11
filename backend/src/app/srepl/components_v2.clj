;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.components-v2
  (:require
   [app.common.fressian :as fres]
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.components-v2 :as feat]
   [app.main :as main]
   [app.srepl.helpers :as h]
   [app.util.events :as events]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(def ^:dynamic *scope* nil)
(def ^:dynamic *semaphore* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRIVATE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-by-created-at
  "SELECT id, features,
          row_number() OVER (ORDER BY created_at DESC) AS rown
     FROM file
    WHERE deleted_at IS NULL
    ORDER BY created_at DESC")

(defn- get-files
  [conn]
  (->> (db/cursor conn [sql:get-files-by-created-at] {:chunk-size 500})
       (map feat/decode-row)
       (remove (fn [{:keys [features]}]
                 (contains? features "components/v2")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate-file!
  [file-id & {:keys [rollback? validate? label cache skip-on-graphic-error?]
              :or {rollback? true
                   validate? false
                   skip-on-graphic-error? true}}]
  (l/dbg :hint "migrate:start" :rollback rollback?)
  (let [tpoint  (dt/tpoint)
        file-id (h/parse-uuid file-id)]

    (binding [feat/*stats* (atom {})
              feat/*cache* cache]
      (try
        (-> (assoc main/system ::db/rollback rollback?)
            (feat/migrate-file! file-id
                                :validate? validate?
                                :skip-on-graphic-error? skip-on-graphic-error?
                                :label label))

        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint))))

        (catch Throwable cause
          (l/wrn :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-team!
  [team-id & {:keys [rollback? skip-on-graphic-error? validate? label cache]
              :or {rollback? true
                   validate? true
                   skip-on-graphic-error? true}}]

  (l/dbg :hint "migrate:start" :rollback rollback?)

  (let [team-id (h/parse-uuid team-id)
        stats   (atom {})
        tpoint  (dt/tpoint)]

    (binding [feat/*stats* stats
              feat/*cache* cache]
      (try
        (-> (assoc main/system ::db/rollback rollback?)
            (feat/migrate-team! team-id
                                :label label
                                :validate? validate?
                                :skip-on-graphics-error? skip-on-graphic-error?))

        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint))))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-files!
  "A REPL helper for migrate all files.

  This function starts multiple concurrent file migration processes
  until thw maximum number of jobs is reached which by default has the
  value of `1`. This is controled with the `:max-jobs` option.

  If you want to run this on multiple machines you will need to specify
  the total number of partitions and the current partition.

  In order to get the report table populated, you will need to provide
  a correct `:label`. That label is also used for persist a file
  snaphot before continue with the migration."
  [& {:keys [max-jobs max-items rollback? validate?
             cache skip-on-graphic-error?
             label partitions current-partition]
      :or {validate? false
           rollback? true
           max-jobs 1
           current-partition 1
           skip-on-graphic-error? true
           max-items Long/MAX_VALUE}}]

  (when (int? partitions)
    (when-not (int? current-partition)
      (throw (IllegalArgumentException. "missing `current-partition` parameter")))
    (when-not (<= 0 current-partition partitions)
      (throw (IllegalArgumentException. "invalid value on `current-partition` parameter"))))

  (let [stats     (atom {})
        tpoint    (dt/tpoint)
        factory   (px/thread-factory :virtual false :prefix "penpot/migration/")
        executor  (px/cached-executor :factory factory)

        sjobs     (ps/create :permits max-jobs)

        migrate-file
        (fn [file-id rown]
          (try
            (db/tx-run! (assoc main/system ::db/rollback rollback?)
                        (fn [system]
                          (db/exec-one! system ["SET LOCAL idle_in_transaction_session_timeout = 0"])
                          (feat/migrate-file! system file-id
                                              :rown rown
                                              :label label
                                              :validate? validate?
                                              :skip-on-graphic-error? skip-on-graphic-error?)))

            (catch Throwable cause
              (l/wrn :hint "unexpected error on processing file (skiping)"
                     :file-id (str file-id))

              (events/tap :error
                          (ex-info "unexpected error on processing file (skiping)"
                                   {:file-id file-id}
                                   cause))

              (swap! stats update :errors (fnil inc 0)))

            (finally
              (ps/release! sjobs))))

        process-file
        (fn [{:keys [id rown]}]
          (ps/acquire! sjobs)
          (px/run! executor (partial migrate-file id rown)))]

    (l/dbg :hint "migrate:start"
           :label label
           :rollback rollback?
           :max-jobs max-jobs
           :max-items max-items)

    (binding [feat/*stats* stats
              feat/*cache* cache]
      (try
        (db/tx-run! main/system
                    (fn [{:keys [::db/conn] :as system}]
                      (db/exec! conn ["SET LOCAL statement_timeout = 0"])
                      (db/exec! conn ["SET LOCAL idle_in_transaction_session_timeout = 0"])

                      (run! process-file
                            (->> (get-files conn)
                                 (filter (fn [{:keys [rown] :as row}]
                                           (if (int? partitions)
                                             (= current-partition (inc (mod rown partitions)))
                                             true)))
                                 (take max-items)))

                      ;; Close and await tasks
                      (pu/close! executor)))

        (-> (deref stats)
            (assoc :elapsed (dt/format-duration (tpoint))))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause)
          (events/tap :error cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end"
                   :rollback rollback?
                   :elapsed elapsed)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CACHE POPULATE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:sobjects-for-cache
  "SELECT id,
          row_number() OVER (ORDER BY created_at) AS index
     FROM storage_object
    WHERE (metadata->>'~:bucket' = 'file-media-object' OR
           metadata->>'~:bucket' IS NULL)
      AND metadata->>'~:content-type' = 'image/svg+xml'
      AND deleted_at IS NULL
      AND size < 1135899
    ORDER BY created_at ASC")

(defn populate-cache!
  "A REPL helper for migrate all files.

  This function starts multiple concurrent file migration processes
  until thw maximum number of jobs is reached which by default has the
  value of `1`. This is controled with the `:max-jobs` option.

  If you want to run this on multiple machines you will need to specify
  the total number of partitions and the current partition.

  In order to get the report table populated, you will need to provide
  a correct `:label`. That label is also used for persist a file
  snaphot before continue with the migration."
  [& {:keys [max-jobs] :or {max-jobs 1}}]

  (let [tpoint    (dt/tpoint)

        factory   (px/thread-factory :virtual false :prefix "penpot/cache/")
        executor  (px/cached-executor :factory factory)

        sjobs     (ps/create :permits max-jobs)

        retrieve-sobject
        (fn [id index]
          (let [path   (feat/get-sobject-cache-path id)
                parent (fs/parent path)]

            (try
              (when-not (fs/exists? parent)
                (fs/create-dir parent))

              (if (fs/exists? path)
                (l/inf :hint "create cache entry" :status "exists" :index index :id (str id) :path (str path))
                (let [svg-data (feat/get-optimized-svg id)]
                  (with-open [^java.lang.AutoCloseable stream (io/output-stream path)]
                    (let [writer (fres/writer stream)]
                      (fres/write! writer svg-data)))

                  (l/inf :hint "create cache entry" :status "created"
                         :index index
                         :id (str id)
                         :path (str path))))

              (catch Throwable cause
                (l/wrn :hint "create cache entry"
                       :status "error"
                       :index index
                       :id (str id)
                       :path (str path)
                       :cause cause))

              (finally
                (ps/release! sjobs)))))

        process-sobject
        (fn [{:keys [id index]}]
          (ps/acquire! sjobs)
          (px/run! executor (partial retrieve-sobject id index)))]

    (l/dbg :hint "migrate:start"
           :max-jobs max-jobs)

    (try
      (binding [feat/*system* main/system]
        (run! process-sobject
              (db/exec! main/system [sql:sobjects-for-cache]))

        ;; Close and await tasks
        (pu/close! executor))

      {:elapsed (dt/format-duration (tpoint))}

      (catch Throwable cause
        (l/dbg :hint "populate:error" :cause cause))

      (finally
        (let [elapsed (dt/format-duration (tpoint))]
          (l/dbg :hint "populate:end"
                 :elapsed elapsed))))))
