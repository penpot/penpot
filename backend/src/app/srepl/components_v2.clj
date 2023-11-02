;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.components-v2
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
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

(defn- print-stats!
  [stats]
  (let [stats (-> stats
                  (d/update-when :elapsed/max-by-team (comp dt/format-duration dt/duration int))
                  (d/update-when :elapsed/avg-by-team (comp dt/format-duration dt/duration int))
                  (d/update-when :elapsed/total-by-team (comp dt/format-duration dt/duration int))
                  (d/update-when :elapsed/max-by-file (comp dt/format-duration dt/duration int))
                  (d/update-when :elapsed/avg-by-file (comp dt/format-duration dt/duration int))
                  (d/update-when :elapsed/total-by-file (comp dt/format-duration dt/duration int))
                  )]
    (->> stats
         (into (sorted-map))
         (pp/pprint))))

(defn- report-progress-files
  [tpoint]
  (fn [_ _ oldv newv]
    (when (not= (:processed/files oldv)
                (:processed/files newv))
      (let [total     (:total/files newv)
            completed (:processed/files newv)
            progress  (/ (* completed 100.0) total)
            elapsed   (tpoint)]
        (l/trc :hint "progress"
               :completed (:processed/files newv)
               :total     (:total/files newv)
               :progress  (str (int progress) "%")
               :elapsed   (dt/format-duration elapsed))))))

(defn- report-progress-teams
  [tpoint]
  (fn [_ _ oldv newv]
    (when (not= (:processed/teams oldv)
                (:processed/teams newv))
      (let [total     (:total/teams newv)
            completed (:processed/teams newv)
            progress  (/ (* completed 100.0) total)
            elapsed   (tpoint)]
        (l/trc :hint "progress"
               :completed (:processed/teams newv)
               :progress  (str (int progress) "%")
               :elapsed   (dt/format-duration elapsed))))))

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

(defn migrate-file!
  [system file-id & {:keys [rollback] :or {rollback true}}]

  (l/dbg :hint "migrate:start")
  (let [tpoint (dt/tpoint)]
    (try
      (binding [feat/*stats* (atom {})]
        (-> (assoc system ::db/rollback rollback)
            (feat/migrate-file! file-id))
        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint)))
            (dissoc :current/graphics)
            (dissoc :current/components)
            (dissoc :current/files)))

      (catch Throwable cause
        (l/dbg :hint "migrate:error" :cause cause))

      (finally
        (let [elapsed (dt/format-duration (tpoint))]
          (l/dbg :hint "migrate:end" :elapsed elapsed))))))

(defn migrate-files!
  [{:keys [::db/pool] :as system} & {:keys [chunk-size max-jobs max-items start-at preset rollback skip-on-error]
                                     :or {chunk-size 10
                                          skip-on-error true
                                          max-jobs 10
                                          max-items Long/MAX_VALUE
                                          preset :shutdown-on-failure
                                          rollback true}}]
  (letfn [(get-chunk [cursor]
            (let [sql  (str/concat
                        "SELECT id, created_at FROM file "
                        " WHERE created_at < ? AND deleted_at IS NULL "
                        " ORDER BY created_at desc LIMIT ?")
                  rows (db/exec! pool [sql cursor chunk-size])]
              [(some->> rows peek :created-at) (seq rows)]))

          (get-candidates []
            (->> (d/iteration get-chunk
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)
                 (map :id)))]

    (l/dbg :hint "migrate:start")
    (let [fsem   (ps/create :permits max-jobs)
          total  (get-total-files pool)
          stats  (atom {:files/total total})
          tpoint (dt/tpoint)]

      (add-watch stats :progress-report (report-progress-files tpoint))

      (binding [feat/*stats* stats
                feat/*semaphore* fsem
                feat/*skip-on-error* skip-on-error]
        (try
          (pu/with-open [scope (px/structured-task-scope :preset preset :factory :virtual)]

            (run! (fn [file-id]
                    (ps/acquire! feat/*semaphore*)
                    (px/submit! scope (fn []
                                        (-> (assoc system ::db/rollback rollback)
                                            (feat/migrate-file! file-id)))))
                  (get-candidates))

            (p/await! scope))

        (-> (deref feat/*stats*)
            (assoc :elapsed (dt/format-duration (tpoint)))
            (dissoc :current/graphics)
            (dissoc :current/components)
            (dissoc :current/files))

        (catch Throwable cause
          (l/dbg :hint "migrate:error" :cause cause))

        (finally
          (let [elapsed (dt/format-duration (tpoint))]
            (l/dbg :hint "migrate:end" :elapsed elapsed))))))))

(defn migrate-team!
  [{:keys [::db/pool] :as system} team-id
   & {:keys [rollback skip-on-error]
      :or {rollback true skip-on-error true}}]
  (l/dbg :hint "migrate:start")

  (let [total  (get-total-files pool :team-id team-id)
        stats  (atom {:total/files total})
        tpoint (dt/tpoint)]

    (add-watch stats :progress-report (report-progress-files tpoint))

    (try
      (binding [feat/*stats* stats
                feat/*skip-on-error* skip-on-error]
        (-> (assoc system ::db/rollback rollback)
            (feat/migrate-team! team-id))

        (print-stats!
         (-> (deref feat/*stats*)
             (dissoc :total/files)
             (dissoc :current/graphics)
             (dissoc :current/components)
             (dissoc :current/files))))

      (catch Throwable cause
        (l/dbg :hint "migrate:error" :cause cause))

      (finally
        (let [elapsed (dt/format-duration (tpoint))]
          (l/dbg :hint "migrate:end" :elapsed elapsed))))))

(defn migrate-teams!
  [{:keys [::db/pool] :as system}
   & {:keys [chunk-size max-jobs max-items start-at rollback preset skip-on-error]
      :or {chunk-size 10000
           rollback true
           skip-on-error true
           preset :shutdown-on-failure
           max-jobs Integer/MAX_VALUE
           max-items Long/MAX_VALUE}}]
  (letfn [(get-chunk [cursor]
            (let [sql  (str/concat
                        "SELECT id, created_at, features FROM team "
                        " WHERE created_at < ? AND deleted_at IS NULL "
                        " ORDER BY created_at desc LIMIT ?")
                  rows (db/exec! pool [sql cursor chunk-size])]
              [(some->> rows peek :created-at) (seq rows)]))

          (get-candidates []
            (->> (d/iteration get-chunk
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (map #(update % :features db/decode-pgarray #{}))
                 (remove #(contains? (:features %) "ephimeral/v2-migration"))
                 (take max-items)
                 (map :id)))]

    (l/dbg :hint "migrate:start")

    (let [sem    (ps/create :permits max-jobs)
          total  (get-total-teams pool)
          stats  (atom {:total/teams (min total max-items)})
          tpoint (dt/tpoint)]

      (add-watch stats :progress-report (report-progress-teams tpoint))

      (binding [feat/*stats* stats
                feat/*semaphore* sem
                feat/*skip-on-error* skip-on-error]
        (try
          (pu/with-open [scope (px/structured-task-scope :preset preset
                                                         :factory :virtual)]
            (run! (fn [team-id]
                    (l/trc :hint "scheduling task" :team-id (dm/str team-id))
                    (ps/acquire! sem)
                    (px/submit! scope (fn []
                                        (try
                                          (-> (assoc system ::db/rollback rollback)
                                              (feat/migrate-team! team-id))
                                          (catch Throwable cause
                                            (l/err :hint "unexpected error on processing team" :team-id (dm/str team-id) :cause cause))))))

                  (get-candidates))
            (p/await! scope))

          (print-stats!
           (-> (deref feat/*stats*)
               (dissoc :total/teams)
               (dissoc :current/graphics)
               (dissoc :current/components)
               (dissoc :current/files)))

          (catch Throwable cause
            (l/dbg :hint "migrate:error" :cause cause))

          (finally
            (let [elapsed (dt/format-duration (tpoint))]
              (l/dbg :hint "migrate:end" :elapsed elapsed))))))))
