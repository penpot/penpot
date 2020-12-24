;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.mutations.projects
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.rpc.queries.projects :as proj]
   [app.tasks :as tasks]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)


;; --- Mutation: Create Project

(declare create-project)
(declare create-project-profile)
(declare create-team-project-profile)

(s/def ::team-id ::us/uuid)
(s/def ::create-project
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sv/defmethod ::create-project
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (let [proj   (create-project conn params)
          params (assoc params :project-id (:id proj))]
      (create-project-profile conn params)
      (create-team-project-profile conn params)
      (assoc proj :is-pinned true))))

(defn create-project
  [conn {:keys [id team-id name default?] :as params}]
  (let [id (or id (uuid/next))
        default? (if (boolean? default?) default? false)]
    (db/insert! conn :project
                {:id id
                 :team-id team-id
                 :name name
                 :is-default default?})))

(defn create-project-profile
  [conn {:keys [project-id profile-id] :as params}]
  (db/insert! conn :project-profile-rel
              {:project-id project-id
               :profile-id profile-id
               :is-owner true
               :is-admin true
               :can-edit true}))

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
                {:id id})))

;; --- Mutation: Delete Project

(declare mark-project-deleted)

(s/def ::delete-project
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::delete-project
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (tasks/submit! conn {:name "delete-object"
                         :delay cfg/default-deletion-delay
                         :props {:id id :type :project}})

    (mark-project-deleted conn params)))

(def ^:private sql:mark-project-deleted
  "update project
      set deleted_at = clock_timestamp()
    where id = ?
   returning id")

(defn mark-project-deleted
  [conn {:keys [id] :as params}]
  (db/exec! conn [sql:mark-project-deleted id])
  nil)
