;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-create
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.common.types.file :as ctf]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.set :as set]))

(defn create-file-role!
  [conn {:keys [file-id profile-id role]}]
  (let [params {:file-id file-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :file-profile-rel))))

(defn create-file
  [{:keys [::db/conn] :as cfg}
   {:keys [id name project-id is-shared revn
           modified-at deleted-at create-page page-id
           ignore-sync-until features]
    :or {is-shared false revn 0 create-page true}
    :as params}]

  (assert (db/connection? conn) "expected a valid connection")

  (binding [pmap/*tracked* (pmap/create-tracked)
            cfeat/*current* features]
    (let [file (ctf/make-file {:id id
                               :project-id project-id
                               :name name
                               :revn revn
                               :is-shared is-shared
                               :features features
                               :ignore-sync-until ignore-sync-until
                               :modified-at modified-at
                               :deleted-at deleted-at}
                              {:create-page create-page
                               :page-id page-id})
          file (-> (bfc/insert-file! cfg file)
                   (bfc/decode-row))]

      (->> (assoc params :file-id (:id file) :role :owner)
           (create-file-role! conn))

      (db/update! conn :project
                  {:modified-at (dt/now)}
                  {:id project-id})

      file)))

(def ^:private schema:create-file
  [:map {:title "create-file"}
   [:name [:string {:max 250}]]
   [:project-id ::sm/uuid]
   [:id {:optional true} ::sm/uuid]
   [:is-shared {:optional true} ::sm/boolean]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::create-file
  {::doc/added "1.17"
   ::doc/module :files
   ::webhooks/event? true
   ::sm/params schema:create-file
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id project-id] :as params}]
  (projects/check-edition-permissions! conn profile-id project-id)
  (let [team     (teams/get-team conn
                                 :profile-id profile-id
                                 :project-id project-id)
        team-id  (:id team)

        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params)))

        ;; We also include all no migration features declared by
        ;; client; that enables the ability to enable a runtime
        ;; feature on frontend and make it permanent on file
        features (-> (:features params #{})
                     (set/intersection cfeat/no-migration-features)
                     (set/difference cfeat/frontend-only-features)
                     (set/union features))

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features features))]

    (quotes/check! cfg {::quotes/id ::quotes/files-per-project
                        ::quotes/team-id team-id
                        ::quotes/profile-id profile-id
                        ::quotes/project-id project-id})

    ;; FIXME: IMPORTANT: this code can have race conditions, because
    ;; we have no locks for updating team so, creating two files
    ;; concurrently can lead to lost team features updating

    (when-let [features (-> features
                            (set/difference (:features team))
                            (set/difference cfeat/no-team-inheritable-features)
                            (not-empty))]
      (let [features (->> features
                          (set/union (:features team))
                          (db/create-array conn "text"))]
        (db/update! conn :team
                    {:features features}
                    {:id (:id team)}
                    {::db/return-keys false})))

    (-> (create-file cfg params)
        (vary-meta assoc ::audit/props {:team-id team-id}))))
