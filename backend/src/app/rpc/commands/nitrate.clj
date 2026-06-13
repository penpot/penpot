;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.nitrate
  "Nitrate API for Penpot. Provides nitrate-related endpoints to be called
   from Penpot frontend."
  (:require
   [app.auth.oidc :as oidc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.nitrate-permissions :as nitrate-perms]
   [app.config :as cf]
   [app.db :as db]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.notifications :as notifications]
   [app.tokens :as tokens]
   [app.util.services :as sv]))


(defn assert-is-owner [cfg profile-id team-id]
  (let [perms (teams/get-permissions cfg profile-id team-id)]
    (when-not (:is-owner perms)
      (ex/raise :type :validation
                :code :insufficient-permissions))))

(defn assert-not-default-team [cfg team-id]
  (let [team (teams/get-team-info cfg {:id team-id})]
    (when (:is-default team)
      (ex/raise :type :validation
                :code :cant-move-default-team))))

(defn assert-membership [cfg profile-id organization-id]
  (let [membership (nitrate/call cfg :get-org-membership {:profile-id profile-id
                                                          :organization-id organization-id})]
    (when-not (:organization-id membership)
      (ex/raise :type :validation
                :code :organization-doesnt-exists))

    (when-not (:is-member membership)
      (ex/raise :type :validation
                :code :user-doesnt-belong-organization))))


(def schema:connectivity
  [:map {:title "nitrate-connectivity"}
   [:licenses ::sm/boolean]])

(sv/defmethod ::get-nitrate-connectivity
  {::rpc/auth true
   ::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:connectivity}
  [cfg _params]
  (nitrate/call cfg :connectivity {}))

(def ^:private schema:subscription-warning
  [:maybe
   [:map {:title "SubscriptionWarning"}
    [:type {:optional true} ::sm/text]
    [:days-from-expiry {:optional true} ::sm/int]
    [:days-until-expiry {:optional true} ::sm/int]
    [:expiration-date {:optional true} ct/schema:inst]]])

(sv/defmethod ::get-subscription-warning
  {::rpc/auth true
   ::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:subscription-warning}
  [cfg {:keys [::rpc/profile-id]}]
  (nitrate/call cfg :get-subscription-warning {:profile-id profile-id}))

(def ^:private schema:redeem-activation-code-params
  [:map {:title "RedeemActivationCodeParams"}
   [:activation-code ::sm/text]])

(def ^:private schema:redeem-activation-code-result
  [:map {:title "RedeemActivationCodeResult"}
   [:cancel-at [:maybe ct/schema:inst]]])

(sv/defmethod ::redeem-nitrate-activation-code
  {::rpc/auth true
   ::doc/added "2.14"
   ::sm/params schema:redeem-activation-code-params
   ::sm/result schema:redeem-activation-code-result}
  [cfg {:keys [::rpc/profile-id activation-code]}]
  (let [profile (db/get cfg :profile {:id profile-id})]
    (try
      (let [result (nitrate/call cfg :redeem-activation-code
                                 {:request-params  {:code      activation-code
                                                    :penpot-id profile-id
                                                    :email     (:email profile)}})]
        (when-not result
          (ex/raise :type :validation
                    :code :invalid-activation-code
                    :hint "The activation code is invalid, expired or fully redeemed"))
        result)
      (catch Exception cause
        (let [{:keys [type status]} (ex-data cause)]
          (if (= type :nitrate-http-error)
            (ex/raise :type :validation
                      :code (case status
                              410 :expired-activation-code
                              :invalid-activation-code)
                      :cause cause)
            (throw cause)))))))

(def ^:private sql:prefix-team-name-and-unset-default
  "UPDATE team
      SET name = ? || name,
          is_default = FALSE
    WHERE id = ?;")

(def ^:private sql:get-member-teams-info
  "SELECT t.id,
          t.is_default,
          tpr.is_owner,
          (SELECT count(*)    FROM team_profile_rel WHERE team_id = t.id) AS num_members,
          (SELECT array_agg(profile_id) FROM team_profile_rel WHERE team_id = t.id) AS member_ids
     FROM team AS t
     JOIN team_profile_rel AS tpr ON (tpr.team_id = t.id)
    WHERE tpr.profile_id = ?
      AND t.id = ANY(?)
      AND t.deleted_at IS NULL")

(def ^:private sql:get-teams-files-counts
  "SELECT p.team_id, count(*) AS total
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ANY(?)
      AND f.deleted_at IS NULL
 GROUP BY p.team_id")

(defn- get-team-files-counts
  [conn team-ids]
  (if (seq team-ids)
    (let [ids-array (db/create-array conn "uuid" team-ids)]
      (->> (db/exec! conn [sql:get-teams-files-counts ids-array])
           (reduce (fn [acc {:keys [team-id total]}]
                     (assoc acc team-id (long total)))
                   {})))
    {}))

(defn- build-leave-org-plan
  [{:keys [::db/conn]} default-team-id teams-to-delete keep-default-team-requested?]
  (let [all-teams     (cond-> (set teams-to-delete) default-team-id (conj default-team-id))
        files-counts  (get-team-files-counts conn all-teams)
        has-files?    (fn [id] (pos? (long (get files-counts id 0))))
        deletable     (remove has-files? teams-to-delete)
        keep-default? (or keep-default-team-requested?
                          (and default-team-id (has-files? default-team-id)))
        to-detach     (cond-> (into [] (remove (set deletable) teams-to-delete))
                        (and default-team-id keep-default?) (conj default-team-id))]
    {:deletable-team-ids       deletable
     :keep-default-team?       keep-default?
     :delete-default-team?     (boolean (and default-team-id (not keep-default?)))
     :detach-from-org-team-ids to-detach}))

(defn get-leave-org-summary
  [cfg default-team-id teams-to-delete teams-to-transfer-count teams-to-exit-count]
  (let [{:keys [deletable-team-ids detach-from-org-team-ids]}
        (build-leave-org-plan cfg default-team-id teams-to-delete nil)]
    {:teams-to-delete   (count deletable-team-ids)
     :teams-to-transfer teams-to-transfer-count
     :teams-to-exit     teams-to-exit-count
     :teams-to-detach   (count detach-from-org-team-ids)}))

(def ^:private schema:leave-org
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:default-team-id ::sm/uuid]
   [:teams-to-delete
    [:vector ::sm/uuid]]
   [:teams-to-leave
    [:vector
     [:map
      [:id ::sm/uuid]
      [:reassign-to {:optional true} ::sm/uuid]]]]])

(def ^:private schema:get-leave-org-summary-result
  [:map
   [:teams-to-delete ::sm/int]
   [:teams-to-transfer ::sm/int]
   [:teams-to-exit ::sm/int]
   [:teams-to-detach ::sm/int]])

(def ^:private schema:get-leave-org-summary
  [:map
   [:id ::sm/uuid]
   [:default-team-id ::sm/uuid]])


(defn- get-organization-teams-for-user
  [{:keys [::db/conn] :as cfg} org-summary profile-id]
  (let [org-team-ids (->> (:teams org-summary)
                          (map :id))
        ids-array    (db/create-array conn "uuid" org-team-ids)]
    (db/exec! conn [sql:get-member-teams-info profile-id ids-array])))

(defn- calculate-valid-teams
  ([org-teams default-team-id]
   (let [;; valid default team is the one which id is default-team-id
         valid-default-team          (d/seek #(= default-team-id (:id %)) org-teams)

         ;; Remove your-penpot for the rest of validations
         org-teams                   (remove #(= default-team-id (:id %)) org-teams)

         ;; valid teams to delete are those that the user is owner, and only have one member
         valid-teams-to-delete-ids   (->> org-teams
                                          (filter #(and (:is-owner %)
                                                        (= (:num-members %) 1)))
                                          (map :id)
                                          (into #{}))
         ;; valid teams to transfer are those that the user is owner, and have more than one member
         valid-teams-to-transfer     (->> org-teams
                                          (filter #(and (:is-owner %)
                                                        (> (:num-members %) 1))))

         ;; valid teams to exit are those that the user isn't owner, and have more than one member
         valid-teams-to-exit         (->> org-teams
                                          (filter #(and (not (:is-owner %))
                                                        (> (:num-members %) 1))))]
     {:valid-teams-to-delete-ids valid-teams-to-delete-ids
      :valid-teams-to-transfer valid-teams-to-transfer
      :valid-teams-to-exit valid-teams-to-exit
      :valid-default-team valid-default-team})))

(defn get-valid-teams [cfg organization-id profile-id default-team-id]
  (let [org-summary                (nitrate/call cfg :get-org-summary {:organization-id organization-id})
        org-teams                  (get-organization-teams-for-user cfg org-summary profile-id)]
    (calculate-valid-teams org-teams default-team-id)))

(defn- assert-valid-teams [cfg profile-id organization-id default-team-id teams-to-delete teams-to-leave]
  (let [org-summary                (nitrate/call cfg :get-org-summary {:organization-id organization-id})
        org-teams                  (get-organization-teams-for-user cfg org-summary profile-id)
        {:keys [valid-teams-to-delete-ids
                valid-teams-to-transfer
                valid-teams-to-exit
                valid-default-team]} (calculate-valid-teams org-teams default-team-id)



        valid-teams-to-exit-ids     (->> valid-teams-to-exit (map :id) (into #{}))
        valid-teams-to-transfer-ids (->> valid-teams-to-transfer (map :id) (into #{}))
        valid-teams-to-leave-ids    (into valid-teams-to-transfer-ids valid-teams-to-exit-ids)

        valid-default-team-id?      (some? valid-default-team)



        valid-teams-to-delete?      (= valid-teams-to-delete-ids (into #{} teams-to-delete))

        ;; for every team in teams-to-leave, check that:
        ;; - if it has a reassign-to, it belongs to valid-teams-to-transfer and
        ;;   the reassign-to is a member of the team and not the current user;
        ;; - if it hasn't a reassign-to, check that it belongs to valid-teams-to-exit
        teams-by-id                 (d/index-by :id org-teams)
        valid-teams-to-leave?       (and
                                     (= valid-teams-to-leave-ids (->> teams-to-leave (map :id) (into #{})))
                                     (every? (fn [{:keys [id reassign-to]}]
                                               (if reassign-to
                                                 (let [members (db/pgarray->set (:member-ids (get teams-by-id id)))]
                                                   (and (contains? valid-teams-to-transfer-ids id)
                                                        (not= reassign-to profile-id)
                                                        (contains? members reassign-to)))
                                                 (contains? valid-teams-to-exit-ids id)))
                                             teams-to-leave))]
    ;; the org owner cannot leave
    (when (= (:owner-id org-summary) profile-id)
      (ex/raise :type :validation
                :code :org-owner-cannot-leave))

    (when (or
           (not valid-teams-to-delete?)
           (not valid-teams-to-leave?)
           (not valid-default-team-id?))
      (ex/raise :type :validation
                :code :not-valid-teams))))



(defn leave-org
  [{:keys [::db/conn] :as cfg}
   {:keys [profile-id id name default-team-id teams-to-delete teams-to-leave skip-validation keep-default-team-requested?]}]
  (let [org-prefix (str "[" (d/sanitize-string name) "] ")
        {:keys [deletable-team-ids
                keep-default-team?
                detach-from-org-team-ids]} (build-leave-org-plan cfg default-team-id teams-to-delete keep-default-team-requested?)]

    ;; assert that the received teams are valid, checking the different constraints
    (when-not skip-validation
      (assert-valid-teams cfg profile-id id default-team-id teams-to-delete teams-to-leave))

    (assert-membership cfg profile-id id)

    ;; delete only eligible teams (non-protected and without files)
    (doseq [id deletable-team-ids]
      (teams/delete-team cfg {:profile-id profile-id
                              :team-id id}))

    ;; leave the teams-to-leave
    (doseq [{:keys [id reassign-to]} teams-to-leave]
      (teams/leave-team cfg {:profile-id profile-id :id id :reassign-to reassign-to}))

    ;; Process org "Your Penpot" team: keep with prefix if needed, otherwise delete.
    (when default-team-id
      (if keep-default-team?
        (db/exec! conn [sql:prefix-team-name-and-unset-default org-prefix default-team-id])
        (teams/delete-team cfg {:profile-id profile-id
                                :team-id default-team-id})))

    ;; Detach retained owned teams from the organization in Nitrate.
    ;; Nitrate will rehome them to its fallback/default org.
    (doseq [team-id detach-from-org-team-ids]
      (nitrate/call cfg :remove-team-from-org {:team-id team-id
                                               :organization-id id}))

    ;; Api call to nitrate
    (nitrate/call cfg :remove-profile-from-org {:profile-id profile-id :organization-id id})

    nil))


(sv/defmethod ::leave-org
  {::rpc/auth true
   ::doc/added "2.15"
   ::sm/params schema:leave-org
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (leave-org cfg (assoc params :profile-id profile-id)))


(sv/defmethod ::get-leave-org-summary
  {::rpc/auth true
   ::doc/added "2.18"
   ::sm/params schema:get-leave-org-summary
   ::sm/result schema:get-leave-org-summary-result
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id default-team-id]}]
  (let [{:keys [valid-teams-to-delete-ids
                valid-teams-to-transfer
                valid-teams-to-exit
                valid-default-team]} (get-valid-teams cfg id profile-id default-team-id)
        teams-to-transfer-count (count valid-teams-to-transfer)
        teams-to-exit-count     (count valid-teams-to-exit)]
    (when-not valid-default-team
      (ex/raise :type :validation
                :code :not-valid-teams))
    (get-leave-org-summary cfg default-team-id valid-teams-to-delete-ids teams-to-transfer-count teams-to-exit-count)))


(def ^:private schema:remove-team-from-org
  [:map
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]
   [:organization-name ::sm/text]])

(sv/defmethod ::remove-team-from-org
  {::doc/added "2.17"
   ::sm/params schema:remove-team-from-org}
  [cfg {:keys [::rpc/profile-id  team-id organization-id organization-name]}]

  (assert-is-owner cfg profile-id team-id)
  (assert-not-default-team cfg team-id)
  (assert-membership cfg profile-id organization-id)
  ;; Check moveTeams permission on the source organization
  (when (contains? cf/flags :nitrate)
    (let [org-perms (nitrate/call cfg :get-org-permissions
                                  {:organization-id organization-id})]
      (if (nil? org-perms)
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "Unable to verify organization permissions")
        (when-not (nitrate-perms/allowed? :move-team
                                          {:org-perms org-perms
                                           :profile-id profile-id})
          (ex/raise :type :validation
                    :code :not-allowed
                    :hint "You are not allowed to move teams that are part of this organization. If you need more information, contact the owner.")))))

  ;; Api call to nitrate
  (nitrate/call cfg :remove-team-from-org {:team-id team-id :organization-id organization-id})

  ;; Notify connected users
  (notifications/notify-team-change cfg {:id team-id :organization {:name organization-name}} "dashboard.team-no-longer-belong-org")
  nil)

(def ^:private sql:get-team-invitation-emails
  "SELECT email_to
     FROM team_invitation
    WHERE team_id = ?
      AND valid_until > now()")

(def ^:private sql:delete-team-external-invitations
  "DELETE FROM team_invitation
    WHERE team_id = ?
      AND email_to = ANY(?)
      AND valid_until > now()")

(def ^:private sql:get-profiles-by-emails
  "SELECT id, email
     FROM profile
    WHERE email = ANY(?)
      AND deleted_at IS NULL")

(defn- get-external-invitation-info
  "Returns info about external (non-org-member) invitations pending for a team.
   External invitations are those sent to users who are not members of the given org.
   Returns {:allows-anybody bool :external-emails [...]}"
  [{:keys [::db/conn] :as cfg} team-id organization-id]
  (let [org-perms      (nitrate/call cfg :get-org-permissions {:organization-id organization-id})
        allows-anybody (nitrate-perms/allowed? :add-anybody-to-team {:org-perms org-perms})]
    (if allows-anybody
      {:allows-anybody true :external-emails []}
      (let [invitation-emails (db/exec! conn [sql:get-team-invitation-emails team-id])
            emails            (map :email-to invitation-emails)]
        (if (empty? emails)
          {:allows-anybody false :external-emails []}
          (let [emails-array    (db/create-array conn "text" (vec emails))
                profiles        (db/exec! conn [sql:get-profiles-by-emails emails-array])
                org-member-ids  (into #{} (nitrate/call cfg :get-org-members {:organization-id organization-id}))
                external-emails (->> profiles
                                     (remove #(contains? org-member-ids (:id %)))
                                     (map :email)
                                     (vec))]
            {:allows-anybody false :external-emails external-emails}))))))

(def ^:private schema:add-team-to-organization
  [:map
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]])

(sv/defmethod ::add-team-to-organization
  {::rpc/auth true
   ::doc/added "2.17"
   ::sm/params schema:add-team-to-organization
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id  team-id organization-id]}]

  (assert-is-owner cfg profile-id team-id)
  (assert-not-default-team cfg team-id)
  (assert-membership cfg profile-id organization-id)

  (when (contains? cf/flags :nitrate)
    (let [team-with-org         (nitrate/call cfg :get-team-org {:team-id team-id})
          source-org-id         (get-in team-with-org [:organization :id])
          source-org-perms      (when source-org-id
                                  (nitrate/call cfg :get-org-permissions
                                                {:organization-id source-org-id}))
          target-org-perms      (nitrate/call cfg :get-org-permissions
                                              {:organization-id organization-id})
          target-org-same-owner? (and (some? source-org-perms)
                                      (some? target-org-perms)
                                      (= (:owner-id source-org-perms)
                                         (:owner-id target-org-perms)))]
      (when (nil? target-org-perms)
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "Unable to verify organization permissions"))

      ;; Team already belongs to an organization: check move-teams on source org.
      (when (some? source-org-id)
        (when (nil? source-org-perms)
          (ex/raise :type :validation
                    :code :not-allowed
                    :hint "Unable to verify organization permissions"))
        (when-not (nitrate-perms/allowed? :move-team
                                          {:org-perms source-org-perms
                                           :profile-id profile-id
                                           :target-org-same-owner? target-org-same-owner?})
          (ex/raise :type :validation
                    :code :not-allowed
                    :hint "You are not allowed to move teams that are part of this organization. If you need more information, contact the owner.")))

      ;; Always check target create-teams permission (new/add and move flows).
      (when-not (nitrate-perms/allowed? :create-team
                                        {:org-perms target-org-perms
                                         :profile-id profile-id})
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "You are not allowed to add teams in this organization")))

    (let [team-members (db/query cfg :team-profile-rel {:team-id team-id})]
      ;; Add teammates to the org if needed
      (doseq [{member-id :profile-id} team-members
              :when (not= member-id profile-id)]
        (teams/initialize-user-in-nitrate-org cfg member-id organization-id)))

    ;; Api call to nitrate
    (let [team (nitrate/call cfg :set-team-org {:team-id team-id :organization-id organization-id :is-default false})]

      ;; Notify connected users
      (notifications/notify-team-change cfg team "dashboard.team-belong-org"))

    ;; Delete pending invitations for users who are not members of the target organization
    (let [{:keys [allows-anybody external-emails]} (get-external-invitation-info cfg team-id organization-id)]
      (when (and (not allows-anybody) (seq external-emails))
        (let [conn         (::db/conn cfg)
              emails-array (db/create-array conn "text" external-emails)]
          (db/exec! conn [sql:delete-team-external-invitations team-id emails-array])))))

  nil)

(def ^:private schema:check-org-members-params
  [:map {:title "CheckOrgMembersParams"}
   [:organization-id ::sm/uuid]
   [:emails [:vector ::sm/email]]])

(sv/defmethod ::check-org-members
  {::rpc/auth true
   ::doc/added "2.17"
   ::sm/params schema:check-org-members-params
   ::sm/result [:map-of :string :boolean]
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id organization-id emails]}]
  (or (when (contains? cf/flags :nitrate)
        (assert-membership cfg profile-id organization-id)
        (let [emails-array   (db/create-array conn "text" emails)
              profiles       (db/exec! conn [sql:get-profiles-by-emails emails-array])
              email->id      (into {} (map (fn [p] [(:email p) (:id p)])) profiles)
              org-member-ids (into #{} (nitrate/call cfg :get-org-members {:organization-id organization-id}))]
          (into {}
                (map (fn [email]
                       (let [pid (get email->id email)]
                         [email (boolean (and pid (contains? org-member-ids pid)))])))
                emails)))
      {}))

(def ^:private schema:all-org-members-in-team-params
  [:map {:title "CheckOrgMembersInTeamParams"}
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]])

(sv/defmethod ::all-org-members-in-team
  {::rpc/auth true
   ::doc/added "2.17"
   ::sm/params schema:all-org-members-in-team-params
   ::sm/result ::sm/boolean}
  [cfg {:keys [::rpc/profile-id team-id organization-id]}]
  (if (contains? cf/flags :nitrate)
    (let [perms (teams/get-permissions cfg profile-id team-id)]
      (when-not (or (:is-admin perms) (:is-owner perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))
      (assert-membership cfg profile-id organization-id)
      (let [org-members     (nitrate/call cfg :get-org-members {:organization-id organization-id})
            org-member-ids  (into #{} org-members)
            team-members    (db/query cfg :team-profile-rel {:team-id team-id})
            team-member-ids (into #{} (map :profile-id team-members))]
        (every? #(contains? team-member-ids %) org-member-ids)))
    false))

(def ^:private schema:all-team-members-in-orgs-params
  [:map {:title "CheckTeamMembersInOrgsParams"}
   [:team-id ::sm/uuid]
   [:organization-ids [:vector ::sm/uuid]]])

(sv/defmethod ::all-team-members-in-orgs
  {::rpc/auth true
   ::doc/added "2.17"
   ::sm/params schema:all-team-members-in-orgs-params
   ::sm/result [:map-of ::sm/uuid ::sm/boolean]}
  [cfg {:keys [::rpc/profile-id team-id organization-ids]}]
  (if (contains? cf/flags :nitrate)
    (let [perms (teams/get-permissions cfg profile-id team-id)]
      (when-not (or (:is-admin perms) (:is-owner perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (let [team-members    (db/query cfg :team-profile-rel {:team-id team-id})
            team-member-ids (into #{} (map :profile-id team-members))]
        ;; Validate requester membership in all orgs before fetching members.
        (run! #(assert-membership cfg profile-id %) organization-ids)

        (into {}
              (map (fn [organization-id]
                     (let [org-members    (nitrate/call cfg :get-org-members {:organization-id organization-id})
                           org-member-ids (into #{} org-members)]
                       [organization-id
                        (every? #(contains? org-member-ids %) team-member-ids)])))
              organization-ids)))
    {}))

(def ^:private schema:check-team-external-invitations-params
  [:map {:title "CheckTeamExternalInvitationsParams"}
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]])

(def ^:private schema:check-team-external-invitations-result
  [:map {:title "CheckTeamExternalInvitationsResult"}
   [:has-external-invitations ::sm/boolean]
   [:allows-anybody ::sm/boolean]])

(sv/defmethod ::check-team-external-invitations
  {::rpc/auth true
   ::doc/added "2.17"
   ::sm/params schema:check-team-external-invitations-params
   ::sm/result schema:check-team-external-invitations-result
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id team-id organization-id]}]
  (if (contains? cf/flags :nitrate)
    (let [perms (teams/get-permissions cfg profile-id team-id)]
      (when-not (or (:is-admin perms) (:is-owner perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))
      (assert-membership cfg profile-id organization-id)
      (let [{:keys [allows-anybody external-emails]} (get-external-invitation-info cfg team-id organization-id)]
        {:has-external-invitations (boolean (seq external-emails))
         :allows-anybody allows-anybody}))
    {:has-external-invitations false
     :allows-anybody false}))


(def ^:private schema:auth-sso
  [:map {:title "AuthSsoParams"}
   [:team-id ::sm/uuid]
   [:url ::sm/uri]])

(sv/defmethod ::auth-sso
  "Check if a user needs to login into the organization SSO.
  Returns {:authorized true} when SSO is not active for the team.
  Returns {:authorized false :redirect-uri <url>} when SSO is active;
  the client must redirect there. The OIDC provider itself handles
  re-authentication transparently if the user already has an active SSO session."
  {::rpc/auth true
   ::doc/added "2.19"
   ::sm/params schema:auth-sso}
  [cfg {:keys [team-id url] :as params}]
  (let [request                   (rph/get-request params)
        {:keys [authorized sso]}  (nitrate/sso-session-authorized? cfg team-id request)]
    (if authorized
      {:authorized true}
      (if-let [issuer (or (:issuer sso) (:base-url sso))]
        (let [oidc-provider   (oidc/prepare-org-sso-provider cfg sso)
              organization-id (:organization-id sso)
              state-token     (tokens/generate cfg {:iss             "oidc"
                                                    :dest-url        url
                                                    :team-id         team-id
                                                    :organization-id organization-id
                                                    :issuer          issuer
                                                    :exp             (ct/in-future "4h")})
              redirect-uri        (oidc/build-auth-redirect-uri oidc-provider state-token)]
          {:authorized false
           :redirect-uri redirect-uri})
        {:authorized false
         :redirect-uri nil}))))
