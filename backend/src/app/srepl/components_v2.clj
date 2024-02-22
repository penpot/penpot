;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.components-v2
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.components-v2 :as feat]
   [app.main :as main]
   [app.srepl.helpers :as h]
   [app.svgo :as svgo]
   [app.util.cache :as cache]
   [app.util.events :as events]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(def ^:dynamic *scope* nil)
(def ^:dynamic *semaphore* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRIVATE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- report-progress-files
  [tpoint]
  (fn [_ _ oldv newv]
    (when (or (not= (:processed-files oldv)
                    (:processed-files newv))
              (not= (:errors oldv)
                    (:errors newv)))
      (let [completed (:processed-files newv 0)
            errors    (:errors newv 0)
            elapsed   (dt/format-duration (tpoint))]
        (events/tap :progress-report
                    {:elapsed elapsed
                     :completed completed
                     :errors errors})
        (l/dbg :hint "progress"
               :completed completed
               :elapsed elapsed)))))

(defn- report-progress-teams
  [tpoint]
  (fn [_ _ oldv newv]
    (when (or (not= (:processed-teams oldv)
                    (:processed-teams newv))
              (not= (:errors oldv)
                    (:errors newv)))
      (let [completed (:processed-teams newv 0)
            errors    (:errors newv 0)
            elapsed   (dt/format-duration (tpoint))]
        (events/tap :progress-report
                    {:elapsed elapsed
                     :completed completed
                     :errors errors})
        (l/dbg :hint "progress"
               :completed completed
               :elapsed elapsed)))))

(def ^:private sql:get-teams-by-created-at
  "WITH teams AS (
     SELECT id, features
       FROM team
      WHERE deleted_at IS NULL
      ORDER BY created_at DESC
   ) SELECT * FROM TEAMS %(pred)s")

(def ^:private sql:get-teams-by-graphics
  "WITH teams AS (
     SELECT t.id, t.features,
            (SELECT count(*)
               FROM file_media_object AS fmo
               JOIN file AS f ON (f.id = fmo.file_id)
               JOIN project AS p ON (p.id = f.project_id)
              WHERE p.team_id = t.id
                AND fmo.mtype = 'image/svg+xml'
                AND fmo.is_local = false) AS graphics
       FROM team AS t
      WHERE t.deleted_at IS NULL
      ORDER BY 3 ASC
   )
   SELECT * FROM teams %(pred)s")

(def ^:private sql:get-teams-by-activity
  "WITH teams AS (
     SELECT t.id, t.features,
            (SELECT coalesce(max(date_trunc('month', f.modified_at)), date_trunc('month', t.modified_at))
               FROM file AS f
               JOIN project AS p ON (f.project_id = p.id)
              WHERE p.team_id = t.id) AS updated_at,
            (SELECT coalesce(count(*), 0)
               FROM file AS f
               JOIN project AS p ON (f.project_id = p.id)
              WHERE p.team_id = t.id) AS total_files
       FROM team AS t
      WHERE t.deleted_at IS NULL
      ORDER BY 3 DESC, 4 DESC
   )
   SELECT * FROM teams %(pred)s")

(def ^:private sql:get-teams-by-report
  "WITH teams AS (
     SELECT t.id t.features, mr.name
       FROM migration_team_report AS mr
       JOIN team AS t ON (t.id = mr.team_id)
      WHERE t.deleted_at IS NULL
        AND mr.error IS NOT NULL
      ORDER BY mr.created_at
   ) SELECT id, features FROM teams %(pred)s")

(def ^:private sql:get-files-by-created-at
  "SELECT id, features
     FROM file
    WHERE deleted_at IS NULL
    ORDER BY created_at DESC")

(def ^:private sql:get-files-by-modified-at
  "SELECT id, features
     FROM file
    WHERE deleted_at IS NULL
    ORDER BY modified_at DESC")

(def ^:private sql:get-files-by-graphics
  "WITH files AS (
     SELECT f.id, f.features,
            (SELECT count(*) FROM file_media_object AS fmo
              WHERE fmo.mtype = 'image/svg+xml'
                AND fmo.is_local = false
                AND fmo.file_id = f.id) AS graphics
       FROM file AS f
      WHERE f.deleted_at IS NULL
      ORDER BY 3 ASC
   ) SELECT * FROM files %(pred)s")

(def ^:private sql:get-files-by-report
  "WITH files AS (
     SELECT f.id, f.features, mr.label
       FROM migration_file_report AS mr
       JOIN file AS f ON (f.id = mr.file_id)
      WHERE f.deleted_at IS NULL
        AND mr.error IS NOT NULL
      ORDER BY mr.created_at
   ) SELECT id, features FROM files %(pred)s")

(defn- read-pred
  [entries]
  (let [entries (if (and (vector? entries)
                         (keyword? (first entries)))
                  [entries]
                  entries)]
    (loop [params  []
           queries []
           entries (seq entries)]
      (if-let [[op val field] (first entries)]
        (let [field (name field)
              cond  (case op
                      :lt  (str/ffmt "% < ?" field)
                      :lte (str/ffmt "% <= ?" field)
                      :gt  (str/ffmt "% > ?" field)
                      :gte (str/ffmt "% >= ?" field)
                      :eq  (str/ffmt "% = ?" field))]
          (recur (conj params val)
                 (conj queries cond)
                 (rest entries)))

        (let [sql (apply str "WHERE " (str/join " AND " queries))]
          (apply vector sql params))))))

(defn- get-teams
  [conn query pred]
  (let [query (d/nilv query :created-at)
        sql   (case query
                :created-at sql:get-teams-by-created-at
                :activity   sql:get-teams-by-activity
                :graphics   sql:get-teams-by-graphics
                :report     sql:get-teams-by-report)
        sql  (if pred
               (let [[pred-sql & pred-params] (read-pred pred)]
                 (apply vector
                        (str/format sql {:pred pred-sql})
                        pred-params))
               [(str/format sql {:pred ""})])]

    (->> (db/cursor conn sql {:chunk-size 500})
         (map feat/decode-row)
         (remove (fn [{:keys [features]}]
                   (contains? features "components/v2")))
         (map :id))))

(defn- get-files
  [conn query pred]
  (let [query (d/nilv query :created-at)
        sql   (case query
                :created-at  sql:get-files-by-created-at
                :modified-at sql:get-files-by-modified-at
                :graphics    sql:get-files-by-graphics
                :report      sql:get-files-by-report)
        sql  (if pred
               (let [[pred-sql & pred-params] (read-pred pred)]
                 (apply vector
                        (str/format sql {:pred pred-sql})
                        pred-params))
               [(str/format sql {:pred ""})])]

    (->> (db/cursor conn sql {:chunk-size 500})
         (map feat/decode-row)
         (remove (fn [{:keys [features]}]
                   (contains? features "components/v2")))
         (map :id))))

(def ^:private sql:team-report-table
  "CREATE UNLOGGED TABLE IF NOT EXISTS migration_team_report (
     id bigserial NOT NULL,
     label text NOT NULL,
     team_id UUID NOT NULL,
     error text NULL,
     created_at timestamptz NOT NULL DEFAULT now(),
     elapsed bigint NOT NULL,
     PRIMARY KEY (label, created_at, id))")

(def ^:private sql:file-report-table
  "CREATE UNLOGGED TABLE IF NOT EXISTS migration_file_report (
     id bigserial NOT NULL,
     label text NOT NULL,
     file_id UUID NOT NULL,
     error text NULL,
     created_at timestamptz NOT NULL DEFAULT now(),
     elapsed bigint NOT NULL,
     PRIMARY KEY (label, created_at, id))")

(defn- create-report-tables!
  [system]
  (db/exec-one! system [sql:team-report-table])
  (db/exec-one! system [sql:file-report-table]))

(defn- clean-team-reports!
  [system label]
  (db/delete! system :migration-team-report {:label label}))

(defn- team-report!
  [system team-id label elapsed error]
  (db/insert! system :migration-team-report
              {:label label
               :team-id team-id
               :elapsed (inst-ms elapsed)
               :error error}
              {::db/return-keys false}))

(defn- clean-file-reports!
  [system label]
  (db/delete! system :migration-file-report {:label label}))

(defn- file-report!
  [system file-id label elapsed error]
  (db/insert! system :migration-file-report
              {:label label
               :file-id file-id
               :elapsed (inst-ms elapsed)
               :error error}
              {::db/return-keys false}))


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
        file-id (h/parse-uuid file-id)
        cache   (if (int? cache)
                  (cache/create :executor (::wrk/executor main/system)
                                :max-items cache)
                  nil)]

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
        tpoint  (dt/tpoint)

        cache   (if (int? cache)
                  (cache/create :executor (::wrk/executor main/system)
                                :max-items cache)
                  nil)]

    (add-watch stats :progress-report (report-progress-files tpoint))

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

(defn migrate-teams!
  "A REPL helper for migrate all teams.

  This function starts multiple concurrent team migration processes
  until thw maximum number of jobs is reached which by default has the
  value of `1`. This is controled with the `:max-jobs` option.

  If you want to run this on multiple machines you will need to specify
  the total number of partitions and the current partition.

  In order to get the report table populated, you will need to provide
  a correct `:label`. That label is also used for persist a file
  snaphot before continue with the migration."
  [& {:keys [max-jobs max-items max-time rollback? validate? query
             pred max-procs cache skip-on-graphic-error?
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
        mtime     (some-> max-time dt/duration)

        factory   (px/thread-factory :virtual false :prefix "penpot/migration/")
        executor  (px/cached-executor :factory factory)

        max-procs (or max-procs max-jobs)
        sjobs     (ps/create :permits max-jobs)
        sprocs    (ps/create :permits max-procs)

        cache     (if (int? cache)
                    (cache/create :executor (::wrk/executor main/system)
                                  :max-items cache)
                    nil)
        migrate-team
        (fn [team-id]
          (let [tpoint (dt/tpoint)]
            (try
              (db/tx-run! (assoc main/system ::db/rollback rollback?)
                          (fn [system]
                            (db/exec-one! system ["SET idle_in_transaction_session_timeout = 0"])
                            (feat/migrate-team! system team-id
                                                :label label
                                                :validate? validate?
                                                :skip-on-graphic-error? skip-on-graphic-error?)))

              (when (string? label)
                (team-report! main/system team-id label (tpoint) nil))

              (catch Throwable cause
                (l/wrn :hint "unexpected error on processing team (skiping)"
                       :team-id (str team-id))

                (events/tap :error
                            (ex-info "unexpected error on processing team (skiping)"
                                     {:team-id team-id}
                                     cause))

                (swap! stats update :errors (fnil inc 0))

                (when (string? label)
                  (team-report! main/system team-id label (tpoint) (ex-message cause))))

              (finally
                (ps/release! sjobs)))))

        process-team
        (fn [team-id]
          (ps/acquire! sjobs)
          (let [ts (tpoint)]
            (if (and mtime (neg? (compare mtime ts)))
              (do
                (l/inf :hint "max time constraint reached"
                       :team-id (str team-id)
                       :elapsed (dt/format-duration ts))
                (ps/release! sjobs)
                (reduced nil))

              (px/run! executor (partial migrate-team team-id)))))]

    (l/dbg :hint "migrate:start"
           :label label
           :rollback rollback?
           :max-jobs max-jobs
           :max-items max-items)

    (add-watch stats :progress-report (report-progress-teams tpoint))

    (binding [feat/*stats* stats
              feat/*cache* cache
              svgo/*semaphore* sprocs]
      (try
        (when (string? label)
          (create-report-tables! main/system)
          (clean-team-reports! main/system label))

        (db/tx-run! main/system
                    (fn [{:keys [::db/conn] :as system}]
                      (db/exec! conn ["SET statement_timeout = 0"])
                      (db/exec! conn ["SET idle_in_transaction_session_timeout = 0"])

                      (run! process-team
                            (->> (get-teams conn query pred)
                                 (filter (fn [team-id]
                                           (if (int? partitions)
                                             (= current-partition (-> (uuid/hash-int team-id)
                                                                      (mod partitions)
                                                                      (inc)))
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
  [& {:keys [max-jobs max-items max-time rollback? validate? query
             pred max-procs cache skip-on-graphic-error?
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
        mtime     (some-> max-time dt/duration)

        factory   (px/thread-factory :virtual false :prefix "penpot/migration/")
        executor  (px/cached-executor :factory factory)

        max-procs (or max-procs max-jobs)
        sjobs     (ps/create :permits max-jobs)
        sprocs    (ps/create :permits max-procs)

        cache     (if (int? cache)
                    (cache/create :executor (::wrk/executor main/system)
                                  :max-items cache)
                    nil)

        migrate-file
        (fn [file-id]
          (let [tpoint (dt/tpoint)]
            (try
              (db/tx-run! (assoc main/system ::db/rollback rollback?)
                          (fn [system]
                            (db/exec-one! system ["SET idle_in_transaction_session_timeout = 0"])
                            (feat/migrate-file! system file-id
                                                :label label
                                                :validate? validate?
                                                :skip-on-graphic-error? skip-on-graphic-error?)))

              (when (string? label)
                (file-report! main/system file-id label (tpoint) nil))

              (catch Throwable cause
                (l/wrn :hint "unexpected error on processing file (skiping)"
                       :file-id (str file-id))

                (events/tap :error
                            (ex-info "unexpected error on processing file (skiping)"
                                     {:file-id file-id}
                                     cause))

                (swap! stats update :errors (fnil inc 0))

                (when (string? label)
                  (file-report! main/system file-id label (tpoint) (ex-message cause))))

              (finally
                (ps/release! sjobs)))))

        process-file
        (fn [file-id]
          (ps/acquire! sjobs)
          (let [ts (tpoint)]
            (if (and mtime (neg? (compare mtime ts)))
              (do
                (l/inf :hint "max time constraint reached"
                       :file-id (str file-id)
                       :elapsed (dt/format-duration ts))
                (ps/release! sjobs)
                (reduced nil))

              (px/run! executor (partial migrate-file file-id)))))]

    (l/dbg :hint "migrate:start"
           :label label
           :rollback rollback?
           :max-jobs max-jobs
           :max-items max-items)

    (add-watch stats :progress-report (report-progress-files tpoint))

    (binding [feat/*stats* stats
              feat/*cache* cache
              svgo/*semaphore* sprocs]
      (try
        (when (string? label)
          (create-report-tables! main/system)
          (clean-file-reports! main/system label))

        (db/tx-run! main/system
                    (fn [{:keys [::db/conn] :as system}]
                      (db/exec! conn ["SET statement_timeout = 0"])
                      (db/exec! conn ["SET idle_in_transaction_session_timeout = 0"])

                      (run! process-file
                            (->> (get-files conn query pred)
                                 (filter (fn [file-id]
                                           (if (int? partitions)
                                             (= current-partition (-> (uuid/hash-int file-id)
                                                                      (mod partitions)
                                                                      (inc)))
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
;; FILE PROCESS HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn delete-broken-files
  [{:keys [id data] :as file}]
  (if (-> data :options :components-v2 true?)
    (do
      (l/wrn :hint "found old components-v2 format"
             :file-id (str id)
             :file-name (:name file))
      (assoc file :deleted-at (dt/now)))
    file))
