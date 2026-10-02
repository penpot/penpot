;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.admin
  "Commands for instance superusers.

  They are served through the main API like every other command
  namespace. Access control lives in `wrap-authentication` (the
  `\"superuser\"` permission), not here, so a new command cannot
  forget it.

  The error-reports commands below mirror `get-error-reports` /
  `get-error-report` (which stay token-only for integrations) for
  session callers, reusing their query and schemas."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.commands.error-reports :as error-reports]
   [app.rpc.commands.profile :as cmd.profile]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

(sv/defmethod ::get-admin-error-reports
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params error-reports/schema:get-error-reports-params
   ::sm/result error-reports/schema:get-error-reports-result}
  [cfg params]
  (let [limit            (min (or (:limit params) error-reports/default-limit)
                              error-reports/max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (error-reports/build-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (if (seq rows)
      (let [items      (->> (take limit rows)
                            (mapv #(-> %
                                       (update :source error-reports/source->name)
                                       d/without-nils)))
            last-item  (peek items)
            has-more?  (> (count rows) limit)]
        {:items      items
         :next-since (when has-more? (:created-at last-item))
         :next-id    (when has-more? (:id last-item))})
      {:items []})))

(sv/defmethod ::get-admin-error-report
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params error-reports/schema:get-error-report-params
   ::sm/result error-reports/schema:error-report}
  [cfg {:keys [id]}]
  (if-let [report (db/get-by-id cfg :server-error-report id {::db/check-deleted false})]
    (let [content (db/decode-transit-pgobject (:content report))]
      (-> report
          (dissoc :content)
          (merge content)
          (update :source error-reports/source->name)
          (assoc :kind (or (:kind content) (:origin content)))
          (assoc :version (:version content))
          (d/without-nils)))
    (ex/raise :type :not-found
              :code :report-not-found
              :hint (str "error report " id " not found"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROFILES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private profiles-default-limit 50)
(def ^:private profiles-max-limit 200)

(def schema:admin-profile-summary
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]
   [:fullname ::sm/text]
   [:created-at ct/schema:inst]
   [:is-active ::sm/boolean]
   [:is-blocked ::sm/boolean]
   [:is-demo ::sm/boolean]
   [:auth-backend {:optional true} ::sm/text]])

(def schema:get-admin-profiles-params
  [:map {:title "get-admin-profiles-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % profiles-max-limit)]]]
   [:search {:optional true} ::sm/text]
   [:is-blocked {:optional true} ::sm/boolean]
   [:is-active {:optional true} ::sm/boolean]
   [:is-demo {:optional true} ::sm/boolean]])

(def schema:get-admin-profiles-result
  [:map
   [:items [:vector schema:admin-profile-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-profiles-list-query
  [{:keys [since since-id search is-blocked is-active is-demo limit]
    :or {limit profiles-default-limit}}]
  (let [clauses    (keep identity
                         [(when (and (string? search) (not (str/blank? search)))
                            {:where "(email ILIKE ? OR fullname ILIKE ?)"
                             :params [(str "%" search "%") (str "%" search "%")]})
                          (when (some? is-blocked)
                            {:where "is_blocked IS NOT DISTINCT FROM ?"
                             :params [is-blocked]})
                          (when (some? is-active)
                            {:where "is_active = ?"
                             :params [is-active]})
                          (when (some? is-demo)
                            {:where "is_demo = ?"
                             :params [is-demo]})
                          (when since
                            {:where "(created_at, id) < (?::timestamptz, ?::uuid)"
                             :params [since (or since-id uuid/zero)]})])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT id, email, fullname, created_at, "
                        "is_active, COALESCE(is_blocked, false) AS is_blocked, "
                        "is_demo, auth_backend, deleted_at "
                        "FROM profile "
                        (when (seq sql-parts)
                          (str "WHERE " (str/join " AND " sql-parts) " "))
                        "ORDER BY created_at DESC, id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-admin-profiles
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-admin-profiles-params
   ::sm/result schema:get-admin-profiles-result}
  [cfg params]
  (let [limit            (min (or (:limit params) profiles-default-limit)
                              profiles-max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (build-profiles-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (if (seq rows)
      (let [items      (->> (take limit rows)
                            (mapv d/without-nils))
            last-item  (peek items)
            has-more?  (> (count rows) limit)]
        {:items      items
         :next-since (when has-more? (:created-at last-item))
         :next-id    (when has-more? (:id last-item))})
      {:items []})))

(def schema:admin-owned-team
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:members ::sm/int]])

(def schema:admin-member-team
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:is-owner ::sm/boolean]
   [:is-admin ::sm/boolean]])

(def schema:admin-profile
  [:merge schema:admin-profile-summary
   [:map
    [:is-muted ::sm/boolean]
    [:owned-teams [:vector schema:admin-owned-team]]
    [:member-teams [:vector schema:admin-member-team]]]])

(def schema:get-admin-profile-params
  [:map
   [:id ::sm/uuid]])

(def ^:private sql:admin-owned-teams
  (str "SELECT t.id, t.name, "
       "(SELECT count(*) FROM team_profile_rel WHERE team_id = t.id) AS members "
       "FROM team AS t "
       "JOIN team_profile_rel AS tp ON (tp.team_id = t.id) "
       "WHERE tp.profile_id = ? AND tp.is_owner IS true AND t.deleted_at IS NULL "
       "ORDER BY t.name ASC"))

(def ^:private sql:admin-member-teams
  (str "SELECT t.id, t.name, "
       "COALESCE(tp.is_owner, false) AS is_owner, "
       "COALESCE(tp.is_admin, false) AS is_admin "
       "FROM team AS t "
       "JOIN team_profile_rel AS tp ON (tp.team_id = t.id) "
       "WHERE tp.profile_id = ? AND NOT COALESCE(tp.is_owner, false) "
       "AND t.deleted_at IS NULL "
       "ORDER BY t.name ASC"))

(sv/defmethod ::get-admin-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-admin-profile-params
   ::sm/result schema:admin-profile}
  [cfg {:keys [id]}]
  (if-let [row (db/get-by-id cfg :profile id {::db/check-deleted false
                                              ::db/remove-deleted false})]
    (d/without-nils
     {:id           (:id row)
      :email        (:email row)
      :fullname     (:fullname row)
      :created-at   (:created-at row)
      :is-active    (boolean (:is-active row))
      :is-blocked   (boolean (:is-blocked row))
      :is-demo      (boolean (:is-demo row))
      :is-muted     (boolean (:is-muted row))
      :auth-backend (:auth-backend row)
      :deleted-at   (:deleted-at row)
      :owned-teams  (db/exec! cfg [sql:admin-owned-teams id])
      :member-teams (db/exec! cfg [sql:admin-member-teams id])})
    (ex/raise :type :not-found
              :code :profile-not-found
              :hint (str "profile " id " not found"))))

(def schema:admin-block-result
  [:map
   [:id ::sm/uuid]
   [:is-blocked ::sm/boolean]])

(defn- get-live-profile
  "Fetch a profile that is neither missing nor marked for deletion.
  Reads use `get-admin-profile` (which returns deleted rows with
  their stamp); writes go through here."
  [cfg id]
  (let [row (db/get-by-id cfg :profile id {::db/check-deleted false
                                           ::db/remove-deleted false})]
    (if (and row (nil? (:deleted-at row)))
      row
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint (str "profile " id " not found")))))

(defn- set-blocked-flag!
  [cfg id blocked?]
  (get-live-profile cfg id)
  (db/update! cfg :profile {:is-blocked blocked?} {:id id})
  (when blocked?
    (session/invalidate-all cfg id))
  {:id id :is-blocked blocked?})

(sv/defmethod ::block-admin-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:get-admin-profile-params
   ::sm/result schema:admin-block-result}
  [cfg {:keys [id ::rpc/profile-id]}]
  (when (= id profile-id)
    (ex/raise :type :validation
              :code :cannot-block-self
              :hint "a superuser cannot block its own profile"))
  (set-blocked-flag! cfg id true))

(sv/defmethod ::unblock-admin-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:get-admin-profile-params
   ::sm/result schema:admin-block-result}
  [cfg {:keys [id]}]
  (set-blocked-flag! cfg id false))

(def schema:admin-resend-result
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]])

(sv/defmethod ::resend-admin-verification
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:get-admin-profile-params
   ::sm/result schema:admin-resend-result}
  [cfg {:keys [id]}]
  (let [row (get-live-profile cfg id)]
    (if (:is-active row)
      (ex/raise :type :validation
                :code :already-active
                :hint "the profile is already active")
      (do
        (cmd.auth/send-email-verification! cfg row)
        {:id (:id row) :email (:email row)}))))

(def schema:delete-admin-profiles-params
  [:map {:title "delete-admin-profiles-params"}
   [:emails [:vector {:min 1 :max 100} ::sm/email]]])

(def schema:delete-admin-profiles-result
  [:map
   [:total ::sm/int]
   [:deleted [:vector ::sm/uuid]]
   [:not-found [:vector ::sm/text]]
   [:skipped-self [:vector ::sm/text]]])

(defn- delete-admin-profile-by-email!
  [cfg email deleted-at cause operator-id]
  (let [email (str/lower (str/trim email))]
    (if-let [profile (some-> (db/get* cfg :profile {:email email})
                             (cmd.profile/decode-row))]
      (if (= (:id profile) operator-id)
        :self
        (do
          (audit/insert cfg
                        {:name "delete-profile"
                         :type "action"
                         :profile-id (:id profile)
                         :tracked-at deleted-at
                         :props (audit/profile->props profile)
                         :context {:triggered-by "admin-panel"
                                   :cause cause
                                   :operator-id operator-id}})
          (db/update! cfg :profile
                      {:deleted-at deleted-at}
                      {:id (:id profile)})
          (session/invalidate-all cfg (:id profile))
          (wrk/submit! {::db/conn (::db/conn cfg)
                        ::wrk/task :delete-object
                        ::wrk/params {:object :profile
                                      :deleted-at deleted-at
                                      :id (:id profile)}})
          (:id profile)))
      nil)))

(sv/defmethod ::delete-admin-profiles
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:delete-admin-profiles-params
   ::sm/result schema:delete-admin-profiles-result}
  [cfg {:keys [emails ::rpc/profile-id]}]
  (let [deleted-at (ct/minus (ct/now) (cf/get-deletion-delay))
        cause      "explicit call to delete-admin-profiles"]
    (reduce (fn [acc raw-email]
              (let [email  (str/lower (str/trim raw-email))
                    result (delete-admin-profile-by-email!
                            cfg email deleted-at cause profile-id)]
                (cond
                  (= :self result) (update acc :skipped-self conj email)
                  (nil? result)    (update acc :not-found conj email)
                  :else            (update acc :deleted conj result))))
            {:total (count emails) :deleted [] :not-found [] :skipped-self []}
            emails)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TEAMS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private teams-default-limit 50)
(def ^:private teams-max-limit 200)

(def schema:admin-team-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:created-at ct/schema:inst]
   [:is-default ::sm/boolean]
   [:total-members ::sm/int]
   [:owner {:optional true} ::sm/text]])

(def schema:get-admin-teams-params
  [:map {:title "get-admin-teams-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % teams-max-limit)]]]
   [:search {:optional true} ::sm/text]])

(def schema:get-admin-teams-result
  [:map
   [:items [:vector schema:admin-team-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-teams-list-query
  [{:keys [since since-id search limit]
    :or {limit teams-default-limit}}]
  (let [clauses    (keep identity
                         [(when (and (string? search) (not (str/blank? search)))
                            {:where "t.name ILIKE ?"
                             :params [(str "%" search "%")]})
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

(sv/defmethod ::get-admin-teams
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-admin-teams-params
   ::sm/result schema:get-admin-teams-result}
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

(def schema:admin-team
  [:merge schema:admin-team-summary
   [:map
    [:features [:vector ::sm/text]]
    [:effective-features [:vector ::sm/text]]
    [:total-projects ::sm/int]]])

(def schema:get-admin-team-params
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

(sv/defmethod ::get-admin-team
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-admin-team-params
   ::sm/result schema:admin-team}
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

(def schema:admin-team-member
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]
   [:fullname ::sm/text]
   [:is-owner ::sm/boolean]
   [:is-admin ::sm/boolean]
   [:can-edit ::sm/boolean]])

(def schema:get-admin-team-members-params
  [:map
   [:team-id ::sm/uuid]])

(def ^:private sql:admin-team-members
  (str "SELECT p.id, p.email, p.fullname, "
       "COALESCE(tp.is_owner, false) AS is_owner, "
       "COALESCE(tp.is_admin, false) AS is_admin, "
       "COALESCE(tp.can_edit, false) AS can_edit "
       "FROM team_profile_rel AS tp "
       "JOIN profile AS p ON (p.id = tp.profile_id) "
       "WHERE tp.team_id = ? "
       "ORDER BY p.email ASC"))

(sv/defmethod ::get-admin-team-members
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-admin-team-members-params
   ::sm/result [:vector schema:admin-team-member]}
  [cfg {:keys [team-id]}]
  (get-live-team cfg team-id)
  (mapv d/without-nils
        (db/exec! cfg [sql:admin-team-members team-id])))

(def schema:admin-team-feature-params
  [:map
   [:team-id ::sm/uuid]
   [:feature ::sm/text]])

(def schema:admin-team-feature-result
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

(sv/defmethod ::enable-admin-team-feature
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:admin-team-feature-params
   ::sm/result schema:admin-team-feature-result}
  [cfg {:keys [team-id feature]}]
  (set-team-feature! cfg team-id feature true))

(sv/defmethod ::disable-admin-team-feature
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:admin-team-feature-params
   ::sm/result schema:admin-team-feature-result}
  [cfg {:keys [team-id feature]}]
  (set-team-feature! cfg team-id feature false))
