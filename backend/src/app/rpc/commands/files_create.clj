;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-create
  (:require
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
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
           modified-at deleted-at create-page
           ignore-sync-until features]
    :or {is-shared false revn 0 create-page true}
    :as params}]

  (let [id       (or id (uuid/next))

        pointers (atom {})
        data     (binding [pmap/*tracked* pointers
                           cfeat/*current* features
                           cfeat/*wrap-with-objects-map-fn* (if (features "fdata/objects-map") omap/wrap identity)
                           cfeat/*wrap-with-pointer-map-fn* (if (features "fdata/pointer-map") pmap/wrap identity)]
                   (if create-page
                     (ctf/make-file-data id)
                     (ctf/make-file-data id nil)))

        features (->> (set/difference features cfeat/frontend-only-features)
                      (db/create-array conn "text"))

        file     (db/insert! conn :file
                             (d/without-nils
                              {:id id
                               :project-id project-id
                               :name name
                               :revn revn
                               :is-shared is-shared
                               :data (blob/encode data)
                               :features features
                               :ignore-sync-until ignore-sync-until
                               :modified-at modified-at
                               :deleted-at deleted-at}))]

    (binding [pmap/*tracked* pointers]
      (files/persist-pointers! conn id))

    (->> (assoc params :file-id id :role :owner)
         (create-file-role! conn))

    (db/update! conn :project
      {:modified-at (dt/now)}
      {:id project-id})

    (files/decode-row file)))

(def ^:private schema:create-file
  [:map {:title "create-file"}
   [:name :string]
   [:project-id ::sm/uuid]
   [:id {:optional true} ::sm/uuid]
   [:is-shared {:optional true} :boolean]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::create-file
  {::doc/added "1.17"
   ::doc/module :files
   ::webhooks/event? true
   ::sm/params schema:create-file}
  [cfg {:keys [::rpc/profile-id project-id] :as params}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (projects/check-edition-permissions! conn profile-id project-id)
                (let [team     (teams/get-team cfg
                                               :profile-id profile-id
                                               :project-id project-id)
                      team-id  (:id team)

                      ;; When we create files, we only need to respect the team
                      ;; features, because some features can be enabled
                      ;; globally, but the team is still not migrated properly.
                      features (-> (cfeat/get-team-enabled-features cf/flags team)
                                   (cfeat/check-client-features! (:features params)))

                      ;; We also include all no migration features declared by
                      ;; client; that enables the ability to enable a runtime
                      ;; feature on frontend and make it permanent on file
                      features (-> (:features params #{})
                                   (set/intersection cfeat/no-migration-features)
                                   (set/union features))

                      params   (-> params
                                   (assoc :profile-id profile-id)
                                   (assoc :features features))]

                  (run! (partial quotes/check-quote! conn)
                        (list {::quotes/id ::quotes/files-per-project
                               ::quotes/team-id team-id
                               ::quotes/profile-id profile-id
                               ::quotes/project-id project-id}))

                  ;; When newly computed features does not match exactly with
                  ;; the features defined on team row, we update it.
                  (when (not= features (:features team))
                    (let [features (db/create-array conn "text" features)]
                      (db/update! conn :team
                                  {:features features}
                                  {:id team-id})))

                  (-> (create-file cfg params)
                      (vary-meta assoc ::audit/props {:team-id team-id}))))))
