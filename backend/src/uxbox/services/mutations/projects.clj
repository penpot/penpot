;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.tasks :as tasks]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]))

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
    where p.id = $1
      and tpr.profile_id = $2
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    where ppr.project_id = $1
      and ppr.profile_id = $2")

(defn check-edition-permissions!
  [conn profile-id project-id]
  (-> (db/query conn [sql:project-permissions project-id profile-id])
      (p/then' seq)
      (p/then' su/raise-not-found-if-nil)
      (p/then' (fn [rows]
                 (when-not (or (some :can-edit rows)
                               (some :is-admin rows)
                               (some :is-owner rows))
                   (ex/raise :type :validation
                             :code :not-authorized))))))



;; --- Mutation: Create Project

(declare create-project)
(declare create-project-profile)

(s/def ::team-id ::us/uuid)
(s/def ::create-project
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-project
  [params]
  (db/with-atomic [conn db/pool]
    (p/let [proj (create-project conn params)]
      (create-project-profile conn (assoc params :project-id (:id proj)))
      proj)))

(def ^:private sql:insert-project
  "insert into project (id, team_id, name, is_default)
   values ($1, $2, $3, $4)
   returning *")

(defn create-project
  [conn {:keys [id profile-id team-id name default?] :as params}]
  (let [id (or id (uuid/next))
        default? (if (boolean? default?) default? false)]
    (db/query-one conn [sql:insert-project id team-id name default?])))

(def ^:private sql:create-project-profile
  "insert into project_profile_rel (project_id, profile_id, is_owner, is_admin, can_edit)
   values ($1, $2, true, true, true)
   returning *")

(defn create-project-profile
  [conn {:keys [project-id profile-id] :as params}]
  (-> (db/query-one conn [sql:create-project-profile project-id profile-id])
      (p/then' su/constantly-nil)))



;; --- Mutation: Rename Project

(declare rename-project)

(s/def ::rename-project
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-project
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)
    (rename-project conn params)))

(def ^:private sql:rename-project
  "update project
      set name = $2
    where id = $1
      and deleted_at is null
     returning *")

(defn rename-project
  [conn {:keys [id name] :as params}]
  (db/query-one conn [sql:rename-project id name]))



;; --- Mutation: Delete Project

(declare mark-project-deleted)

(s/def ::delete-project
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-project
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (tasks/schedule! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :project}})

    (mark-project-deleted conn params)))

(def ^:private sql:mark-project-deleted
  "update project
      set deleted_at = clock_timestamp()
    where id = $1
   returning id")

(defn mark-project-deleted
  [conn {:keys [id profile-id] :as params}]
  (-> (db/query-one conn [sql:mark-project-deleted id])
      (p/then' su/constantly-nil)))
