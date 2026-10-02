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
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def ^:private teams-default-limit 50)
(def ^:private teams-max-limit 200)

(def schema:team-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:created-at ct/schema:inst]
   [:is-default ::sm/boolean]
   [:total-members ::sm/int]
   [:owner {:optional true} ::sm/text]])

(def schema:get-teams-params
  [:map {:title "get-teams-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % teams-max-limit)]]]
   [:search {:optional true} ::sm/text]])

(def schema:get-teams-result
  [:map
   [:items [:vector schema:team-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-teams-list-query
  [{:keys [since since-id search limit]
    :or {limit teams-default-limit}}]
  (let [search-id (when (and (string? search) (not (str/blank? search)))
                    (uuid/parse* search))
        clauses    (keep identity
                         [(when (and (string? search) (not (str/blank? search)))
                            (if search-id
                              {:where "(t.name ILIKE ? OR t.id = ?)"
                               :params [(str "%" search "%") search-id]}
                              {:where "t.name ILIKE ?"
                               :params [(str "%" search "%")]}))
                          (when since
                            {:where "(t.created_at, t.id) < (?::timestamptz, ?::uuid)"
                             :params [since (or since-id uuid/zero)]})])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT t.id, t.name, t.created_at, t.is_default, "
                        "(SELECT count(*) FROM team_profile_rel WHERE team_id = t.id) AS total_members, "
                        "(SELECT p.email FROM team_profile_rel AS tpr "
                        "JOIN profile AS p ON (p.id = tpr.profile_id) "
                        "WHERE tpr.team_id = t.id AND tpr.is_owner IS true "
                        "ORDER BY p.email ASC LIMIT 1) AS owner "
                        "FROM team AS t "
                        "WHERE t.deleted_at IS NULL "
                        (when (seq sql-parts)
                          (str "AND " (str/join " AND " sql-parts) " "))
                        "ORDER BY t.created_at DESC, t.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-teams
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-teams-params
   ::sm/result schema:get-teams-result}
  [cfg params]
  (let [limit            (min (or (:limit params) teams-default-limit)
                              teams-max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (build-teams-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (if (seq rows)
      (let [items     (->> (take limit rows)
                           (mapv d/without-nils))
            last-item (peek items)
            has-more? (> (count rows) limit)]
        {:items      items
         :next-since (when has-more? (:created-at last-item))
         :next-id    (when has-more? (:id last-item))})
      {:items []})))

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
  "Fetch a team that is neither missing nor marked for deletion."
  [cfg id]
  (let [row (db/get-by-id cfg :team id {::db/check-deleted false
                                        ::db/remove-deleted false})]
    (if (and row (nil? (:deleted-at row)))
      row
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
      :is-default         (boolean (:is-default team))
      :total-members      members
      :total-projects     projects
      :features           (vec (sort (:own sets)))
      :effective-features (vec (sort (:effective sets)))})))

(def schema:team-member
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]
   [:fullname ::sm/text]
   [:is-owner ::sm/boolean]
   [:is-admin ::sm/boolean]
   [:can-edit ::sm/boolean]])

(def schema:get-team-members-params
  [:map
   [:team-id ::sm/uuid]])

(def ^:private sql:team-members
  (str "SELECT p.id, p.email, p.fullname, "
       "COALESCE(tp.is_owner, false) AS is_owner, "
       "COALESCE(tp.is_admin, false) AS is_admin, "
       "COALESCE(tp.can_edit, false) AS can_edit "
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

(defn- set-team-feature!
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
  (set-team-feature! cfg team-id feature true))

(sv/defmethod ::disable-team-feature
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:team-feature-params
   ::sm/result schema:team-feature-result}
  [cfg {:keys [team-id feature]}]
  (set-team-feature! cfg team-id feature false))

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
