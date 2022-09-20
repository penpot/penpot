;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.projects
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.projects :as proj]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)


;; --- Mutation: Create Project

(declare create-project)
(declare create-project-role)
(declare create-team-project-profile)

(s/def ::team-id ::us/uuid)
(s/def ::create-project
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sv/defmethod ::create-project
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (let [project (create-project conn params)
          params  (assoc params
                         :project-id (:id project)
                         :role :owner)]
      (create-project-role conn params)
      (create-team-project-profile conn params)
      (assoc project :is-pinned true))))

(defn create-project
  [conn {:keys [id team-id name is-default] :as params}]
  (let [id         (or id (uuid/next))
        is-default (if (boolean? is-default) is-default false)]
    (db/insert! conn :project
                {:id id
                 :name name
                 :team-id team-id
                 :is-default is-default})))

(defn create-project-role
  [conn {:keys [project-id profile-id role]}]
  (let [params {:project-id project-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :project-profile-rel))))

;; TODO: pending to be refactored
(defn create-team-project-profile
  [conn {:keys [team-id project-id profile-id] :as params}]
  (db/insert! conn :team-project-profile-rel
              {:project-id project-id
               :profile-id profile-id
               :team-id team-id
               :is-pinned true}))


;; --- Mutation: Toggle Project Pin

(def ^:private
  sql:update-project-pin
  "insert into team_project_profile_rel (team_id, project_id, profile_id, is_pinned)
   values (?, ?, ?, ?)
       on conflict (team_id, project_id, profile_id)
       do update set is_pinned=?")

(s/def ::is-pinned ::us/boolean)
(s/def ::project-id ::us/uuid)

(s/def ::update-project-pin
  (s/keys :req-un [::profile-id ::id ::team-id ::is-pinned]))

(sv/defmethod ::update-project-pin
  [{:keys [pool] :as cfg} {:keys [id profile-id team-id is-pinned] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id id)
    (db/exec-one! conn [sql:update-project-pin team-id id profile-id is-pinned is-pinned])
    nil))


;; --- Mutation: Rename Project

(declare rename-project)

(s/def ::rename-project
  (s/keys :req-un [::profile-id ::name ::id]))

(sv/defmethod ::rename-project
  [{:keys [pool] :as cfg} {:keys [id profile-id name] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id id)
    (db/update! conn :project
                {:name name}
                {:id id})
    nil))

;; --- Mutation: Delete Project

(s/def ::delete-project
  (s/keys :req-un [::id ::profile-id]))

;; TODO: right now, we just don't allow delete default projects, in a
;; future we need to ensure raise a correct exception signaling that
;; this is not allowed.

(sv/defmethod ::delete-project
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id id)
    (db/update! conn :project
                {:deleted-at (dt/now)}
                {:id id :is-default false})
    nil))
