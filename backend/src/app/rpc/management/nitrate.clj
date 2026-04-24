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
   [app.common.schema :as sm]
   [app.common.types.organization :refer [schema:team-with-organization]]
   [app.common.types.profile :refer [schema:profile, schema:basic-profile]]
   [app.common.types.team :refer [schema:team]]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.nitrate :as cnit]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.commands.teams-invitations :as ti]
   [app.rpc.doc :as doc]
   [app.rpc.notifications :as notifications]
   [app.storage :as sto]
   [app.util.services :as sv]))


(defn- profile-to-map [profile]
  {:id            (:id profile)
   :name          (:fullname profile)
   :email         (:email profile)
   :photo-url     (files/resolve-public-uri (get profile :photo-id))})

;; ---- API: authenticate

(sv/defmethod ::authenticate
  "Authenticate the current user"
  {::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:profile}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (let [profile            (profile/get-profile cfg profile-id)]
    (-> (profile-to-map profile)
        (assoc :theme (:theme profile)))))

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

;; ---- API: upload-org-logo

(def ^:private schema:upload-org-logo
  [:map
   [:content media/schema:upload]
   [:organization-id ::sm/uuid]
   [:previous-id {:optional true} ::sm/uuid]])

(def ^:private schema:upload-org-logo-result
  [:map [:id ::sm/uuid]])

(sv/defmethod ::upload-org-logo
  "Store an organization logo in penpot storage and return its ID.
  Accepts an optional previous-id to mark the old logo for garbage
  collection when replacing an existing one."
  {::doc/added "2.17"
   ::sm/params schema:upload-org-logo
   ::sm/result schema:upload-org-logo-result}
  [{:keys [::sto/storage]} {:keys [content organization-id previous-id]}]
  (when previous-id
    (sto/touch-object! storage previous-id))
  (let [hash (sto/calculate-hash (:path content))
        data (-> (sto/content (:path content))
                 (sto/wrap-with-hash hash))
        obj  (sto/put-object! storage {::sto/content      data
                                       ::sto/deduplicate? true
                                       :bucket            "organization"
                                       :content-type      (:mtype content)
                                       :organization-id   organization-id})]
    {:id (:id obj)}))

;; ---- API: notify-team-change

(sv/defmethod ::notify-team-change
  "Notify to Penpot a team change from nitrate"
  {::doc/added "2.14"
   ::sm/params schema:team-with-organization
   ::rpc/auth false}
  [cfg team]
  (notifications/notify-team-change cfg (select-keys team [:id :is-your-penpot :organization]) nil)
  nil)

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
  (db/tx-run! cfg teams/create-default-org-team profile-id organization-id))


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
  "SELECT t.id, t.name, t.is_default
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
                     [:name ::sm/text]
                     [:is-default ::sm/boolean]]]]
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
                     {:teams teams
                      :num-files files-count})))))


;; ---- API: delete-teams-keeping-your-penpot-projects

(def ^:private sql:prefix-teams-name-and-unset-default
  "UPDATE team
      SET name = ? || name,
          is_default = FALSE
    WHERE id = ANY(?)
RETURNING id, name;")


(def ^:private schema:notify-org-deletion
  [:map
   [:organization-name ::sm/text]
   [:teams [:vector ::sm/uuid]]])

(sv/defmethod ::notify-org-deletion
  "For a list of teams, rename them with the name of the deleted org, and notify
   of the deletion to the connected users"
  {::doc/added "2.15"
   ::sm/params schema:notify-org-deletion}
  [cfg {:keys [teams organization-name]}]
  (when (seq teams)
    (let [org-prefix (str "[" (d/sanitize-string organization-name) "] ")]
      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn] :as cfg}]
         (let [ids-array (db/create-array conn "uuid" teams)
               ;; Rename projects
               updated-teams (db/exec! conn [sql:prefix-teams-name-and-unset-default org-prefix ids-array])]

           ;; Notify users
           (doseq [team updated-teams]
             (notifications/notify-team-change cfg {:id (:id team) :name (:name team) :organization {:name organization-name}} "dashboard.org-deleted"))))))))

;; ---- API: get-profile-by-email

(def ^:private sql:get-profile-by-email
  "SELECT DISTINCT id, fullname, email, photo_id
     FROM profile
    WHERE email = ?
      AND deleted_at IS NULL;")

(sv/defmethod ::get-profile-by-email
  "Get profile by email"
  {::doc/added "2.15"
   ::sm/params [:map [:email ::sm/email]]
   ::sm/result schema:profile}
  [cfg {:keys [email]}]
  (let [profile (db/exec-one! cfg [sql:get-profile-by-email email])]
    (when-not profile
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint "profile does not exist"
                :email email))
    (profile-to-map profile)))


;; ---- API: get-profile-by-id

(def ^:private sql:get-profile-by-id
  "SELECT DISTINCT id, fullname, email, photo_id
     FROM profile
    WHERE id = ?
      AND deleted_at IS NULL;")

(sv/defmethod ::get-profile-by-id
  "Get profile by email"
  {::doc/added "2.15"
   ::sm/params [:map [:id ::sm/uuid]]
   ::sm/result schema:profile}
  [cfg {:keys [id]}]
  (let [profile (db/exec-one! cfg [sql:get-profile-by-id id])]
    (when-not profile
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint "profile does not exist"
                :id id))
    (profile-to-map profile)))


;; ---- API: get-org-member-team-counts

(def ^:private sql:get-org-member-team-counts
  "SELECT tpr.profile_id, COUNT(DISTINCT t.id) AS team_count
     FROM team_profile_rel AS tpr
     JOIN team AS t ON t.id = tpr.team_id
    WHERE t.id = ANY(?)
      AND t.deleted_at IS NULL
      AND t.is_default IS FALSE
    GROUP BY tpr.profile_id;")

(def ^:private schema:get-org-member-team-counts-params
  [:map [:team-ids [:or ::sm/uuid [:vector ::sm/uuid]]]])

(def ^:private schema:get-org-member-team-counts-result
  [:vector [:map
            [:profile-id ::sm/uuid]
            [:team-count ::sm/int]]])

(sv/defmethod ::get-org-member-team-counts
  "Get the number of non-default teams each profile belongs to within a set of teams."
  {::doc/added "2.15"
   ::sm/params schema:get-org-member-team-counts-params
   ::sm/result schema:get-org-member-team-counts-result
   ::rpc/auth false}
  [cfg {:keys [team-ids]}]
  (let [team-ids (cond
                   (uuid? team-ids)
                   [team-ids]

                   (and (vector? team-ids) (every? uuid? team-ids))
                   team-ids

                   :else
                   [])]
    (if (empty? team-ids)
      []
      (db/run! cfg (fn [{:keys [::db/conn]}]
                     (let [ids-array (db/create-array conn "uuid" team-ids)]
                       (db/exec! conn [sql:get-org-member-team-counts ids-array])))))))


;; API: invite-to-org

(sv/defmethod ::invite-to-org
  "Invite to organization"
  {::doc/added "2.15"
   ::sm/params [:map
                [:email ::sm/email]
                [:id ::sm/uuid]
                [:name ::sm/text]
                [:logo ::sm/uri]]}
  [cfg params]
  (db/tx-run! cfg ti/create-org-invitation params)
  nil)



;; API: remove-from-org

(def ^:private sql:get-reassign-to
  "SELECT tpr.profile_id
     FROM team_profile_rel AS tpr
    WHERE tpr.team_id = ?
      AND tpr.profile_id <> ?
      AND tpr.is_owner IS NOT TRUE
    ORDER BY CASE
               WHEN tpr.is_admin IS TRUE THEN 1
               ELSE 2
             END,
             tpr.created_at,
             tpr.profile_id
    LIMIT 1;")

(defn add-reassign-to [cfg profile-id team-to-transfer]
  (let [reassign-to (-> (db/exec-one! cfg [sql:get-reassign-to (:id team-to-transfer) profile-id])
                        :profile-id)]
    (when-not reassign-to
      (ex/raise :type :validation
                :code :nobody-to-reassign-team))

    (assoc team-to-transfer :reassign-to reassign-to)))

(sv/defmethod ::remove-from-org
  "Remove an user from an organization"
  {::doc/added "2.17"
   ::sm/params [:map
                [:profile-id ::sm/uuid]
                [:organization-id ::sm/uuid]
                [:organization-name ::sm/text]
                [:default-team-id ::sm/uuid]]
   ::db/transaction true}
  [cfg {:keys [profile-id organization-id organization-name default-team-id] :as params}]
  (let [{:keys [valid-teams-to-delete-ids
                valid-teams-to-transfer
                valid-teams-to-exit]} (cnit/get-valid-teams cfg organization-id profile-id default-team-id)
        add-reassign-to (partial add-reassign-to cfg profile-id)

        valid-teams-to-leave (into valid-teams-to-exit
                                   (map add-reassign-to valid-teams-to-transfer))]

    (cnit/leave-org cfg (assoc params
                               :id organization-id
                               :name organization-name
                               :teams-to-delete valid-teams-to-delete-ids
                               :teams-to-leave valid-teams-to-leave
                               :skip-validation true))
    (notifications/notify-user-org-change cfg profile-id organization-id organization-name "dashboard.user-no-longer-belong-org")
    nil))

;; API: get-remove-from-org-summary

(def ^:private schema:get-remove-from-org-summary-result
  [:map
   [:teams-to-delete ::sm/int]
   [:teams-to-transfer ::sm/int]
   [:teams-to-exit ::sm/int]])

(sv/defmethod ::get-remove-from-org-summary
  "Get a summary of the teams that would be deleted, transferred, or exited
   if the user were removed from the organization"
  {::doc/added "2.17"
   ::sm/params [:map
                [:profile-id ::sm/uuid]
                [:organization-id ::sm/uuid]
                [:default-team-id ::sm/uuid]]
   ::sm/result schema:get-remove-from-org-summary-result
   ::db/transaction true}
  [cfg {:keys [profile-id organization-id default-team-id]}]
  (let [{:keys [valid-teams-to-delete-ids
                valid-teams-to-transfer
                valid-teams-to-exit
                valid-default-team]} (cnit/get-valid-teams cfg organization-id profile-id default-team-id)]
    (when-not valid-default-team
      (ex/raise :type :validation
                :code :not-valid-teams))
    {:teams-to-delete   (count valid-teams-to-delete-ids)
     :teams-to-transfer (count valid-teams-to-transfer)
     :teams-to-exit     (count valid-teams-to-exit)}))

