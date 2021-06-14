;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.viewer
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: Viewer Bundle (by Page ID)

(declare check-shared-token!)
(declare retrieve-shared-token)

(def ^:private
  sql:project
  "select p.id, p.name, p.team_id
     from project as p
    where p.id = ?
      and p.deleted_at is null")

(defn- retrieve-project
  [conn id]
  (db/exec-one! conn [sql:project id]))

(s/def ::id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::token ::us/string)

(s/def ::viewer-bundle
  (s/keys :req-un [::file-id ::page-id]
          :opt-un [::profile-id ::token]))

(sv/defmethod ::viewer-bundle {:auth false}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id page-id token] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg     (assoc cfg :conn conn)
          file    (files/retrieve-file cfg file-id)
          project (retrieve-project conn (:project-id file))
          page    (get-in file [:data :pages-index page-id])
          file    (merge (dissoc file :data)
                         (select-keys (:data file) [:colors :media :typographies]))
          libs    (files/retrieve-file-libraries cfg false file-id)
          users   (teams/retrieve-users conn (:team-id project))

          fonts   (db/query conn :team-font-variant
                            {:team-id (:team-id project)
                             :deleted-at nil})

          bundle  {:file file
                   :page page
                   :users users
                   :fonts fonts
                   :project project
                   :libraries libs}]

      (if (string? token)
        (do
          (check-shared-token! conn file-id page-id token)
          (assoc bundle :token token))
        (let [stoken (retrieve-shared-token conn file-id page-id)]
          (files/check-read-permissions! conn profile-id file-id)
          (assoc bundle :token (:token stoken)))))))

(defn check-shared-token!
  [conn file-id page-id token]
  (let [sql "select exists(select 1 from file_share_token where file_id=? and page_id=? and token=?) as exists"]
    (when-not (:exists (db/exec-one! conn [sql file-id page-id token]))
      (ex/raise :type :not-found
                :code :object-not-found))))

(defn retrieve-shared-token
  [conn file-id page-id]
  (let [sql "select * from file_share_token where file_id=? and page_id=?"]
    (db/exec-one! conn [sql file-id page-id])))



