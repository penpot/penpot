;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.management.nitrate
  "Internal Nitrate HTTP RPC API. Provides authenticated access to
  organization management and token validation endpoints."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.organization :refer [schema:team-with-organization schema:organization-with-avatar]]
   [app.common.types.profile :refer [schema:profile, schema:basic-profile]]
   [app.common.types.team :refer [schema:team]]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.nitrate :as cnit]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.commands.teams-invitations :as ti]
   [app.rpc.doc :as doc]
   [app.rpc.notifications :as notifications]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [cuerdas.core :as str]))


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
        (assoc :theme (:theme profile))
        (assoc :lang (:lang profile)))))

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
  [:map
   [:version
    [:map
     [:full [:maybe ::sm/text]]
     [:branch [:maybe ::sm/text]]
     [:base [:maybe ::sm/text]]
     [:main [:maybe ::sm/text]]
     [:major [:maybe ::sm/text]]
     [:minor [:maybe ::sm/text]]
     [:patch [:maybe ::sm/text]]
     [:modifier [:maybe ::sm/text]]
     [:commit [:maybe ::sm/text]]
     [:commit-hash [:maybe ::sm/text]]]]])

(sv/defmethod ::get-penpot-version
  "Get the current Penpot version"
  {::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:get-penpot-version-result
   ::rpc/auth false}
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

(def ^:private sql:get-teams-files-counts
  "SELECT p.team_id, COUNT(f.*) AS total
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
     JOIN team AS t ON (t.id = p.team_id)
    WHERE t.id = ANY(?)
      AND t.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND f.deleted_at IS NULL
 GROUP BY p.team_id;")

(def ^:private sql:soft-delete-teams
  "UPDATE team
      SET deleted_at = ?
    WHERE id = ANY(?)
RETURNING id, deleted_at;")


;; ---- API: notify-organization-deletion

(def ^:private schema:notify-organization-deletion
  [:map
   [:organization-id ::sm/uuid]])


(defn- soft-delete-teams!
  "Soft-delete the provided team ids and submit a delete task per team."
  [{:keys [::db/conn] :as cfg} team-ids]
  (when (seq team-ids)
    (let [delay      (cf/get-deletion-delay)
          deleted-at (ct/in-future delay)
          updated    (db/exec! conn [sql:soft-delete-teams
                                     deleted-at
                                     (db/create-array conn "uuid" team-ids)])]
      (doseq [{:keys [id deleted-at]} updated]
        (wrk/submit! {::db/conn conn
                      ::wrk/task :delete-object
                      ::wrk/params {:object :team
                                    :deleted-at deleted-at
                                    :id id}}))))
  nil)

(defn manage-deleted-organization-teams
  "For a deleted organization, preserve org teams unchanged and only prefix or
  delete member Your Penpot teams depending on whether they still contain files."
  [cfg {:keys [organization-id organization-name teams]}]
  (let [all-team-ids (->> teams
                          (map :id)
                          (filter uuid?)
                          distinct
                          (into []))
        your-penpot-team-ids (->> teams
                                  (filter :is-your-penpot)
                                  (map :id)
                                  (filter uuid?)
                                  distinct
                                  (into []))]
    (when (seq all-team-ids)
      (let [org-prefix (str "[" (d/sanitize-string organization-name) "] ")]
        (db/tx-run!
         cfg
         (fn [{:keys [::db/conn] :as cfg}]
           (let [teams-with-files (if (seq your-penpot-team-ids)
                                    (->> (db/exec! conn [sql:get-teams-files-counts
                                                         (db/create-array conn "uuid" your-penpot-team-ids)])
                                         (filter (fn [{:keys [total]}] (pos? total)))
                                         (map :team-id)
                                         (into #{}))
                                    #{})
                 teams-to-prefix  (->> your-penpot-team-ids (filter teams-with-files) (into []))
                 teams-to-delete  (->> your-penpot-team-ids (remove teams-with-files) (into []))]

             ;; Org teams move to the fallback org unchanged. Only imported
             ;; Your Penpot teams keep the org prefix when they still have files.
             (when (seq teams-to-prefix)
               (db/exec! conn [sql:prefix-teams-name-and-unset-default
                               org-prefix
                               (db/create-array conn "uuid" teams-to-prefix)]))

             ;; Empty imported Your Penpot teams disappear entirely.
             (soft-delete-teams! cfg teams-to-delete)

             (notifications/notify-organization-deletion cfg organization-id organization-name all-team-ids teams-to-delete)
             nil)))))))


(sv/defmethod ::notify-organization-deletion
  "For a deleted organization, preserve org teams and only prefix or delete
   imported Your Penpot teams before notifying connected users."
  {::doc/added "2.15"
   ::sm/params schema:notify-organization-deletion
   ::rpc/auth false}
  [cfg {:keys [organization-id]}]
  (let [org-summary (nitrate/call cfg :get-org-summary {:organization-id organization-id})
        teams       (:teams org-summary)]
    (manage-deleted-organization-teams cfg {:organization-name (:name org-summary)
                                            :organization-id (:id org-summary)
                                            :teams teams})
    nil))

;; ---- API: notify-user-organizations-deletion

(def ^:private schema:notify-user-organizations-deletion
  [:map
   [:profile-id ::sm/uuid]])

(sv/defmethod ::notify-user-organizations-deletion
  "For a given user, find all owned organizations and apply the deleted-org
   transfer rules to their imported Your Penpot teams."
  {::doc/added "2.18"
   ::sm/params schema:notify-user-organizations-deletion}
  [cfg {:keys [profile-id]}]
  (let [owned-orgs (nitrate/call cfg :get-owned-orgs {:profile-id profile-id})]
    (doseq [org owned-orgs]
      (let [organization-name (:name org)
            teams             (:teams org)]
        (manage-deleted-organization-teams cfg {:organization-name organization-name
                                                :organization-id (:id org)
                                                :teams teams}))))
  nil)




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
                [:organization schema:organization-with-avatar]]}
  [cfg params]
  (db/tx-run! cfg ti/create-org-invitation params)
  nil)


;; API: get-org-invitations

(def ^:private sql:get-org-invitations
  "SELECT DISTINCT ON (email_to)
          ti.id,
          ti.org_id AS organization_id,
          ti.email_to AS email,
          ti.created_at AS sent_at,
          p.fullname AS name,
          p.id AS profile_id,
          p.photo_id
     FROM team_invitation AS ti
LEFT JOIN profile AS p
       ON p.email = ti.email_to
      AND p.deleted_at IS NULL
    WHERE ti.valid_until >= now()
      AND (ti.org_id = ? OR ti.team_id = ANY(?))
    ORDER BY ti.email_to, ti.valid_until DESC, ti.created_at DESC;")

(def ^:private schema:get-org-invitations-params
  [:map
   [:organization-id ::sm/uuid]])

(def ^:private schema:get-org-invitations-result
  [:vector
   [:map
    [:id ::sm/uuid]
    [:organization-id {:optional true} [:maybe ::sm/uuid]]
    [:email ::sm/email]
    [:sent-at ::sm/inst]
    [:name {:optional true} [:maybe ::sm/text]]
    [:profile-id {:optional true} [:maybe ::sm/uuid]]
    [:photo-url {:optional true} ::sm/uri]]])

(sv/defmethod ::get-org-invitations
  "Get valid invitations for an organization, returning at most one invitation per email."
  {::doc/added "2.16"
   ::sm/params schema:get-org-invitations-params
   ::sm/result schema:get-org-invitations-result}
  [cfg {:keys [organization-id]}]
  (let [org-summary (nitrate/call cfg :get-org-summary {:organization-id organization-id})
        team-ids    (->> (:teams org-summary)
                         (map :id)
                         (filter uuid?)
                         (into []))]
    (db/run! cfg (fn [{:keys [::db/conn]}]
                   (let [ids-array (db/create-array conn "uuid" team-ids)]
                     (->> (db/exec! conn [sql:get-org-invitations organization-id ids-array])
                          (mapv (fn [{:keys [photo-id] :as invitation}]
                                  (cond-> (dissoc invitation :photo-id)
                                    photo-id
                                    (assoc :photo-url (files/resolve-public-uri photo-id)))))))))))


;; API: delete-org-invitations

(def ^:private sql:delete-org-invitations
  "DELETE FROM team_invitation AS ti
    WHERE ti.email_to = ?
      AND (ti.org_id = ? OR ti.team_id = ANY(?));")

(def ^:private schema:delete-org-invitations-params
  [:map
   [:organization-id ::sm/uuid]
   [:email ::sm/email]])

(sv/defmethod ::delete-org-invitations
  "Delete all invitations for one email in an organization scope (org + org teams)."
  {::doc/added "2.16"
   ::sm/params schema:delete-org-invitations-params}
  [cfg {:keys [organization-id email]}]
  (let [org-summary (nitrate/call cfg :get-org-summary {:organization-id organization-id})
        clean-email (profile/clean-email email)
        team-ids    (->> (:teams org-summary)
                         (map :id)
                         (filter uuid?)
                         (into []))]
    (db/run! cfg (fn [{:keys [::db/conn]}]
                   (let [ids-array (db/create-array conn "uuid" team-ids)]
                     (db/exec! conn [sql:delete-org-invitations clean-email organization-id ids-array]))))
    nil))


;; API: delete-all-org-invitations

(def ^:private sql:delete-all-org-invitations
  "DELETE FROM team_invitation AS ti
    WHERE ti.org_id = ?
       OR ti.team_id = ANY(?);")

(def ^:private schema:delete-all-org-invitations-params
  [:map
   [:organization-id ::sm/uuid]])

(sv/defmethod ::delete-all-org-invitations
  "Delete every pending invitation associated with an organization (org-level + team-level).
   Called from Nitrate when an organization is about to be deleted, so users that click
   their invitation token hit the existing invalid-token landing page."
  {::doc/added "2.18"
   ::sm/params schema:delete-all-org-invitations-params
   ::rpc/auth false}
  [cfg {:keys [organization-id]}]
  (let [org-summary (nitrate/call cfg :get-org-summary {:organization-id organization-id})
        team-ids    (->> (:teams org-summary)
                         (map :id))]
    (db/run! cfg (fn [{:keys [::db/conn]}]
                   (let [ids-array (db/create-array conn "uuid" team-ids)]
                     (db/exec! conn [sql:delete-all-org-invitations organization-id ids-array]))))
    nil))


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
   [:teams-to-exit ::sm/int]
   [:teams-to-detach ::sm/int]])

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
    (cnit/get-leave-org-summary cfg
                                default-team-id
                                valid-teams-to-delete-ids
                                (count valid-teams-to-transfer)
                                (count valid-teams-to-exit))))

;; API: send-renewal-email

(def ^:private schema:send-renewal-email-params
  [:map
   [:profile-id ::sm/uuid]
   [:user-email ::sm/email]
   [:user-name [:maybe ::sm/text]]
   [:renewal-date :string]
   [:estimated-amount :double]
   [:organizations [:vector schema:organization-with-avatar]]])

(sv/defmethod ::send-renewal-email
  "Send an Enterprise subscription renewal notice email to a user."
  {::doc/added "2.17"
   ::sm/params schema:send-renewal-email-params
   ::rpc/auth false}
  [cfg {:keys [profile-id user-email user-name renewal-date estimated-amount organizations]}]
  (let [amount-str (format "$%.2f" estimated-amount)
        user-name  (if (str/empty? user-name)
                     (:fullname (profile/get-profile cfg profile-id))
                     user-name)]
    (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                      (eml/send! {::eml/conn    conn
                                  ::eml/factory eml/renewal-notice
                                  :public-uri   (cf/get :public-uri)
                                  :to           user-email
                                  :user-name    user-name
                                  :renewal-date renewal-date
                                  :estimated-amount amount-str
                                  :organizations organizations}))))
  nil)

;; API: exists-org-team-invitations-for-non-members /
;;      delete-org-team-invitations-for-non-members

(def ^:private sql:get-profile-emails-by-ids
  "SELECT email
     FROM profile
    WHERE id = ANY(?)
      AND deleted_at IS NULL")

(def ^:private sql:exists-non-member-org-team-invitations
  "SELECT EXISTS (
            SELECT 1
              FROM team_invitation
             WHERE team_id = ANY(?)
               AND email_to <> ALL(?)
         ) AS non_member")

(def ^:private sql:delete-non-member-org-team-invitations
  "DELETE FROM team_invitation
    WHERE team_id = ANY(?)
      AND email_to <> ALL(?)
   RETURNING email_to")

(def ^:private schema:org-team-invitations-for-non-members-params
  [:map
   [:team-ids [:vector ::sm/uuid]]
   [:member-ids [:vector ::sm/uuid]]])

(def ^:private schema:exists-org-team-invitations-for-non-members-result
  [:map [:exists ::sm/boolean]])

(defn- org-team-invitations-for-non-members-arrays
  "Member emails and PG arrays used by exists/delete org team invitation endpoints."
  [conn {:keys [team-ids member-ids]}]
  (let [member-ids-array (db/create-array conn "uuid" member-ids)
        member-emails    (->> (db/exec! conn [sql:get-profile-emails-by-ids member-ids-array])
                              (map :email)
                              (into #{}))]
    {:emails-array (db/create-array conn "text" (vec member-emails))
     :teams-array  (db/create-array conn "uuid" team-ids)}))

(defn- non-member-org-team-invitations-exist?
  [conn params]
  (let [{:keys [emails-array teams-array]}
        (org-team-invitations-for-non-members-arrays conn params)]
    (-> (db/exec-one! conn [sql:exists-non-member-org-team-invitations
                            teams-array
                            emails-array])
        :non-member)))

(sv/defmethod ::exists-org-team-invitations-for-non-members
  "Return if there are any team invitations for emails that are not organization members."
  {::doc/added "2.18"
   ::sm/params schema:org-team-invitations-for-non-members-params
   ::sm/result schema:exists-org-team-invitations-for-non-members-result}
  [cfg params]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 {:exists (boolean (non-member-org-team-invitations-exist? conn params))})))

(sv/defmethod ::delete-org-team-invitations-for-non-members
  "Delete team invitations for emails that are not organization members."
  {::doc/added "2.18"
   ::sm/params schema:org-team-invitations-for-non-members-params
   ::db/transaction true}
  [cfg params]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [{:keys [emails-array teams-array]}
                       (org-team-invitations-for-non-members-arrays conn params)]
                   (db/exec! conn [sql:delete-non-member-org-team-invitations
                                   teams-array
                                   emails-array])
                   nil))))

;; ---- API: push-audit-events

(def ^:private schema:nitrate-audit-event
  [:map {:title "NitrateAuditEvent"}
   [:name [:and [:string {:max 250}]
           [:re #"[\d\w-]{1,50}"]]]
   [:profile-id ::sm/uuid]
   [:props {:optional true} [:map-of :keyword :any]]])

(def ^:private schema:push-audit-events-params
  [:map {:title "PushAuditEventsParams"}
   [:events [:vector schema:nitrate-audit-event]]])

(defn- submit-nitrate-audit-event
  [cfg {:keys [name profile-id props]}]
  (let [now (ct/now)]
    (audit/submit* cfg {:type "action"
                        :name name
                        :profile-id profile-id
                        :props (or props {})
                        :context {}
                        :tracked-at now
                        :created-at now
                        :source "nitrate"
                        :ip-addr "0.0.0.0"})))

(sv/defmethod ::push-audit-events
  "Push audit events from Nitrate to Penpot audit log"
  {::doc/added "2.19"
   ::sm/params schema:push-audit-events-params
   ::rpc/auth false}
  [{:keys [::db/pool] :as cfg} {:keys [events]}]
  (let [telemetry? (contains? cf/flags :telemetry)
        audit-log? (contains? cf/flags :audit-log)
        enabled?   (and (not (db/read-only? pool))
                        (or audit-log? telemetry?))]
    (when (and enabled? (seq events))
      (run! (partial submit-nitrate-audit-event cfg) events))
    nil))

