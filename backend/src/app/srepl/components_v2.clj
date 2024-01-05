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
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [promesa.core :as p]
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
      (let [total     (:total/files newv)
            completed (:processed/files newv)
            progress  (/ (* completed 100.0) total)
            elapsed   (tpoint)]
        (l/dbg :hint "progress"
               :completed (:processed/files newv)
               :total     (:total/files newv)
               :progress  (str (int progress) "%")
               :elapsed   (dt/format-duration elapsed))))))

(defn- report-progress-teams
  [tpoint on-progress]
  (fn [_ _ oldv newv]
    (when (not= (:processed/teams oldv)
                (:processed/teams newv))
      (let [total     (:total/teams newv)
            completed (:processed/teams newv)
            progress  (/ (* completed 100.0) total)
            progress  (str (int progress) "%")
            elapsed   (dt/format-duration (tpoint))]

        (when (fn? on-progress)
          (on-progress {:total total
                        :elapsed elapsed
                        :completed completed
                        :progress progress}))

        (l/dbg :hint "progress"
               :completed completed
               :progress progress
               :elapsed elapsed)))))

(defn- get-total-files
  [pool & {:keys [team-id]}]
  (if (some? team-id)
    (let [sql (str/concat
               "SELECT count(f.id) AS count FROM file AS f "
               "  JOIN project AS p ON (p.id = f.project_id) "
               " WHERE p.team_id = ? AND f.deleted_at IS NULL "
               "  AND p.deleted_at IS NULL")
          res (db/exec-one! pool [sql team-id])]
      (:count res))

    (let [sql (str/concat
               "SELECT count(id) AS count FROM file "
               " WHERE deleted_at IS NULL")
          res (db/exec-one! pool [sql])]
      (:count res))))

(defn- get-total-teams
  [pool]
  (let [sql (str/concat
             "SELECT count(id) AS count FROM team "
             " WHERE deleted_at IS NULL")
        res (db/exec-one! pool [sql])]
    (:count res)))


(defn- mark-team-migration!
  [{:keys [::db/pool]} team-id]
  ;; We execute this out of transaction because we want this
  ;; change to be visible to all other sessions before starting
  ;; the migration
  (let [sql (str "UPDATE team SET features = "
                 "    array_append(features, 'ephimeral/v2-migration') "
                 " WHERE id = ?")]
    (db/exec-one! pool [sql team-id])))

(defn- unmark-team-migration!
  [{:keys [::db/pool]} team-id]
  ;; We execute this out of transaction because we want this
  ;; change to be visible to all other sessions before starting
  ;; the migration
  (let [sql (str "UPDATE team SET features = "
                 "    array_remove(features, 'ephimeral/v2-migration') "
                 " WHERE id = ?")]
    (db/exec-one! pool [sql team-id])))

(def ^:private sql:get-teams
  "SELECT id, features
     FROM team
    WHERE deleted_at IS NULL
    ORDER BY created_at ASC")

(defn- get-teams
  [conn]
  (->> (db/cursor conn sql:get-teams)
       (map feat/decode-row)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate-file!
  [system file-id & {:keys [rollback? max-procs]
                     :or {rollback? true}}]

  (l/dbg :hint "migrate:start" :rollback rollback?)
  (let [tpoint (dt/tpoint)
        file-id (if (string? file-id)
                  (parse-uuid file-id)
                  file-id)]
    (binding [feat/*stats* (atom {})]
      (try
        (-> (assoc system ::db/rollback rollback?)
            (feat/migrate-file! file-id :max-procs max-procs))

        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint))))

        (catch Throwable cause
          (l/wrn :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-team!
  [{:keys [::db/pool] :as system} team-id & {:keys [rollback? skip-on-error validate? max-procs]
                                             :or {rollback? true
                                                  skip-on-error true
                                                  validate? false
                                                  max-procs 1 }
                                             :as opts}]

  (l/dbg :hint "migrate:start" :rollback rollback?)

  (let [team-id   (if (string? team-id)
                    (parse-uuid team-id)
                    team-id)
        total     (get-total-files pool :team-id team-id)
        stats     (atom {:total/files total})
        tpoint    (dt/tpoint)]

    (add-watch stats :progress-report (report-progress-files tpoint))

    (binding [feat/*stats* stats
              feat/*skip-on-error* skip-on-error]

      (try
        (mark-team-migration! system team-id)

        (-> (assoc system ::db/rollback rollback?)
            (feat/migrate-team! team-id
                                :max-procs max-procs
                                :validate? validate?
                                :throw-on-validate? (not skip-on-error)))

        (print-stats!
         (-> (deref feat/*stats*)
             (dissoc :total/files)
             (assoc :elapsed (dt/format-duration (tpoint)))))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause))

        (finally
          (unmark-team-migration! system team-id)

          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-teams!
  "A REPL helper for migrate all teams.

  This function starts multiple concurrent team migration processes
  until thw maximum number of jobs is reached which by default has the
  value of `1`. This is controled with the `:max-jobs` option.

  Each tram migration process also can start multiple procs for
  graphics migration, the total of that procs is controled with the
  `:max-procs` option.

  Internally, the graphics migration process uses SVGO module which by
  default has a limited number of maximum concurent
  operations (globally), ensure setting up correct number with
  PENPOT_SVGO_MAX_PROCS environment variable."

  [{:keys [::db/pool] :as system} & {:keys [max-jobs max-procs max-items
                                            rollback? validate? preset
                                            skip-on-error max-time
                                            on-start on-progress on-error on-end]
                                     :or {validate? false
                                          rollback? true
                                          skip-on-error true
                                          preset :shutdown-on-failure
                                          max-jobs 1
                                          max-procs 10
                                          max-items Long/MAX_VALUE}
                                     :as opts}]

  (let [total  (get-total-teams pool)
        stats  (atom {:total/teams (min total max-items)})

        tpoint (dt/tpoint)
        mtime  (some-> max-time dt/duration)

        scope  (px/structured-task-scope :preset preset :factory :virtual)
        sjobs  (ps/create :permits max-jobs)

        migrate-team
        (fn [{:keys [id features] :as team}]
          (ps/acquire! sjobs)
          (let [ts (tpoint)]
            (cond
              (and mtime (neg? (compare mtime ts)))
              (do
                (l/inf :hint "max time constraint reached"
                       :team-id (str id)
                       :elapsed (dt/format-duration ts))
                (ps/release! sjobs)
                (reduced nil))

              (or (contains? features "ephimeral/v2-migration")
                  (contains? features "components/v2"))
              (do
                (l/dbg :hint "skip team" :team-id (str id))
                (ps/release! sjobs))

              :else
              (px/submit! scope (fn []
                                    (try
                                      (mark-team-migration! system id)
                                      (-> (assoc system ::db/rollback rollback?)
                                          (feat/migrate-team! id
                                                              :max-procs max-procs
                                                              :validate? validate?
                                                              :throw-on-validate? (not skip-on-error)))
                                      (catch Throwable cause
                                        (l/err :hint "unexpected error on processing team"
                                               :team-id (str id)
                                               :cause cause))
                                      (finally
                                        (ps/release! sjobs)
                                        (unmark-team-migration! system id))))))))]

    (l/dbg :hint "migrate:start"
           :rollback rollback?
           :total total
           :max-jobs max-jobs
           :max-procs max-procs
           :max-items max-items)

    (add-watch stats :progress-report (report-progress-teams tpoint on-progress))

    (binding [feat/*stats* stats
              feat/*skip-on-error* skip-on-error]
      (try
        (when (fn? on-start)
          (on-start {:total total :rollback rollback?}))

        (db/tx-run! system
                    (fn [{:keys [::db/conn]}]
                      (run! (partial migrate-team)
                            (->> (get-teams conn)
                                 (take max-items)))))
        (try
          (p/await! scope)
          (finally
            (pu/close! scope)))


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
