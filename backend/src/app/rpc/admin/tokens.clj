;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.tokens
  "Admin access-token issuance and management, served through
  `/api/admin/methods`.

  These are the only admin methods that keep an explicit `\"superuser\"`
  requirement: a granular token must never be able to amplify itself,
  so issuance, listing and revocation stay root-only. The granted
  vocabulary is `app.rpc.admin.perms/grantable-perms`; `\"superuser\"`
  is rejected even though it never appears there, to answer with a
  clear error instead of a generic unknown-permission one."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.admin.perms :as perms]
   [app.rpc.commands.access-token :as access-token]
   [app.rpc.doc :as doc]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]))

(def ^:private schema:create-admin-token-params
  [:map {:title "create-admin-token-params"}
   [:name [:string {:min 1 :max 250}]]
   [:profile-id {:optional true} ::sm/uuid]
   [:expiration {:optional true} ::ct/duration]
   [:perms [:vector {:min 1} [:string {:min 1 :max 200}]]]])

(def ^:private schema:admin-token
  [:map {:title "admin-token"}
   [:id ::sm/uuid]
   [:profile-id ::sm/uuid]
   [:owner-email {:optional true} ::sm/text]
   [:name ::sm/text]
   [:perms [:vector ::sm/text]]
   [:type {:optional true} ::sm/text]
   [:created-at ct/schema:inst]
   [:updated-at ct/schema:inst]
   [:expires-at {:optional true} ct/schema:inst]])

(def ^:private schema:admin-token-created
  [:merge schema:admin-token
   [:map [:token ::sm/text]]])

(sv/defmethod ::create-admin-token
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:create-admin-token-params
   ::sm/result schema:admin-token-created}
  [cfg {creator-id ::rpc/profile-id :keys [profile-id name expiration perms]}]
  (let [owner-id  (or profile-id creator-id)
        requested (set perms)
        grantable (perms/grantable-perms)]
    (when (contains? requested "superuser")
      (ex/raise :type :validation
                :code :superuser-not-grantable
                :hint "the superuser scope cannot be granted on a token"))
    (let [unknown (sort (remove grantable requested))]
      (when (seq unknown)
        (ex/raise :type :validation
                  :code :unknown-permission
                  :hint "unknown admin permission"
                  :unknown unknown)))
    (when-not (db/get* cfg :profile {:id owner-id} {:columns [:id :deleted-at]})
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint "owner profile not found"))
    (quotes/check! cfg {::quotes/id ::quotes/access-tokens-per-profile
                        ::quotes/profile-id owner-id})
    (let [row (db/tx-run!
               cfg
               (fn [{:keys [::db/conn] :as tx-cfg}]
                 (let [created (access-token/create-access-token
                                tx-cfg owner-id name expiration "admin")]
                   (db/update! conn :access-token
                               {:perms (db/create-array conn "text" (vec (sort requested)))}
                               {:id (:id created)})
                   (db/get* tx-cfg :access-token {:id (:id created)}))))]
      (-> row
          (select-keys [:id :profile-id :name :perms :type
                        :created-at :updated-at :expires-at :token])
          (update :perms #(vec (sort (db/decode-pgarray % #{}))))
          (d/without-nils)))))

(def ^:private sql:list-admin-tokens
  "SELECT t.id, t.profile_id, t.name, t.perms, t.type,
          t.created_at, t.updated_at, t.expires_at,
          p.email AS owner_email
     FROM access_token AS t
     JOIN profile AS p ON p.id = t.profile_id
    WHERE t.type = 'admin'")

(def ^:private schema:get-admin-permissions-result
  [:vector ::sm/text])

(sv/defmethod ::get-admin-permissions
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params [:map {:title "get-admin-permissions-params"}]
   ::sm/result schema:get-admin-permissions-result}
  [_ _]
  (vec (sort (perms/grantable-perms))))

(def ^:private schema:get-admin-tokens-params
  [:map {:title "get-admin-tokens-params"}
   [:profile-id {:optional true} ::sm/uuid]])

(sv/defmethod ::get-admin-tokens
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-admin-tokens-params
   ::sm/result [:vector schema:admin-token]}
  [cfg {:keys [profile-id]}]
  (let [sql (if profile-id
              (str sql:list-admin-tokens " AND t.profile_id = ? ORDER BY t.created_at DESC, t.id DESC")
              (str sql:list-admin-tokens " ORDER BY t.created_at DESC, t.id DESC"))
        args (cond-> [sql] profile-id (conj profile-id))]
    (->> (db/exec! cfg args)
         (mapv (fn [row]
                 (-> row
                     (select-keys [:id :profile-id :name :perms :type
                                   :created-at :updated-at :expires-at
                                   :owner-email])
                     (update :perms #(vec (sort (db/decode-pgarray % #{}))))
                     (d/without-nils)))))))

(def ^:private schema:revoke-admin-token-params
  [:map {:title "revoke-admin-token-params"}
   [:id ::sm/uuid]])

(sv/defmethod ::revoke-admin-token
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:revoke-admin-token-params}
  [cfg {:keys [id]}]
  ;; Scoped to `type = 'admin'`: a personal or MCP token id must never
  ;; be revoked through here, whatever trust it carries.
  (if (db/get* cfg :access-token {:id id :type "admin"} {:columns [:id]})
    (do
      (db/delete! cfg :access-token {:id id :type "admin"})
      nil)
    (ex/raise :type :not-found
              :code :access-token-not-found
              :hint "admin access token not found")))
