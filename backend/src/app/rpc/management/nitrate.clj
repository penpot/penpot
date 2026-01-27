;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.nitrate
  "Internal Nitrate HTTP RPC API. Provides authenticated access to
  organization management and token validation endpoints."
  (:require
   [app.common.schema :as sm]
   [app.common.types.profile :refer [schema:profile]]
   [app.common.types.team :refer [schema:team]]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]))

;; ---- API: authenticate

(sv/defmethod ::authenticate
  "Authenticate the current user"
  {::doc/added "2.14"
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
  "Notify to Penpot a team change from nitrate"
  {::doc/added "2.14"
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







