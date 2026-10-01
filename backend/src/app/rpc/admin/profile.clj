;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.profile
  "Admin profile commands, served through `/api/admin/methods`.

  Access control lives in `wrap-authentication` (the `\"superuser\"`
  permission), not here, so a new command cannot forget it."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.admin.list :as adml]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.commands.profile :as cmd.profile]
   [app.rpc.doc :as doc]
   [app.tasks.restore-object]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def ^:private profiles-default-limit 50)
(def ^:private profiles-max-limit 200)

(def schema:profile-summary
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]
   [:fullname ::sm/text]
   [:created-at ct/schema:inst]
   [:modified-at ct/schema:inst]
   [:is-active ::sm/boolean]
   [:is-blocked ::sm/boolean]
   [:is-demo ::sm/boolean]
   [:deleted-at {:optional true} ct/schema:inst]
   [:auth-backend {:optional true} ::sm/text]])

(def schema:get-profiles-params
  [:map {:title "get-profiles-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % profiles-max-limit)]]]
   [:id {:optional true} ::sm/uuid]
   [:email {:optional true} ::sm/text]
   [:deleted {:optional true} ::sm/boolean]])

(def schema:get-profiles-result
  [:map
   [:items [:vector schema:profile-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-profiles-list-query
  "List profiles newest-first with keyset pagination.

  Lookup is by exact indexed fields only (`id`, exact `email`); see
  `app.rpc.admin.list` for the shared list contract."
  [{:keys [since since-id id email deleted limit]
    :or {limit profiles-default-limit}}]
  (let [clauses    (keep identity
                         [(when id
                            {:where "id = ?"
                             :params [id]})
                          (when (and (string? email) (not (str/blank? email)))
                            {:where "email = ?"
                             :params [(str/lower (str/trim email))]})
                          (adml/deleted-clause "deleted_at" deleted)
                          (adml/since-clause "created_at" "id" since since-id)])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT id, email, fullname, created_at, modified_at, "
                        "is_active, COALESCE(is_blocked, false) AS is_blocked, "
                        "is_demo, auth_backend, deleted_at "
                        "FROM profile "
                        (when (seq sql-parts)
                          (str "WHERE " (str/join " AND " sql-parts) " "))
                        "ORDER BY created_at DESC, id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-profiles
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-profiles-params
   ::sm/result schema:get-profiles-result}
  [cfg params]
  (let [[limit params]   (adml/with-fetch-limit params profiles-default-limit profiles-max-limit)
        [sql & sql-args] (build-profiles-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (adml/paginate rows limit)))

(def schema:owned-team
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:members ::sm/int]
   [:deleted-at {:optional true} ::sm/inst]])

(def schema:member-team
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:is-owner ::sm/boolean]
   [:is-admin ::sm/boolean]
   [:deleted-at {:optional true} ::sm/inst]])

(def schema:profile
  [:merge schema:profile-summary
   [:map
    [:is-muted ::sm/boolean]
    [:owned-teams [:vector schema:owned-team]]
    [:member-teams [:vector schema:member-team]]]])

(def schema:get-profile-params
  [:map
   [:id ::sm/uuid]])

(def ^:private sql:owned-teams
  (str "SELECT t.id, t.name, t.deleted_at, "
       "(SELECT count(*) FROM team_profile_rel WHERE team_id = t.id) AS members "
       "FROM team AS t "
       "JOIN team_profile_rel AS tp ON (tp.team_id = t.id) "
       "WHERE tp.profile_id = ? AND tp.is_owner IS true "
       "ORDER BY t.name ASC"))

(def ^:private sql:member-teams
  (str "SELECT t.id, t.name, t.deleted_at, "
       "COALESCE(tp.is_owner, false) AS is_owner, "
       "COALESCE(tp.is_admin, false) AS is_admin "
       "FROM team AS t "
       "JOIN team_profile_rel AS tp ON (tp.team_id = t.id) "
       "WHERE tp.profile_id = ? AND NOT COALESCE(tp.is_owner, false) "
       "ORDER BY t.name ASC"))

(sv/defmethod ::get-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-profile-params
   ::sm/result schema:profile}
  [cfg {:keys [id]}]
  (if-let [row (db/get-by-id cfg :profile id {::db/check-deleted false
                                              ::db/remove-deleted false})]
    (d/without-nils
     {:id           (:id row)
      :email        (:email row)
      :fullname     (:fullname row)
      :created-at   (:created-at row)
      :modified-at  (:modified-at row)
      :is-active    (boolean (:is-active row))
      :is-blocked   (boolean (:is-blocked row))
      :is-demo      (boolean (:is-demo row))
      :is-muted     (boolean (:is-muted row))
      :auth-backend (:auth-backend row)
      :deleted-at   (:deleted-at row)
      :owned-teams  (db/exec! cfg [sql:owned-teams id])
      :member-teams (db/exec! cfg [sql:member-teams id])})
    (ex/raise :type :not-found
              :code :profile-not-found
              :hint (str "profile " id " not found"))))

(def schema:block-result
  [:map
   [:id ::sm/uuid]
   [:is-blocked ::sm/boolean]])

(defn- get-live-profile
  "Fetch a profile that is neither missing nor marked for deletion.
  Reads use `get-profile` (which returns deleted rows with
  their stamp); writes go through here."
  [cfg id]
  (let [row (db/get-by-id cfg :profile id {::db/check-deleted false
                                           ::db/remove-deleted false})]
    (if (and row (nil? (:deleted-at row)))
      row
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint (str "profile " id " not found")))))

(defn- set-blocked-flag
  [cfg id blocked?]
  (get-live-profile cfg id)
  (db/update! cfg :profile {:is-blocked blocked?} {:id id})
  (when blocked?
    (session/invalidate-all cfg id))
  {:id id :is-blocked blocked?})

(sv/defmethod ::block-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:get-profile-params
   ::sm/result schema:block-result}
  [cfg {:keys [id ::rpc/profile-id]}]
  (when (= id profile-id)
    (ex/raise :type :validation
              :code :cannot-block-self
              :hint "a superuser cannot block its own profile"))
  (set-blocked-flag cfg id true))

(sv/defmethod ::unblock-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:get-profile-params
   ::sm/result schema:block-result}
  [cfg {:keys [id]}]
  (set-blocked-flag cfg id false))

(def schema:resend-result
  [:map
   [:id ::sm/uuid]
   [:email ::sm/text]])

(sv/defmethod ::resend-verification
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:get-profile-params
   ::sm/result schema:resend-result}
  [cfg {:keys [id]}]
  (let [row (get-live-profile cfg id)]
    (if (:is-active row)
      (ex/raise :type :validation
                :code :already-active
                :hint "the profile is already active")
      (do
        (cmd.auth/send-email-verification! cfg row)
        {:id (:id row) :email (:email row)}))))

(def schema:delete-profiles-params
  [:map {:title "delete-profiles-params"}
   [:emails [:vector {:min 1 :max 100} ::sm/email]]])

(def schema:delete-profiles-result
  [:map
   [:total ::sm/int]
   [:deleted [:vector ::sm/uuid]]
   [:not-found [:vector ::sm/text]]
   [:skipped-self [:vector ::sm/text]]])

(defn- delete-profile-by-email
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
          (jobs/submit cfg
                       {::jobs/name :delete-object
                        ::jobs/params {:object :profile
                                       :deleted-at deleted-at
                                       :id (:id profile)}})
          (:id profile)))
      nil)))

(sv/defmethod ::delete-profiles
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:delete-profiles-params
   ::sm/result schema:delete-profiles-result}
  [cfg {:keys [emails ::rpc/profile-id]}]
  (let [deleted-at (ct/minus (ct/now) (cf/get-deletion-delay))
        cause      "explicit call to delete-profiles"]
    (reduce (fn [acc raw-email]
              (let [email  (str/lower (str/trim raw-email))
                    result (delete-profile-by-email
                            cfg email deleted-at cause profile-id)]
                (cond
                  (= :self result) (update acc :skipped-self conj email)
                  (nil? result)    (update acc :not-found conj email)
                  :else            (update acc :deleted conj result))))
            {:total (count emails) :deleted [] :not-found [] :skipped-self []}
            emails)))

(def schema:restore-profile-params
  [:map {:title "restore-profile"}
   [:id ::sm/uuid]])

(def schema:restore-profile-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::restore-profile
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:restore-profile-params
   ::sm/result schema:restore-profile-result}
  [cfg {:keys [id]}]
  (let [row (db/get-by-id cfg :profile id {::db/check-deleted false
                                           ::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint (str "profile " id " not found"))))
  ;; NOTE: restoring the profile gives its owned teams a live owner
  ;; back; the teams cascade runs as a worker task.
  (db/update! cfg :profile
              {:deleted-at nil}
              {:id id}
              {::db/return-keys false})
  (jobs/submit cfg
               {::jobs/name :restore-object
                ::jobs/params {:object :profile
                               :id id}})
  {:id id})
