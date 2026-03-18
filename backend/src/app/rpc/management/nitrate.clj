;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.nitrate
  "Internal Nitrate HTTP RPC API. Provides authenticated access to
  organization management and token validation endpoints."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
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
   [app.rpc.commands.management :as management]
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

(def ^:private sql:get-projects-and-default-teams
  "Get projects from specified teams along with their team owner's default team information.
   This query:
   - Selects projects (id, team_id, name) from teams in the provided list
   - Gets the profile_id of each team owner
   - Gets the default_team_id where projects should be moved
   - Only includes teams where the user is owner
   - Only includes projects that contain at least one non-deleted file
   - Excludes deleted projects and teams"
  "SELECT p.id AS project_id,
          p.team_id AS source_team_id,
          p.name AS project_name,
          tpr.profile_id,
          pr.default_team_id
     FROM project AS p
     JOIN team AS tm ON p.team_id = tm.id
     JOIN team_profile_rel AS tpr ON tm.id = tpr.team_id
     JOIN profile AS pr ON tpr.profile_id = pr.id
    WHERE p.team_id = ANY(?)
      AND p.deleted_at IS NULL
      AND tm.deleted_at IS NULL
      AND tpr.is_owner IS TRUE
      AND EXISTS (SELECT 1 FROM file f WHERE f.project_id = p.id AND f.deleted_at IS NULL);")

(def ^:private sql:delete-teams
  "UPDATE team SET deleted_at = ? WHERE id = ANY(?)")

(def ^:private schema:delete-teams-keeping-your-penpot-projects
  [:map
   [:org-name ::sm/text]
   [:teams [:vector [:map
                     [:id ::sm/uuid]
                     [:is-your-penpot ::sm/boolean]]]]])

(def ^:private schema:delete-teams-error
  [:map
   [:error ::sm/keyword]
   [:message ::sm/text]
   [:cause ::sm/text]
   [:project-id {:optional true} ::sm/uuid]
   [:project-name {:optional true} ::sm/text]
   [:team-id {:optional true} ::sm/uuid]
   [:phase {:optional true} [:enum :move-projects :delete-teams]]])

(def ^:private schema:delete-teams-result
  [:or [:= nil] schema:delete-teams-error])

(defn- ^:private clean-org-name
  "Clean and sanitize organization name to remove emojis, special characters,
   and prevent potential injections. Only allows alphanumeric characters,
   spaces, hyphens, underscores, and parentheses."
  [org-name]
  (when org-name
    (-> org-name
        str
        str/trim
        (str/replace #"[^\w\s\-_()]+" "")
        (str/replace #"\s+" " ")
        str/trim)))

(sv/defmethod ::delete-teams-keeping-your-penpot-projects
  "For a list of teams, move the projects of your-penpot teams to the
   default team of each team owner, then delete all provided teams."
  {::doc/added "2.15"
   ::sm/params schema:delete-teams-keeping-your-penpot-projects
   ::sm/result schema:delete-teams-result}
  [cfg {:keys [teams org-name ::rpc/request-at]}]

  (let [your-penpot-team-ids (into [] (comp (filter :is-your-penpot) d/xf:map-id) teams)
        all-team-ids         (into [] d/xf:map-id teams)
        cleaned-org-name     (clean-org-name org-name)
        org-prefix           (if (str/empty? cleaned-org-name)
                               "imported: "
                               (str cleaned-org-name " imported: "))]

    (when (seq all-team-ids)

      (db/tx-run! cfg
                  (fn [{:keys [::db/conn] :as cfg}]

                    ;; ---- Move projects ----
                    (when (seq your-penpot-team-ids)
                      (let [ids-array (db/create-array conn "uuid" your-penpot-team-ids)
                            projects  (db/exec! conn [sql:get-projects-and-default-teams ids-array])]

                        (doseq [{:keys [default-team-id profile-id project-id project-name source-team-id]} projects
                                :when default-team-id]

                          (try
                            (management/move-project cfg {:profile-id profile-id
                                                          :team-id default-team-id
                                                          :project-id project-id})

                            (db/update! conn :project
                                        {:is-default false
                                         :name (str org-prefix project-name)}
                                        {:id project-id})

                            (catch Throwable cause
                              (ex/raise :type :internal
                                        :code :nitrate-project-move-failed
                                        :context {:project-id project-id
                                                  :project-name project-name
                                                  :team-id source-team-id}
                                        :cause cause))))))

                    ;; ---- Delete teams ----
                    (try
                      (let [team-ids-array (db/create-array conn "uuid" all-team-ids)]
                        (db/exec-one! conn [sql:delete-teams request-at team-ids-array]))
                      (catch Throwable cause
                        (ex/raise :type :internal
                                  :code :nitrate-team-deletion-failed
                                  :context {:team-ids all-team-ids}
                                  :cause cause))))))

    nil))

