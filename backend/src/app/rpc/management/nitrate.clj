;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.nitrate
  "Internal Nitrate HTTP API.
       Provides authenticated access to organization management and token validation endpoints.
       All requests must include a valid shared key token in the `x-shared-key` header, and
       a cookie `auth-token` with the user token.
       They will return `401 Unauthorized` if the shared key or user token are invalid."
  (:require
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as doc]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

;; ---- API: authenticate
(def ^:private schema:profile
  [:map
   [:id ::sm/uuid]
   [:name :string]
   [:email :string]
   [:photo-url :string]])

(sv/defmethod ::authenticate
  "Authenticate an user
     @api GET /authenticate
     @returns
       200 OK: Returns the authenticated user."
  {::doc/added "2.12"
   ::sm/result schema:profile}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (let [profile    (profile/get-profile cfg profile-id)]
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
      AND tpr.is_owner = 't'
      AND t.is_default = 'f'
      AND t.deleted_at is null;")

(def ^:private schema:team
  [:map
   [:id ::sm/uuid]
   [:name :string]])

(def ^:private schema:get-teams-result
  [:vector schema:team])

(sv/defmethod ::get-teams
  "List teams for which current user is owner.
     @api GET /get-teams
     @returns
       200 OK: Returns the list of teams for the user."
  {::doc/added "2.12"
   ::sm/result schema:get-teams-result}
  [cfg {:keys [::rpc/profile-id]}]
  (when (contains? cf/flags :nitrate)
    (let [current-user-id (-> (profile/get-profile cfg profile-id) :id)]
      (->> (db/exec! cfg [sql:get-teams current-user-id])
           (map #(select-keys % [:id :name]))))))

;; ---- API: notify-team-change

(def ^:private schema:notify-team-change
  [:map
   [:id ::sm/uuid]
   [:organization-id ::sm/text]])


(sv/defmethod ::notify-team-change
  "Notify to Penpot a team change from nitrate
     @api POST /notify-team-change
     @returns
       200 OK"
  {::doc/added "2.12"
   ::sm/params schema:notify-team-change
   ::rpc/auth false}
  [cfg {:keys [id organization-id organization-name]}]
  (when (contains? cf/flags :nitrate)
    (let [msgbus (::mbus/msgbus cfg)]
      (mbus/pub! msgbus
                 ;;TODO There is a bug on dashboard with teams notifications.
                 ;;For now we send it to uuid/zero instead of team-id
                 :topic uuid/zero
                 :message {:type :team-org-change
                           :team-id id
                           :organization-id organization-id
                           :organization-name organization-name}))))







