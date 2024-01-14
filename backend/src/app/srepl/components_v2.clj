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
   [app.svgo :as svgo]
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
      (let [completed (:processed/files newv)
            elapsed   (tpoint)]
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

;; (def ^:private sql:get-teams
;;   "SELECT id, features
;;      FROM team
;;     WHERE deleted_at IS NULL
;;     ORDER BY created_at DESC")

;; (def ^:private sql:get-teams
;;   "SELECT t.id, t.features,
;;           (SELECT count(*)
;;              FROM file_media_object AS fmo
;;              JOIN file AS f ON (f.id = fmo.file_id)
;;              JOIN project AS p ON (p.id = f.project_id)
;;             WHERE p.team_id = t.id
;;               AND fmo.mtype = 'image/svg+xml'
;;               AND fmo.is_local = false) AS graphics
;;      FROM team AS t
;;     ORDER BY t.created_at DESC")


(def ^:private sql:get-teams
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
  [[op val field]]
  (let [field (name field)]
    (case op
      :lt  [(str/ffmt "WHERE % < ?" field) val]
      :lte [(str/ffmt "WHERE % <= ?" field) val]
      :gt  [(str/ffmt "WHERE % > ?" field) val]
      :gte [(str/ffmt "WHERE % >= ?" field) val]
      :eq  [(str/ffmt "WHERE % = ?" field) val]
      [""])))

(defn- get-teams
  [conn pred]
  (let [[sql & params] (read-pred pred)]
    (->> (db/cursor conn (apply vector (str sql:get-teams sql) params))
         (map feat/decode-row)
         (remove (fn [{:keys [features]}]
                   (or (contains? features "ephimeral/v2-migration")
                       (contains? features "components/v2"))))
         (map :id))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate-file!
  [system file-id & {:keys [rollback?] :or {rollback? true}}]

  (l/dbg :hint "migrate:start" :rollback rollback?)
  (let [tpoint (dt/tpoint)
        file-id (if (string? file-id)
                  (parse-uuid file-id)
                  file-id)]
    (binding [feat/*stats* (atom {})]
      (try
        (-> (assoc system ::db/rollback rollback?)
            (feat/migrate-file! file-id))

        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint))))

        (catch Throwable cause
          (l/wrn :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-team!
  [{:keys [::db/pool] :as system} team-id & {:keys [rollback? skip-on-graphic-error? validate? skip-mark?]
                                             :or {rollback? true
                                                  validate? true
                                                  skip-on-graphic-error? false
                                                  skip-mark? false}
                                             :as opts}]

  (l/dbg :hint "migrate:start" :rollback rollback?)

  (let [team-id   (if (string? team-id)
                    (parse-uuid team-id)
                    team-id)
        stats     (atom {})
        tpoint    (dt/tpoint)]

    (add-watch stats :progress-report (report-progress-files tpoint))

    (binding [feat/*stats* stats]
      (try
        (when-not skip-mark?
          (mark-team-migration! system team-id))

        (-> (assoc system ::db/rollback rollback?)
            (feat/migrate-team! team-id
                                :validate? validate?
                                :skip-on-graphics-error? skip-on-graphic-error?))
        (print-stats!
         (-> (deref feat/*stats*)
             (assoc :elapsed (dt/format-duration (tpoint)))))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause))

        (finally
          (when-not skip-mark?
            (unmark-team-migration! system team-id))

          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :rollback rollback? :elapsed elapsed)))))))

(defn migrate-teams!
  "A REPL helper for migrate all teams.

  This function starts multiple concurrent team migration processes
  until thw maximum number of jobs is reached which by default has the
  value of `1`. This is controled with the `:max-jobs` option."

  [{:keys [::db/pool] :as system} & {:keys [max-jobs max-items max-time
                                            rollback? validate? preset
                                            pred max-procs skip-mark?
                                            on-start on-progress on-error on-end]
                                     :or {validate? true
                                          rollback? true
                                          preset :shutdown-on-failure
                                          skip-mark? true
                                          max-jobs 1
                                          max-items Long/MAX_VALUE}
                                     :as opts}]

  (let [stats     (atom {})
        tpoint    (dt/tpoint)
        mtime     (some-> max-time dt/duration)

        factory   (px/thread-factory :virtual false :prefix "penpot/migration/compv2/")
        executor  (px/cached-executor :factory factory)
        max-procs (or max-procs max-jobs)
        sjobs     (ps/create :permits max-jobs)
        sprocs    (ps/create :permits max-procs)

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
                                    (when-not skip-mark?
                                      (mark-team-migration! system team-id))
                                    (-> (assoc system ::db/rollback rollback?)
                                        (feat/migrate-team! team-id :validate? validate?))
                                    (catch Throwable cause
                                      (l/err :hint "unexpected error on processing team (skiping)"
                                             :team-id (str team-id)
                                             :cause cause))
                                    (finally
                                      (ps/release! sjobs)
                                      (when-not skip-mark?
                                        (unmark-team-migration! system team-id)))))))))]

    (l/dbg :hint "migrate:start"
           :rollback rollback?
           :max-jobs max-jobs
           :max-items max-items)

    (add-watch stats :progress-report (report-progress-teams tpoint on-progress))

    (binding [feat/*stats* stats
              svgo/*semaphore* sprocs]
      (try
        (when (fn? on-start)
          (on-start {:rollback rollback?}))

        (db/tx-run! system
                    (fn [{:keys [::db/conn]}]
                      (run! (partial migrate-team)
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
