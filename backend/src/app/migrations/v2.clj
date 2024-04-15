;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.migrations.v2
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.components-v2 :as feat]
   [app.setup :as setup]
   [app.util.time :as dt]))

(def ^:private sql:get-teams
  "SELECT id, features,
          row_number() OVER (ORDER BY created_at DESC) AS rown
    FROM team
   WHERE deleted_at IS NULL
     AND (not (features @> '{components/v2}') OR features IS NULL)
   ORDER BY created_at DESC")

(defn- get-teams
  [conn]
  (->> (db/cursor conn [sql:get-teams] {:chunk-size 1})
       (map feat/decode-row)))

(defn- migrate-teams
  [{:keys [::db/conn] :as system}]
  ;; Allow long running transaction for this connection
  (db/exec-one! conn ["SET LOCAL idle_in_transaction_session_timeout = 0"])

  ;; Do not allow other migration running in the same time
  (db/xact-lock! conn 0)

  ;; Run teams migration
  (run! (fn [{:keys [id rown]}]
          (try
            (-> (assoc system ::db/rollback false)
                (feat/migrate-team! id
                                    :rown rown
                                    :label "v2-migration"
                                    :validate? false
                                    :skip-on-graphics-error? true))
            (catch Throwable _
              (swap! feat/*stats* update :errors (fnil inc 0))
              (l/wrn :hint "error on migrating team (skiping)"))))
        (get-teams conn))

  (setup/set-prop! system :v2-migrated true))

(defn migrate
  [system]
  (let [tpoint    (dt/tpoint)
        stats     (atom {})
        migrated? (setup/get-prop system :v2-migrated false)]

    (when-not migrated?
      (l/inf :hint "v2 migration started")
      (try
        (binding [feat/*stats* stats]
          (db/tx-run! system migrate-teams))

        (let [stats   (deref stats)
              elapsed (dt/format-duration (tpoint))]
          (l/inf :hint "v2 migration finished"
                 :files (:processed-files stats)
                 :teams (:processed-teams stats)
                 :errors (:errors stats)
                 :elapsed elapsed))

        (catch Throwable cause
          (l/err :hint "error on aplying v2 migration" :cause cause))))))

(def ^:private required-services
  [[:app.main/assets :app.storage.s3/backend]
   [:app.main/assets :app.storage.fs/backend]
   :app.storage/storage
   :app.db/pool
   :app.setup/props
   :app.svgo/optimizer
   :app.metrics/metrics
   :app.migrations/migrations
   :app.http.client/client])

(defn -main
  [& _args]
  (try
    (let [config-var (requiring-resolve 'app.main/system-config)
          start-var  (requiring-resolve 'app.main/start-custom)
          stop-var   (requiring-resolve 'app.main/stop)
          system-var (requiring-resolve 'app.main/system)
          config     (select-keys @config-var required-services)]

      (start-var config)
      (migrate @system-var)
      (stop-var)
      (System/exit 0))
    (catch Throwable cause
      (ex/print-throwable cause)
      (flush)
      (System/exit -1))))
