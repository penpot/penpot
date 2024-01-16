;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.components-v2
  (:require
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.db :as db]
   [app.features.components-v2 :as feat]
   [app.main :as main]
   [app.svgo :as svgo]
   [app.util.cache :as cache]
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

(defn- print-stats!
  [stats]
  (->> stats
       (into (sorted-map))
       (pp/pprint)))

(defn- report-progress-files
  [tpoint]
  (fn [_ _ oldv newv]
    (when (not= (:processed/files oldv)
                (:processed/files newv))
      (let [elapsed (tpoint)]
        (l/dbg :hint "progress"
               :completed (:processed/files newv)
               :elapsed   (dt/format-duration elapsed))))))

(defn- report-progress-teams
  [tpoint on-progress]
  (fn [_ _ oldv newv]
    (when (not= (:processed/teams oldv)
                (:processed/teams newv))
      (let [completed (:processed/teams newv)
            elapsed   (dt/format-duration (tpoint))]
        (when (fn? on-progress)
          (on-progress {:elapsed elapsed
                        :completed completed}))
        (l/dbg :hint "progress"
               :completed completed
               :elapsed elapsed)))))

(def ^:private sql:get-teams-1
  "SELECT id, features
     FROM team
    WHERE deleted_at IS NULL
    ORDER BY created_at DESC")

(def ^:private sql:get-teams-2
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
      ORDER BY 3 ASC
   )
   SELECT * FROM teams ")

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
  [conn pred]
  (let [sql (if pred
              (let [[sql & params] (read-pred pred)]
                (apply vector (str sql:get-teams-2 sql) params))
              [sql:get-teams-1])]

    (->> (db/cursor conn sql)
         (map feat/decode-row)
         (remove (fn [{:keys [features]}]
                   (or (contains? features "ephimeral/v2-migration")
                       (contains? features "components/v2"))))
         (map :id))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate-file!
  [file-id & {:keys [rollback? validate?] :or {rollback? true validate? false}}]
  (l/dbg :hint "migrate:start" :rollback rollback?)
  (let [tpoint (dt/tpoint)
        file-id (if (string? file-id)
                  (parse-uuid file-id)
                  file-id)]
    (binding [feat/*stats* (atom {})]
      (try
        (-> (assoc main/system ::db/rollback rollback?)
            (feat/migrate-file! file-id :validate? validate?))

        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint))))

        (catch Throwable cause
          (l/wrn :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-team!
  [team-id & {:keys [rollback? skip-on-graphic-error? validate?]
              :or {rollback? true
                   validate? true
                   skip-on-graphic-error? false}}]

  (l/dbg :hint "migrate:start" :rollback rollback?)

  (let [team-id   (if (string? team-id)
                    (parse-uuid team-id)
                    team-id)
        stats     (atom {})
        tpoint    (dt/tpoint)]

    (add-watch stats :progress-report (report-progress-files tpoint))

    (binding [feat/*stats* stats]
      (try
        (-> (assoc main/system ::db/rollback rollback?)
            (feat/migrate-team! team-id
                                :validate? validate?
                                :skip-on-graphics-error? skip-on-graphic-error?))
        (print-stats!
         (-> (deref feat/*stats*)
             (assoc :elapsed (dt/format-duration (tpoint)))))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-teams!
  "A REPL helper for migrate all teams.

  This function starts multiple concurrent team migration processes
  until thw maximum number of jobs is reached which by default has the
  value of `1`. This is controled with the `:max-jobs` option."

  [& {:keys [max-jobs max-items max-time rollback? validate?
             pred max-procs cache on-start on-progress on-error on-end
             skip-on-graphic-error?]
      :or {validate? false
           rollback? true
           max-jobs 1
           skip-on-graphic-error? true
           max-items Long/MAX_VALUE}}]

  (let [stats     (atom {})
        tpoint    (dt/tpoint)
        mtime     (some-> max-time dt/duration)

        factory   (px/thread-factory :virtual false :prefix "penpot/migration/")
        executor  (px/cached-executor :factory factory)

        max-procs (or max-procs max-jobs)
        sjobs     (ps/create :permits max-jobs)
        sprocs    (ps/create :permits max-procs)

        cache     (if (int? cache)
                    (cache/create :executor :same-thread
                                  :max-items cache)
                    nil)

        migrate-team
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

              (px/run! executor (fn []
                                  (try
                                    (-> (assoc main/system ::db/rollback rollback?)
                                        (feat/migrate-team! team-id
                                                            :validate? validate?
                                                            :skip-on-graphics-error? skip-on-graphic-error?))

                                    (catch Throwable cause
                                      (l/wrn :hint "unexpected error on processing team (skiping)"
                                             :team-id (str team-id)
                                             :cause cause))

                                    (finally
                                      (ps/release! sjobs))))))))]

    (l/dbg :hint "migrate:start"
           :rollback rollback?
           :max-jobs max-jobs
           :max-items max-items)

    (add-watch stats :progress-report (report-progress-teams tpoint on-progress))

    (binding [feat/*stats* stats
              feat/*cache* cache
              svgo/*semaphore* sprocs]
      (try
        (when (fn? on-start)
          (on-start {:rollback rollback?}))

        (db/tx-run! main/system
                    (fn [{:keys [::db/conn]}]
                      (db/exec! conn ["SET statement_timeout = 0;"])
                      (db/exec! conn ["SET idle_in_transaction_session_timeout = 0;"])
                      (run! migrate-team
                            (->> (get-teams conn pred)
                                 (take max-items)))))

        ;; Close and await tasks
        (pu/close! executor)

        (if (fn? on-end)
          (-> (deref stats)
              (assoc :elapsed/total (tpoint))
              (on-end))
          (-> (deref stats)
              (assoc :elapsed/total (tpoint))
              (update :elapsed/total dt/format-duration)
              (dissoc :total/teams)
              (print-stats!)))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause)
          (when (fn? on-error)
            (on-error cause)))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end"
                   :rollback rollback?
                   :elapsed elapsed)))))))
