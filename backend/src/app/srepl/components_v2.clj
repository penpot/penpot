;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.components-v2
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.libraries-helpers :as cflh]
   [app.common.files.migrations :as pmg]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as l]
   [app.common.pages.changes :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.svg :as csvg]
   [app.common.svg.shapes-builder :as sbuilder]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.components-v2 :as feat]
   [app.media :as media]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.media :as cmd.media]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(defn- report-progress-files
  [tpoint]
  (fn [_ _ oldv newv]
    (when (not= (:files/completed oldv)
                (:files/completed newv))
      (let [total     (:files/total newv)
            completed (:files/completed newv)
            progress  (/ (* completed 100.0) total)
            elapsed   (dt/format-duration (tpoint))]
        (l/trc :hint "progress"
               :completed (:files/completed newv)
               :progress (str (int progress) "%")
               :elapsed elapsed)))))

(defn- report-progress-teams
  [tpoint]
  (fn [_ _ oldv newv]
    (when (not= (:teams/completed oldv)
                (:teams/completed newv))
      (let [total     (:teams/total newv)
            completed (:teams/completed newv)
            progress  (/ (* completed 100.0) total)
            elapsed   (dt/format-duration (tpoint))]
        (l/trc :hint "progress"
               :completed (:teams/completed newv)
               :progress (str (int progress) "%")
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

(defn migrate-file!
  [{:keys [::db/pool] :as system} file-id]

  (l/dbg :hint "migrate:start")
  (let [tpoint (dt/tpoint)]
    (try
      (binding [feat/*stats*  (atom {})]
        (feat/migrate-file! system file-id)
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
  [{:keys [::db/pool] :as system} & {:keys [chunk-size max-jobs max-items start-at]
                                     :or {chunk-size 10 max-jobs 10 max-items Long/MAX_VALUE}}]
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
                feat/*semaphore* fsem]
        (try
          (pu/with-open [scope (px/structured-task-scope {:thread-factory :virtual})]

            (run! (fn [file-id]
                    (ps/acquire! feat/*semaphore*)
                    (px/submit! scope (partial feat/migrate-file! system file-id)))
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
  [{:keys [::db/pool] :as system} team-id]
  (l/dbg :hint "migrate:start")

  (let [total  (get-total-files pool :team-id team-id)
        stats  (atom {:files/total total})
        tpoint (dt/tpoint)]

    (add-watch stats :progress-report (report-progress-files tpoint))
    (try
      (binding [feat/*stats* stats]
        (feat/migrate-team! system team-id)
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

(defn migrate-teams!
  [{:keys [::db/pool] :as system} & {:keys [chunk-size max-jobs max-items start-at]
                                     :or {chunk-size 100
                                          max-jobs Integer/MAX_VALUE
                                          max-items Long/MAX_VALUE}}]
  (letfn [(get-chunk [cursor]
            (let [sql  (str/concat
                        "SELECT id, created_at FROM team "
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
    (let [sem    (ps/create :permits max-jobs)
          total  (get-total-teams pool)
          stats  (atom {:teams/total total})
          tpoint (dt/tpoint)]

      (add-watch stats :progress-report (report-progress-teams tpoint))

      (binding [feat/*stats* stats
                feat/*semaphore* sem]
        (try
          (pu/with-open [scope (px/structured-task-scope {:preset :shutdown-on-failure})]
            (run! (fn [team-id]

                    (prn "sem:pre:acquire" (.availablePermits sem))
                    (ps/acquire! feat/*semaphore*)
                    (prn "sem:post:acquire" (.availablePermits sem))

                    (px/submit! scope (partial feat/migrate-team! system team-id)))
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
