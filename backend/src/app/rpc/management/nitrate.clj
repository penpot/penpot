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
   [clojure.set :as set]
   [cuerdas.core :as str]))

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

;; ---- API: get-penpot-version

(def ^:private schema:get-penpot-version-result
  [:map [:version ::sm/text]])

(sv/defmethod ::get-penpot-version
  "Get the current Penpot version"
  {::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:get-penpot-version-result}
  [_cfg _params]
  {:version cf/version})

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

(defn notify-team-change
  [cfg team-id team-name organization-id organization-name notification]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               ;;TODO There is a bug on dashboard with teams notifications.
               ;;For now we send it to uuid/zero instead of team-id
               :topic uuid/zero
               :message {:type :team-org-change
                         :team-id team-id
                         :team-name team-name
                         :organization-id organization-id
                         :organization-name organization-name
                         :notification notification})))


(sv/defmethod ::notify-team-change
  "Notify to Penpot a team change from nitrate"
  {::doc/added "2.14"
   ::sm/params schema:notify-team-change
   ::rpc/auth false}
  [cfg {:keys [id organization-id organization-name]}]
  (notify-team-change cfg id nil organization-id organization-name nil))

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
  [cfg {:keys [profile-id organization-id]}]
  (quotes/check! cfg {::quotes/id ::quotes/teams-per-profile
                      ::quotes/profile-id profile-id})

  (let [features (-> (cfeat/get-enabled-features cf/flags)
                     (set/difference cfeat/frontend-only-features)
                     (set/difference cfeat/no-team-inheritable-features))
        params   {:profile-id profile-id
                  :name "Default"
                  :features features
                  :organization-id organization-id
                  :is-default true}
        team     (db/tx-run! cfg teams/create-team params)]
    (select-keys team [:id])))


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

;; ---- API: get-teams-summary

(def ^:private sql:get-teams-summary
  "SELECT t.id, t.name
     FROM team AS t
    WHERE t.id = ANY(?)
      AND t.deleted_at IS NULL;")

(def ^:private sql:get-files-count
  "SELECT COUNT(f.*) AS count
     FROM file AS f
     JOIN project AS p ON f.project_id = p.id
     JOIN team AS t ON t.id = p.team_id
    WHERE p.team_id = ANY(?)
      AND t.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND f.deleted_at IS NULL;")

(def ^:private schema:get-teams-summary-params
  [:map
   [:ids [:or ::sm/uuid [:vector ::sm/uuid]]]])

(def ^:private schema:get-teams-summary-result
  [:map
   [:teams [:vector [:map
                     [:id ::sm/uuid]
                     [:name ::sm/text]]]]
   [:num-files ::sm/int]])

(sv/defmethod ::get-teams-summary
  "Get summary information for a list of teams"
  {::doc/added "2.15"
   ::sm/params schema:get-teams-summary-params
   ::sm/result schema:get-teams-summary-result}
  [cfg {:keys [ids]}]
  (let [;; Handle one or multiple params
        ids (cond
              (uuid? ids)
              [ids]

              (and (vector? ids) (every? uuid? ids))
              ids

              :else
              [])]
    (db/run! cfg (fn [{:keys [::db/conn]}]
                   (let [ids-array     (db/create-array conn "uuid" ids)
                         teams         (db/exec! conn [sql:get-teams-summary ids-array])
                         files-count   (-> (db/exec-one! conn [sql:get-files-count ids-array]) :count)]
                     {:teams (mapv #(select-keys % [:id :name]) teams)
                      :num-files files-count})))))


;; ---- API: delete-teams-keeping-your-penpot-projects

(def ^:private sql:add-prefix-to-teams
  "UPDATE team
      SET name = ? || name
    WHERE id = ANY(?)
RETURNING id, name;")


(def ^:private schema:notify-org-deletion
  [:map
   [:org-name ::sm/text]
   [:teams [:vector ::sm/uuid]]])

(sv/defmethod ::notify-org-deletion
  "For a list of teams, rename them with the name of the deleted org, and notify
   of the deletion to the connected users"
  {::doc/added "2.15"
   ::sm/params schema:notify-org-deletion}
  [cfg {:keys [teams org-name]}]
  (when (seq teams)
    (let [cleaned-org-name (if org-name
                             (-> org-name
                                 str
                                 str/trim
                                 (str/replace #"[^\w\s\-_()]+" "")
                                 (str/replace #"\s+" " ")
                                 str/trim)
                             "")
          org-prefix       (str "[" cleaned-org-name "] ")]
      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn] :as cfg}]
         (let [ids-array (db/create-array conn "uuid" teams)
               ;; ---- Rename projects ----
               updated-teams (db/exec! conn [sql:add-prefix-to-teams org-prefix ids-array])]

           ;; ---- Notify users ----
           (doseq [team updated-teams]
             (notify-team-change cfg (:id team) (:name team) nil org-name "dashboard.org-deleted"))))))))

