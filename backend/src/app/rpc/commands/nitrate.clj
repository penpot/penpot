(ns app.rpc.commands.nitrate
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))


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

(def ^:private sql:prefix-team-name-and-unset-default
  "UPDATE team
      SET name = ? || name,
          is_default = FALSE
    WHERE id = ?;")

(def ^:private sql:get-member-teams-info
  "SELECT t.id,
          tpr.is_owner,
          (SELECT count(*)    FROM team_profile_rel WHERE team_id = t.id) AS num_members,
          (SELECT array_agg(profile_id) FROM team_profile_rel WHERE team_id = t.id) AS member_ids
     FROM team AS t
     JOIN team_profile_rel AS tpr ON (tpr.team_id = t.id)
    WHERE tpr.profile_id = ?
      AND t.id = ANY(?)
      AND t.deleted_at IS NULL")

(def ^:private schema:leave-org
  [:map
   [:org-id ::sm/uuid]
   [:default-team-id ::sm/uuid]
   [:teams-to-delete
    [:vector ::sm/uuid]]
   [:teams-to-leave
    [:vector
     [:map
      [:id ::sm/uuid]
      [:reassign-to {:optional true} ::sm/uuid]]]]])

(sv/defmethod ::leave-org
  {::rpc/auth true
   ::doc/added "2.15"
   ::sm/params schema:leave-org
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id org-id default-team-id teams-to-delete teams-to-leave] :as params}]
  (let [org-summary                (nitrate/call cfg :get-org-summary {:org-id org-id})

        org-name                   (:name org-summary)
        org-prefix                 (str "[" (d/sanitize-string org-name) "] ")

        your-penpot-ids            (->> (:teams org-summary)
                                        (filter :is-your-penpot)
                                        (map :id)
                                        (into #{}))

        valid-default-team-id?      (contains? your-penpot-ids default-team-id)

        org-team-ids                (->> (:teams org-summary)
                                         (remove :is-your-penpot)
                                         (map :id))
        ids-array                   (db/create-array conn "uuid" org-team-ids)
        teams                       (db/exec! conn [sql:get-member-teams-info profile-id ids-array])
        teams-by-id                 (d/index-by :id teams)

        ;; valid teams to delete are those that the user is owner, and only have one member
        valid-teams-to-delete-ids   (->> teams
                                         (filter #(and (:is-owner %)
                                                       (= (:num-members %) 1)))
                                         (map :id)
                                         (into #{}))

        valid-teams-to-delete?      (= valid-teams-to-delete-ids (into #{} teams-to-delete))

        ;; valid teams to transfer are those that the user is owner, and have more than one member
        valid-teams-to-transfer     (->> teams
                                         (filter #(and (:is-owner %)
                                                       (> (:num-members %) 1))))
        valid-teams-to-transfer-ids (->> valid-teams-to-transfer (map :id) (into #{}))

        ;; valid teams to exit are those that the user isn't owner, and have more than one member
        valid-teams-to-exit         (->> teams
                                         (filter #(and (not (:is-owner %))
                                                       (> (:num-members %) 1))))
        valid-teams-to-exit-ids     (->> valid-teams-to-exit (map :id) (into #{}))

        valid-teams-to-leave-ids    (into valid-teams-to-transfer-ids valid-teams-to-exit-ids)

        ;; for every team in teams-to-leave, check that:
        ;; - if it has a reassign-to, it belongs to valid-teams-to-transfer and
        ;;   the reassign-to is a member of the team and not the current user;
        ;; - if it hasn't a reassign-to, check that it belongs to valid-teams-to-exit
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


    (when (= (:owner-id org-summary) profile-id)
      (ex/raise :type :validation
                :code :org-owner-cannot-leave))

    (when (or
           (not valid-teams-to-delete?)
           (not valid-teams-to-leave?)
           (not valid-default-team-id?))
      (ex/raise :type :validation
                :code :not-valid-teams))

    ;; delete the teams-to-delete
    (doseq [id teams-to-delete]
      (teams/delete-team cfg {:profile-id profile-id :team-id id}))

    ;; leave the teams-to-leave
    (doseq [{:keys [id reassign-to]} teams-to-leave]
      (teams/leave-team cfg {:profile-id profile-id :id id :reassign-to reassign-to}))

    ;; Rename default-team-id
    (db/exec! conn [sql:prefix-team-name-and-unset-default org-prefix default-team-id])

    ;; Api call to nitrate
    (nitrate/call cfg :remove-profile-from-org {:profile-id profile-id :org-id org-id})

    nil))
