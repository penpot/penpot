;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-temp
  (:require
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-create :as files.create]
   [app.rpc.commands.files-update :as-alias files.update]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.set :as set]))

;; --- MUTATION COMMAND: create-temp-file

(def ^:private schema:create-temp-file
  [:map {:title "create-temp-file"}
   [:name [:string {:max 250}]]
   [:project-id ::sm/uuid]
   [:id {:optional true} ::sm/uuid]
   [:is-shared ::sm/boolean]
   [:features ::cfeat/features]
   [:create-page ::sm/boolean]])

(sv/defmethod ::create-temp-file
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params schema:create-temp-file
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id project-id] :as params}]
  (projects/check-edition-permissions! conn profile-id project-id)
  (let [team (teams/get-team conn :profile-id profile-id :project-id project-id)
        ;; When we create files, we only need to respect the team
        ;; features, because some features can be enabled
        ;; globally, but the team is still not migrated properly.
        input-features
        (:features params #{})

        ;; If the imported project doesn't contain v2 we need to remove it
        team-features
        (cond-> (cfeat/get-team-enabled-features cf/flags team)
          (not (contains? input-features "components/v2"))
          (disj "components/v2"))

        ;; We also include all no migration features declared by
        ;; client; that enables the ability to enable a runtime
        ;; feature on frontend and make it permanent on file
        features
        (-> input-features
            (set/intersection cfeat/no-migration-features)
            (set/union team-features))

        params
        (-> params
            (assoc :profile-id profile-id)
            (assoc :deleted-at (dt/in-future {:days 1}))
            (assoc :features features))]

    (files.create/create-file cfg params)))

;; --- MUTATION COMMAND: update-temp-file


(def ^:private schema:update-temp-file
  [:map {:title "update-temp-file"}
   [:changes [:vector ::cpc/change]]
   [:revn [::sm/int {:min 0}]]
   [:session-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::update-temp-file
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params schema:update-temp-file}
  [cfg {:keys [::rpc/profile-id session-id id revn changes] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (db/insert! conn :file-change
                                {:id (uuid/next)
                                 :session-id session-id
                                 :profile-id profile-id
                                 :created-at (dt/now)
                                 :file-id id
                                 :revn revn
                                 :data nil
                                 :changes (blob/encode changes)})
                    (rph/with-meta (rph/wrap nil)
                      {::audit/replace-props {:file-id id
                                              :revn revn}}))))

;; --- MUTATION COMMAND: persist-temp-file

(defn persist-temp-file
  [{:keys [::db/conn] :as cfg} {:keys [id] :as params}]
  (let [file (files/get-file cfg id
                             :migrate? false
                             :lock-for-update? true)]

    (when (nil? (:deleted-at file))
      (ex/raise :type :validation
                :code :cant-persist-already-persisted-file))

    (let [changes (->> (db/cursor conn
                                  (sql/select :file-change {:file-id id}
                                              {:order-by [[:revn :asc]]})
                                  {:chunk-size 10})
                       (sequence (mapcat (comp blob/decode :changes))))

          file    (update file :data cpc/process-changes changes)

          file    (if (contains? (:features file) "fdata/objects-map")
                    (fdata/enable-objects-map file)
                    file)

          file    (if (contains? (:features file) "fdata/pointer-map")
                    (binding [pmap/*tracked* (pmap/create-tracked)]
                      (let [file (fdata/enable-pointer-map file)]
                        (fdata/persist-pointers! cfg id)
                        file))
                    file)]

      ;; Delete changes from the changes history
      (db/delete! conn :file-change {:file-id id})

      (db/update! conn :file
                  {:deleted-at nil
                   :revn 1
                   :data (blob/encode (:data file))}
                  {:id id})
      nil)))

(def ^:private schema:persist-temp-file
  [:map {:title "persist-temp-file"}
   [:id ::sm/uuid]])

(sv/defmethod ::persist-temp-file
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params schema:persist-temp-file}
  [cfg {:keys [::rpc/profile-id id] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (files/check-edition-permissions! conn profile-id id)
                    (persist-temp-file cfg params))))
