;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.teams
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)

;; --- Mutation: Create Team

(declare create-team)
(declare create-team-profile)

(s/def ::create-team
  (s/keys :req-un [::profile-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-team
  [params]
  (db/with-atomic [conn db/pool]
    (p/let [team (create-team conn params)]
      (create-team-profile conn (assoc params :team-id (:id team)))
      team)))

(def ^:private sql:insert-team
  "insert into team (id, name, photo, is_default)
   values ($1, $2, '', $3)
   returning *")

(def ^:private sql:create-team-profile
  "insert into team_profile_rel (team_id, profile_id, is_owner, is_admin, can_edit)
   values ($1, $2, true, true, true)
   returning *")

(defn create-team
  [conn {:keys [id profile-id name default?] :as params}]
  (let [id (or id (uuid/next))
        default? (if (boolean? default?) default? false)]
    (db/query-one conn [sql:insert-team id name default?])))

(defn create-team-profile
  [conn {:keys [team-id profile-id] :as params}]
  (-> (db/query-one conn [sql:create-team-profile team-id profile-id])
      (p/then' su/constantly-nil)))



;; --- Mutation: Team Edition Permissions

(def ^:private sql:team-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    where tpr.profile_id = $1
      and tpr.team_id = $2")

(defn check-edition-permissions!
  [conn profile-id team-id]
  (-> (db/query-one conn [sql:team-permissions profile-id team-id])
      (p/then' (fn [row]
                 (when-not (or (:can-edit row)
                               (:is-admin row)
                               (:is-owner row))
                   (ex/raise :type :validation
                             :code :not-authorized))))))



