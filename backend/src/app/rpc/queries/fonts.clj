;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.fonts
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.projects :as projects]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: Team Font Variants

;; TODO: deprecated, should be removed on 1.7.x

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-font-variants
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-font-variants
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (db/query conn :team-font-variant
              {:team-id team-id
               :deleted-at nil})))

;; --- Query: Font Variants

(s/def ::file-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::font-variants
  (s/and
   (s/keys :req-un [::profile-id]
           :opt-un [::team-id
                    ::file-id
                    ::project-id])
   (fn [o]
     (or (contains? o :team-id)
         (contains? o :file-id)
         (contains? o :project-id)))))

(sv/defmethod ::font-variants
  [{:keys [pool] :as cfg} {:keys [profile-id team-id file-id project-id] :as params}]
  (with-open [conn (db/open pool)]
    (cond
      (uuid? team-id)
      (do
        (teams/check-read-permissions! conn profile-id team-id)
        (db/query conn :team-font-variant
                  {:team-id team-id
                   :deleted-at nil}))

      (uuid? project-id)
      (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})]
        (projects/check-read-permissions! conn profile-id project-id)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil}))

      (uuid? file-id)
      (let [file    (db/get-by-id conn :file file-id {:columns [:id :project-id]})
            project (db/get-by-id conn :project (:project-id file) {:columns [:id :team-id]})]
        (files/check-read-permissions! conn profile-id file-id)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil})))))
