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
                                                          :org-id organization-id})]
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

(def ^:private sql:get-team-files-count
  "SELECT count(*) AS total
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?
      AND f.deleted_at IS NULL")

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

        ;; Get all the teams ids
        all-teams-ids                   (into #{} d/xf:map-id (:teams org-summary))

        ;; Get all the ids of the teams that will be processed:
        ;; all the ids on teams-to-leave, teams-to-delete and default-team-id
        selected-team-ids           (-> (into #{default-team-id} teams-to-delete)
                                        (into d/xf:map-id teams-to-leave))

        ;; Check that we are processing all the teams
        all-teams-selected?         (= all-teams-ids selected-team-ids)

        default-team-files-count    (-> (db/exec-one! conn [sql:get-team-files-count default-team-id])
                                        :total)
        delete-default-team?        (= default-team-files-count 0)

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
           (not valid-default-team-id?)
           (not all-teams-selected?))
      (ex/raise :type :validation
                :code :not-valid-teams))

    (assert-membership cfg profile-id org-id)

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
    (nitrate/call cfg :remove-profile-from-org {:profile-id profile-id :org-id org-id})

    nil))


(def ^:private schema:remove-team-from-org
  [:map
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]
   [:organization-name ::sm/text]])

(sv/defmethod ::remove-team-from-org
  {::doc/added "2.16"
   ::sm/params schema:remove-team-from-org}
  [cfg {:keys [::rpc/profile-id  team-id organization-id organization-name]}]

  (assert-is-owner cfg profile-id team-id)
  (assert-not-default-team cfg team-id)
  (assert-membership cfg profile-id organization-id)

  ;; Api call to nitrate
  (nitrate/call cfg :remove-team-from-org {:team-id team-id :organization-id organization-id})

  ;; Notify connected users
  (notifications/notify-team-change cfg team-id nil nil organization-name "dashboard.team-no-longer-belong-org")
  nil)


(def ^:private schema:add-team-to-org
  [:map
   [:team-id ::sm/uuid]
   [:organization-id ::sm/uuid]
   [:organization-name ::sm/text]])

(sv/defmethod ::add-team-to-org
  {::rpc/auth true
   ::doc/added "2.16"
   ::sm/params schema:add-team-to-org
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id  team-id organization-id organization-name]}]

  (assert-is-owner cfg profile-id team-id)
  (assert-not-default-team cfg team-id)
  (assert-membership cfg profile-id organization-id)

  (let [team-members (db/query cfg :team-profile-rel {:team-id team-id})]
    ;; Add teammates to the org if needed
    (doseq [{member-id :profile-id} team-members
            :when (not= member-id profile-id)]
      (teams/initialize-user-in-nitrate-org cfg member-id organization-id)))

  ;; Api call to nitrate
  (nitrate/call cfg :set-team-org {:team-id team-id :organization-id organization-id :is-default false})

  ;; Notify connected users
  (notifications/notify-team-change cfg team-id nil organization-id organization-name "dashboard.team-belong-org")
  nil)
