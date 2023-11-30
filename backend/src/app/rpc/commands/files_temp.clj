;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-temp
  (:require
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as fch]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-create :as files.create]
   [app.rpc.commands.files-update :as-alias files.update]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]))


;; --- MUTATION COMMAND: create-temp-file

(s/def ::create-page ::us/boolean)

(s/def ::create-temp-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::files/name
                   ::files/project-id]
          :opt-un [::files/id
                   ::files/is-shared
                   ::files/features
                   ::create-page]))

(sv/defmethod ::create-temp-file
  {::doc/added "1.17"
   ::doc/module :files}
  [cfg {:keys [::rpc/profile-id project-id] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (projects/check-edition-permissions! conn profile-id project-id)
                    (let [team     (teams/get-team conn
                                                   :profile-id profile-id
                                                   :project-id project-id)

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
                                       (assoc :deleted-at (dt/in-future {:days 1}))
                                       (assoc :features features))]

                      (files.create/create-file cfg params)))))

;; --- MUTATION COMMAND: update-temp-file

(s/def ::update-temp-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::files.update/changes
                   ::files.update/revn
                   ::files.update/session-id
                   ::files/id]))

(sv/defmethod ::update-temp-file
  {::doc/added "1.17"
   ::doc/module :files}
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
                    nil)))

;; --- MUTATION COMMAND: persist-temp-file

(defn persist-temp-file
  [conn {:keys [id] :as params}]
  (let [file (db/get-by-id conn :file id)
        revs (db/query conn :file-change
                       {:file-id id}
                       {:order-by [[:revn :asc]]})
        revn (count revs)]

    (when (nil? (:deleted-at file))
      (ex/raise :type :validation
                :code :cant-persist-already-persisted-file))


    (let [data
          (->> revs
               (mapcat #(->> % :changes blob/decode))
               (fch/process-changes (blob/decode (:data file))))]
      (db/update! conn :file
                  {:deleted-at nil
                   :revn revn
                   :data (blob/encode data)}
                  {:id id}))
    nil))

(s/def ::persist-temp-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::files/id]))

(sv/defmethod ::persist-temp-file
  {::doc/added "1.17"
   ::doc/module :files}
  [cfg {:keys [::rpc/profile-id id] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (files/check-edition-permissions! conn profile-id id)
                    (persist-temp-file conn params))))
