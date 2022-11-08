;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files.create
  (:require
   [app.common.data :as d]
   [app.common.files.features :as ffeat]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.projects :as proj]
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
  [conn {:keys [id name project-id is-shared data revn
                modified-at deleted-at
                ignore-sync-until features]
         :or {is-shared false revn 0}
         :as params}]
  (let [id       (or id (:id data) (uuid/next))
        features (-> (into files/default-features features)
                     (files/check-features-compatibility!))

        data     (or data
                     (binding [ffeat/*current* features
                               ffeat/*wrap-with-objects-map-fn* (if (features "storate/objects-map") omap/wrap identity)
                               ffeat/*wrap-with-pointer-map-fn* (if (features "storage/pointer-map") pmap/wrap identity)]
                       (ctf/make-file-data id)))

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

    (->> (assoc params :file-id id :role :owner)
         (create-file-role! conn))

    (files/decode-row file)))

(s/def ::create-file
  (s/keys :req-un [::files/profile-id
                   ::files/name
                   ::files/project-id]
          :opt-un [::files/id
                   ::files/is-shared
                   ::files/features]))

(sv/defmethod ::create-file
  {::doc/added "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id project-id)
    (let [team-id (files/get-team-id conn project-id)]
      (-> (create-file conn params)
          (vary-meta assoc ::audit/props {:team-id team-id})))))

