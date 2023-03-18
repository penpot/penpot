;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-create
  (:require
   [app.common.data :as d]
   [app.common.files.features :as ffeat]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(defn create-file-role!
  [conn {:keys [file-id profile-id role]}]
  (let [params {:file-id file-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :file-profile-rel))))

(defn create-file
  [conn {:keys [id name project-id is-shared revn
                modified-at deleted-at create-page
                ignore-sync-until features]
         :or {is-shared false revn 0 create-page true}
         :as params}]

  (let [id       (or id (uuid/next))
        features (-> (into files/default-features features)
                     (files/check-features-compatibility!))

        pointers (atom {})
        data     (binding [pmap/*tracked* pointers
                           ffeat/*current* features
                           ffeat/*wrap-with-objects-map-fn* (if (features "storate/objects-map") omap/wrap identity)
                           ffeat/*wrap-with-pointer-map-fn* (if (features "storage/pointer-map") pmap/wrap identity)]
                   (if create-page
                     (ctf/make-file-data id)
                     (ctf/make-file-data id nil)))

        features (db/create-array conn "text" features)
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

    (files/decode-row file)))

(s/def ::create-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::files/name
                   ::files/project-id]
          :opt-un [::files/id
                   ::files/is-shared
                   ::files/features]))

(sv/defmethod ::create-file
  {::doc/added "1.17"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (projects/check-edition-permissions! conn profile-id project-id)
    (let [team-id (files/get-team-id conn project-id)
          params  (assoc params :profile-id profile-id)]

      (run! (partial quotes/check-quote! conn)
            (list {::quotes/id ::quotes/files-per-project
                   ::quotes/team-id team-id
                   ::quotes/profile-id profile-id
                   ::quotes/project-id project-id}))

      (-> (create-file conn params)
          (vary-meta assoc ::audit/props {:team-id team-id})))))
