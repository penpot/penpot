;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.nitrate
  "Internal Nitrate HTTP RPC API. Provides authenticated access to
  organization management and token validation endpoints."
  (:require
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.common.types.profile :refer [schema:profile, schema:basic-profile]]
   [app.common.types.team :refer [schema:team]]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as doc]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]
   [clojure.set :as set]))

;; ---- API: authenticate

(sv/defmethod ::authenticate
  "Authenticate the current user"
  {::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:profile}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (let [profile (profile/get-profile cfg profile-id)]
    {:id (get profile :id)
     :name (get profile :fullname)
     :email (get profile :email)
     :photo-url (files/resolve-public-uri (get profile :photo-id))}))

;; ---- API: get-teams

(def ^:private sql:get-teams
  "SELECT t.*
     FROM team AS t
     JOIN team_profile_rel AS tpr ON t.id = tpr.team_id
    WHERE tpr.profile_id = ?
      AND tpr.is_owner IS TRUE
      AND t.is_default IS FALSE
      AND t.deleted_at IS NULL;")

(def ^:private schema:get-teams-result
  [:vector schema:team])

(sv/defmethod ::get-teams
  "List teams for which current user is owner"
  {::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:get-teams-result}
  [cfg {:keys [::rpc/profile-id]}]
  (let [current-user-id (-> (profile/get-profile cfg profile-id) :id)]
    (->> (db/exec! cfg [sql:get-teams current-user-id])
         (map #(select-keys % [:id :name])))))

;; ---- API: notify-team-change

(def ^:private schema:notify-team-change
  [:map
   [:id ::sm/uuid]
   [:organization-id ::sm/uuid]
   [:organization-name ::sm/text]])

(sv/defmethod ::notify-team-change
  "Notify to Penpot a team change from nitrate"
  {::doc/added "2.14"
   ::sm/params schema:notify-team-change
   ::rpc/auth false}
  [cfg {:keys [id organization-id organization-name]}]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               ;;TODO There is a bug on dashboard with teams notifications.
               ;;For now we send it to uuid/zero instead of team-id
               :topic uuid/zero
               :message {:type :team-org-change
                         :team-id id
                         :organization-id organization-id
                         :organization-name organization-name})))

;; ---- API: notify-user-added-to-organization

(def ^:private schema:notify-user-added-to-organization
  [:map
   [:profile-id ::sm/uuid]
   [:organization-id ::sm/uuid]
   [:role ::sm/text]])

(sv/defmethod ::notify-user-added-to-organization
  "Notify to Penpot that an user has joined an org from nitrate"
  {::doc/added "2.14"
   ::sm/params schema:notify-user-added-to-organization
   ::rpc/auth false}
  [cfg {:keys [profile-id]}]
  (when (contains? cf/flags :nitrate)
    (quotes/check! cfg {::quotes/id ::quotes/teams-per-profile
                        ::quotes/profile-id profile-id})

    (let [features (-> (cfeat/get-enabled-features cf/flags)
                       (set/difference cfeat/frontend-only-features)
                       (set/difference cfeat/no-team-inheritable-features))
          ;; TODO Choose a better name and manage a default-in-org setting
          params   {:profile-id profile-id
                    :features features
                    :name "NitrateDefault"}
          team     (db/tx-run! cfg teams/create-team params)]
      (select-keys team [:id]))))


;; ---- API: get-managed-profiles

(def ^:private sql:get-managed-profiles
  "SELECT DISTINCT p.id, p.fullname as name, p.email
     FROM profile p
     JOIN team_profile_rel tpr_member
       ON tpr_member.profile_id = p.id
    WHERE p.id <> ?
      AND EXISTS (
        SELECT 1
          FROM team_profile_rel tpr_owner
          JOIN team t
            ON t.id = tpr_owner.team_id
         WHERE tpr_owner.profile_id = ?
           AND tpr_owner.team_id = tpr_member.team_id
           AND tpr_owner.is_owner IS TRUE
           AND t.is_default IS FALSE
           AND t.deleted_at IS NULL);")

(def schema:managed-profile-result
  [:vector schema:basic-profile])

(sv/defmethod ::get-managed-profiles
  "List profiles that belong to teams for which current user is owner"
  {::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:managed-profile-result}
  [cfg {:keys [::rpc/profile-id]}]
  (let [current-user-id (-> (profile/get-profile cfg profile-id) :id)]
    (db/exec! cfg [sql:get-managed-profiles current-user-id current-user-id])))

