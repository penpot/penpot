;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.nitrate
  "Nitrate API for Penpot. Provides nitrate-related endpoints to be called
   from Penpot frontend."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.notifications :as notifications]
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

(def sql:get-team-files-count
  "SELECT count(*) AS total
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?
      AND f.deleted_at IS NULL")

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
  [{:keys [::db/conn] :as cfg} {:keys [profile-id id name default-team-id teams-to-delete teams-to-leave skip-validation] :as params}]
  (let [org-prefix                 (str "[" (d/sanitize-string name) "] ")

        default-team-files-count    (-> (db/exec-one! conn [sql:get-team-files-count default-team-id])
                                        :total)
        delete-default-team?        (= default-team-files-count 0)]




    ;; assert that the received teams are valid, checking the different constraints
    (when-not skip-validation
      (assert-valid-teams cfg profile-id id default-team-id teams-to-delete teams-to-leave))

    (assert-membership cfg profile-id id)

    ;; delete the teams-to-delete
    (doseq [id teams-to-delete]
      (teams/delete-team cfg {:profile-id profile-id :team-id id}))

    ;; leave the teams-to-leave
    (doseq [{:keys [id reassign-to]} teams-to-leave]
      (teams/leave-team cfg {:profile-id profile-id :id id :reassign-to reassign-to}))

    ;; Delete default-team-id if empty; otherwise keep it and prefix the name.
    (if delete-default-team?
      (do
        (db/update! conn :team {:is-default false} {:id default-team-id})
        (teams/delete-team cfg {:profile-id profile-id :team-id default-team-id}))
      (db/exec! conn [sql:prefix-team-name-and-unset-default org-prefix default-team-id]))

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

  ;; Api call to nitrate
  (nitrate/call cfg :remove-team-from-org {:team-id team-id :organization-id organization-id})

  ;; Notify connected users
  (notifications/notify-team-change cfg {:id team-id :organization {:name organization-name}} "dashboard.team-no-longer-belong-org")
  nil)


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
    (let [org-perms (nitrate/call cfg :get-org-permissions
                                  {:organization-id organization-id})]
      (if (nil? org-perms)
        (ex/raise :type :validation
                  :code :not-allowed
                  :hint "Unable to verify organization permissions")
        (let [create-perm (:create-teams org-perms)
              is-owner?   (= profile-id (:owner-id org-perms))]
          (when (and (= create-perm "onlyMe") (not is-owner?))
            (ex/raise :type :validation
                      :code :not-allowed
                      :hint "You are not allowed to add teams in this organization"))))))

  (let [team-members (db/query cfg :team-profile-rel {:team-id team-id})]
    ;; Add teammates to the org if needed
    (doseq [{member-id :profile-id} team-members
            :when (not= member-id profile-id)]
      (teams/initialize-user-in-nitrate-org cfg member-id organization-id)))

  ;; Api call to nitrate
  (let [team (nitrate/call cfg :set-team-org {:team-id team-id :organization-id organization-id :is-default false})]

    ;; Notify connected users
    (notifications/notify-team-change cfg team "dashboard.team-belong-org"))
  nil)
