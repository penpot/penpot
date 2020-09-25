;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.services.mutations.projects
  (:require
   [clojure.spec.alpha :as s]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.services.mutations :as sm]
   [app.tasks :as tasks]
   [app.util.blob :as blob]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)

;; --- Permissions Checks

(def ^:private sql:project-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    where p.id = ?
      and tpr.profile_id = ?
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    where ppr.project_id = ?
      and ppr.profile_id = ?")

(defn check-edition-permissions!
  [conn profile-id project-id]
  (let [rows (db/exec! conn [sql:project-permissions
                             project-id profile-id
                             project-id profile-id])]
    (when (empty? rows)
      (ex/raise :type :not-found))
    (when-not (or (some :can-edit rows)
                  (some :is-admin rows)
                  (some :is-owner rows))
      (ex/raise :type :validation
                :code :not-authorized))))


;; --- Mutation: Create Project

(declare create-project)
(declare create-project-profile)
(declare create-team-project-profile)

(s/def ::team-id ::us/uuid)
(s/def ::create-project
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-project
  [params]
  (db/with-atomic [conn db/pool]
    (let [proj   (create-project conn params)
          params (assoc params :project-id (:id proj))]
      (create-project-profile conn params)
      (create-team-project-profile conn params)
      (assoc proj :is-pinned true))))

(defn create-project
  [conn {:keys [id profile-id team-id name default?] :as params}]
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

(s/def ::is-pinned ::us/boolean)
(s/def ::toggle-project-pin
  (s/keys :req-un [::profile-id ::id ::team-id ::is-pinned]))

(sm/defmutation ::toggle-project-pin
  [{:keys [id profile-id team-id is-pinned] :as params}]
  (db/with-atomic [conn db/pool]
    (db/update! conn :team-project-profile-rel
                {:is-pinned is-pinned}
                {:profile-id profile-id
                 :project-id id
                 :team-id team-id})
    nil))

;; --- Mutation: Rename Project

(declare rename-project)

(s/def ::rename-project
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-project
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [project (db/get-by-id conn :project id {:for-update true})]
      (check-edition-permissions! conn profile-id id)
      (db/update! conn :project
                  {:name name}
                  {:id id}))))

;; --- Mutation: Delete Project

(declare mark-project-deleted)

(s/def ::delete-project
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-project
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)

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
  [conn {:keys [id profile-id] :as params}]
  (db/exec! conn [sql:mark-project-deleted id])
  nil)
