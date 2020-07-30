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
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.util.blob :as blob]))

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
    (let [team (create-team conn params)]
      (create-team-profile conn (assoc params :team-id (:id team)))
      team)))

(defn create-team
  [conn {:keys [id profile-id name default?] :as params}]
  (let [id (or id (uuid/next))
        default? (if (boolean? default?) default? false)]
    (db/insert! conn :team
                {:id id
                 :name name
                 :photo ""
                 :is-default default?})))

(defn create-team-profile
  [conn {:keys [team-id profile-id] :as params}]
  (db/insert! conn :team-profile-rel
              {:team-id team-id
               :profile-id profile-id
               :is-owner true
               :is-admin true
               :can-edit true}))

;; --- Mutation: Team Edition Permissions

(def ^:private sql:team-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    where tpr.profile_id = ?
      and tpr.team_id = ?")

(defn check-edition-permissions!
  [conn profile-id team-id]
  (let [row (db/exec-one! conn [sql:team-permissions profile-id team-id])]
    (when-not (or (= team-id uuid/zero)
                  (:can-edit row)
                  (:is-admin row)
                  (:is-owner row))
      (ex/raise :type :validation
                :code :not-authorized))))
