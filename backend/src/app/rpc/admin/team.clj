;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.team
  "Admin team commands, served through `/api/admin/methods`.

  Access control lives in `wrap-authentication` (the `\"superuser\"`
  permission), not here, so a new command cannot forget it."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.common.types.team :as types.team]
   [app.config :as cf]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.jobs :as jobs]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.admin.list :as adml]
   [app.rpc.doc :as doc]
   [app.tasks.restore-object]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def ^:private teams-default-limit 50)
(def ^:private teams-max-limit 200)

(def schema:team-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:created-at ct/schema:inst]
   [:modified-at ct/schema:inst]
   [:is-default ::sm/boolean]
   [:total-members ::sm/int]
   [:deleted-at {:optional true} ct/schema:inst]
   [:owner {:optional true} ::sm/text]])

(def schema:get-teams-params
  [:map {:title "get-teams-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % teams-max-limit)]]]
   [:id {:optional true} ::sm/uuid]
   [:deleted {:optional true} ::sm/boolean]])

(def schema:get-teams-result
  [:map
   [:items [:vector schema:team-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-teams-list-query
  "List teams newest-first with keyset pagination.

  Lookup is by exact `id` only; see `app.rpc.admin.list` for the
  shared list contract."
  [{:keys [since since-id id deleted limit]
    :or {limit teams-default-limit}}]
  (let [clauses    (keep identity
                         [(when id
                            {:where "t.id = ?"
                             :params [id]})
                          (adml/deleted-clause "t.deleted_at" deleted)
                          (adml/since-clause "t.created_at" "t.id" since since-id)])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT t.id, t.name, t.created_at, t.modified_at, t.is_default, t.deleted_at, "
                        ;; Two correlated subqueries per row (member count
                        ;; plus owner email). Fine at admin page sizes; if
                        ;; this list ever gets slow at PRO scale, measure
                        ;; first — the fix is a join here, not another
                        ;; created_at index.
                        "(SELECT count(*) FROM team_profile_rel WHERE team_id = t.id) AS total_members, "
                        "(SELECT p.email FROM team_profile_rel AS tpr "
                        "JOIN profile AS p ON (p.id = tpr.profile_id) "
                        "WHERE tpr.team_id = t.id AND tpr.is_owner IS true "
                        "ORDER BY p.email ASC LIMIT 1) AS owner "
                        "FROM team AS t "
                        (when (seq sql-parts)
                          (str "WHERE " (str/join " AND " sql-parts) " "))
                        "ORDER BY t.created_at DESC, t.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-teams
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-teams-params
   ::sm/result schema:get-teams-result}
  [cfg params]
  (let [[limit params]   (adml/with-fetch-limit params teams-default-limit teams-max-limit)
        [sql & sql-args] (build-teams-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (adml/paginate rows limit)))

(def schema:team
  [:merge schema:team-summary
   [:map
    [:features [:vector ::sm/text]]
    [:effective-features [:vector ::sm/text]]
    [:total-projects ::sm/int]]])

(def schema:get-team-params
  [:map
   [:id ::sm/uuid]])

(defn- get-live-team
  "Fetch a team that is not missing. Deleted teams are returned
  with their stamp: the panel shows them with a restore option."
  [cfg id]
  (let [row (db/get-by-id cfg :team id {::db/check-deleted false
                                        ::db/remove-deleted false})]
    (or row
        (ex/raise :type :not-found
                  :code :team-not-found
                  :hint (str "team " id " not found")))))

(defn- team-feature-sets
  [row]
  (let [own (if (some? (:features row))
              (db/decode-pgarray (:features row) #{})
              #{})]
    {:own own
     :effective (cfeat/get-team-enabled-features cf/flags {:features own})}))

(sv/defmethod ::get-team
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-team-params
   ::sm/result schema:team}
  [cfg {:keys [id]}]
  (let [team     (get-live-team cfg id)
        sets     (team-feature-sets team)
        members  (:count (db/exec-one! cfg ["SELECT count(*) AS count FROM team_profile_rel WHERE team_id = ?" id]))
        projects (:count (db/exec-one! cfg ["SELECT count(*) AS count FROM project WHERE team_id = ?" id]))]
    (d/without-nils
     {:id                 (:id team)
      :name               (:name team)
      :created-at         (:created-at team)
      :modified-at        (:modified-at team)
      :is-default         (boolean (:is-default team))
      :total-members      members
      :total-projects     projects
      :deleted-at         (:deleted-at team)
      :features           (vec (sort (:own sets)))
      :effective-features (vec (sort (:effective sets)))})))

(def schema:team-member
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]
   [:fullname ::sm/text]
   [:is-owner ::sm/boolean]
   [:is-admin ::sm/boolean]
   [:can-edit ::sm/boolean]
   [:deleted-at {:optional true} ::sm/inst]
   [:is-blocked ::sm/boolean]
   [:is-active ::sm/boolean]
   [:is-demo ::sm/boolean]])

(def schema:get-team-members-params
  [:map
   [:team-id ::sm/uuid]])

(def ^:private sql:team-members
  (str "SELECT p.id, p.email, p.fullname, "
       "COALESCE(tp.is_owner, false) AS is_owner, "
       "COALESCE(tp.is_admin, false) AS is_admin, "
       "COALESCE(tp.can_edit, false) AS can_edit, "
       "p.deleted_at AS deleted_at, "
       "COALESCE(p.is_blocked, false) AS is_blocked, "
       "COALESCE(p.is_active, false) AS is_active, "
       "COALESCE(p.is_demo, false) AS is_demo "
       "FROM team_profile_rel AS tp "
       "JOIN profile AS p ON (p.id = tp.profile_id) "
       "WHERE tp.team_id = ? "
       "ORDER BY p.email ASC"))

(sv/defmethod ::get-team-members
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-team-members-params
   ::sm/result [:vector schema:team-member]}
  [cfg {:keys [team-id]}]
  (get-live-team cfg team-id)
  (mapv d/without-nils
        (db/exec! cfg [sql:team-members team-id])))

(def schema:team-feature-params
  [:map
   [:team-id ::sm/uuid]
   [:feature ::sm/text]])

(def schema:team-feature-result
  [:map
   [:id ::sm/uuid]
   [:features [:vector ::sm/text]]])

(defn- set-team-feature
  [cfg team-id feature enable?]
  (when-not (contains? cfeat/supported-features feature)
    (ex/raise :type :validation
              :code :feature-not-supported
              :hint (str "feature '" feature "' not supported")))
  (let [team    (get-live-team cfg team-id)
        current (if (some? (:features team))
                  (db/decode-pgarray (:features team) #{})
                  #{})
        updated ((if enable? conj disj) current feature)]
    (when (not= updated current)
      (let [conn (::db/conn cfg)]
        (db/update! conn :team
                    {:features (db/create-array conn "text" (vec updated))}
                    {:id team-id})))
    {:id team-id :features (vec (sort updated))}))

(sv/defmethod ::enable-team-feature
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:team-feature-params
   ::sm/result schema:team-feature-result}
  [cfg {:keys [team-id feature]}]
  (set-team-feature cfg team-id feature true))

(sv/defmethod ::disable-team-feature
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:team-feature-params
   ::sm/result schema:team-feature-result}
  [cfg {:keys [team-id feature]}]
  (set-team-feature cfg team-id feature false))

;; --- Mutation: Team Member Role

(def schema:update-team-member-role-params
  [:map {:title "update-team-member-role"}
   [:team-id ::sm/uuid]
   [:member-id ::sm/uuid]
   [:role types.team/schema:role]])

(sv/defmethod ::update-team-member-role
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:update-team-member-role-params}
  [{:keys [::db/conn ::mbus/msgbus] :as cfg} {:keys [team-id member-id role]}]
  (let [role    (keyword role)
        _       (get-live-team cfg team-id)
        members (db/exec! cfg [sql:team-members team-id])
        member  (d/seek #(= member-id (:id %)) members)]
    (when-not member
      (ex/raise :type :not-found
                :code :member-does-not-exist))

    ;; Ownership moves by promoting someone else: the current
    ;; owner keeps the seat until then, so it is never vacant.
    (when (:is-owner member)
      (ex/raise :type :validation
                :code :cant-change-role-to-owner))

    (mbus/pub! msgbus
               :topic member-id
               :message {:type :team-role-change
                         :topic member-id
                         :team-id team-id
                         :role role})

    ;; Only allow single owner on team.
    (when (= role :owner)
      (db/update! conn :team-profile-rel
                  {:is-owner false}
                  {:team-id team-id
                   :is-owner true}))

    (db/update! conn :team-profile-rel
                (get types.team/permissions-for-role role)
                {:team-id team-id
                 :profile-id member-id})
    nil))

(def schema:restore-team-params
  [:map {:title "restore-team"}
   [:id ::sm/uuid]])

(def schema:restore-team-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::restore-team
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:restore-team-params
   ::sm/result schema:restore-team-result}
  [cfg {:keys [id]}]
  (let [row (db/get-by-id cfg :team id {::db/check-deleted false
                                        ::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :team-not-found
                :hint (str "team " id " not found"))))
  ;; NOTE: the team rows are restored inline so the team reads back
  ;; instantly; the children cascade runs as a worker task. Refuses
  ;; ownerless teams before writing anything.
  (cascade/restore-chain (::db/conn cfg) :team id)
  (jobs/submit cfg
               {::jobs/name :restore-object
                ::jobs/params {:object :team
                               :id id}})
  {:id id})

(def schema:delete-team-params
  [:map {:title "delete-team"}
   [:id ::sm/uuid]])

(def schema:delete-team-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::delete-team
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:delete-team-params
   ::sm/result schema:delete-team-result}
  [cfg {:keys [id]}]
  (let [row (db/get-by-id cfg :team id {::db/check-deleted false
                                        ::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :team-not-found
                :hint (str "team " id " not found")))
    (if (some? (:deleted-at row))
      ;; NOTE: already deleted is a no-op success: the date stays
      ;; untouched and no task is submitted.
      {:id id}
      (do
        (when (:is-default row)
          (ex/raise :type :validation
                    :code :non-deletable-team
                    :hint "impossible to delete default team"))
        (let [deleted-at (ct/minus (ct/now) (cf/get-deletion-delay))]
          ;; NOTE: the team rows are stamped inline so the team reads
          ;; back instantly; the children cascade runs as a worker
          ;; task with the same date.
          (db/update! cfg :team
                      {:deleted-at deleted-at}
                      {:id id}
                      {::db/return-keys false})
          (db/update! cfg :team-font-variant
                      {:deleted-at deleted-at}
                      {:team-id id}
                      {::db/return-keys false})
          (jobs/submit cfg
                       {::jobs/name :delete-object
                        ::jobs/params {:object :team
                                       :deleted-at deleted-at
                                       :id id}})
          {:id id})))))
