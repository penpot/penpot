;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.access-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.access-token :as actoken]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.util.services :as sv]))

(defn- decode-row
  [row]
  (dissoc row :perms))

(def ^:private sql:clean-old-mcp-tokens
  "DELETE FROM access_token
    WHERE profile_id = ?
      AND id != ?
      AND type = 'mcp'")

(def ^:private sql:get-valid-mcp-token
  "SELECT * FROM access_token
    WHERE profile_id = ?
      AND type = 'mcp'
      AND (expires_at IS NULL OR expires_at > now())
    ORDER BY created_at DESC
    LIMIT 1")

(defn create-access-token
  "Create an access token with empty perms.

  Elevated permissions (e.g. error-reports:read) are not assignable via
  the public API; grant them with SQL or `repl:grant-access-token-perm`."
  [{:keys [::db/conn] :as cfg} profile-id name expiration type]
  ;; Serialize regeneration with OAuth provisioning for the same profile.
  (when (= type "mcp")
    (db/get-by-id conn :profile profile-id {::db/for-update true}))
  (let [token-id   (uuid/next)
        expires-at (some-> expiration (ct/in-future))
        created-at (ct/now)
        token-iss  (if (= type "mcp") actoken/mcp-token-issuer "access-token")
        token      (tokens/generate cfg {:iss token-iss
                                         :uid profile-id
                                         :iat created-at
                                         :tid token-id})

        token      (db/insert! conn :access-token
                               {:id token-id
                                :name name
                                :token token
                                :type type
                                :profile-id profile-id
                                :created-at created-at
                                :updated-at created-at
                                :expires-at expires-at
                                :perms (db/create-array conn "text" [])})]

    ;; If the created token is of mcp type, we should proceed to
    ;; delete all other mcp tokens on the table for the current
    ;; profile.
    (when (= type "mcp")
      (db/exec! conn [sql:clean-old-mcp-tokens profile-id (:id token)]))

    (decode-row token)))

(defn ensure-mcp-access!
  "Enable MCP and return the profile's unexpired token, creating it if absent.
  Must run in a transaction. Lock the profile even when no token exists, so
  concurrent approvals and settings regeneration use the same lock. Enabling
  MCP must not regenerate a token already held by a connected client; an
  expired token is replaced because no client can use it, and so is a token
  issued before MCP tokens had their own issuer, or one this instance cannot
  decrypt, because the backend rejects both."
  [{:keys [::db/conn] :as cfg} profile-id]
  (let [profile (profile/get-profile conn profile-id ::db/for-update true)
        token   (db/exec-one! conn [sql:get-valid-mcp-token profile-id])
        ;; A token this instance cannot decrypt is as unusable as an expired one
        token   (when (= actoken/mcp-token-issuer
                         (ex/ignoring (:iss (tokens/decode cfg (:token token)))))
                  token)]
    (when-not (:mcp-enabled (:props profile))
      (profile/update-profile-props cfg profile-id {:mcp-enabled true}))
    (if token
      (decode-row token)
      (create-access-token cfg profile-id "MCP" nil "mcp"))))

(defn repl:create-access-token
  [cfg profile-id name expiration]
  (db/tx-run! cfg create-access-token profile-id name expiration))

(def ^:private sql:grant-access-token-perm
  "UPDATE access_token
      SET perms = (
            SELECT ARRAY(
              SELECT DISTINCT unnest(perms || ARRAY[?]::text[])
            )
          ),
          updated_at = now()
    WHERE id = ?
RETURNING id, perms")

(defn repl:grant-access-token-perm
  "Append a permission string to an access token (operator/SQL path).

  Example: (repl:grant-access-token-perm cfg token-id \"error-reports:read\")"
  [cfg token-id perm]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (let [row (db/exec-one! conn [sql:grant-access-token-perm perm token-id])]
                  (some-> row (update :perms db/decode-pgarray #{}))))))

(def ^:private schema:create-access-token
  [:map {:title "create-access-token"}
   [:name [:string {:max 250 :min 1}]]
   [:expiration {:optional true} ::ct/duration]
   [:type {:optional true} :string]])

(sv/defmethod ::create-access-token
  {::doc/added "1.18"
   ::sm/params schema:create-access-token}
  [cfg {:keys [::rpc/profile-id name expiration type]}]

  (quotes/check! cfg {::quotes/id ::quotes/access-tokens-per-profile
                      ::quotes/profile-id profile-id})

  (db/tx-run! cfg create-access-token profile-id name expiration type))

(def ^:private schema:delete-access-token
  [:map {:title "delete-access-token"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-access-token
  {::doc/added "1.18"
   ::sm/params schema:delete-access-token}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (db/delete! pool :access-token {:id id :profile-id profile-id})
  nil)

(def ^:private schema:get-access-tokens
  [:map {:title "get-access-tokens"}])

(sv/defmethod ::get-access-tokens
  {::doc/added "1.18"
   ::sm/params schema:get-access-tokens}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (->> (db/query pool :access-token
                 {:profile-id profile-id}
                 {:order-by [[:expires-at :asc] [:created-at :asc]]
                  :columns [:id :name :perms :type :created-at :updated-at :expires-at :token]})
       (map decode-row)
       (map (fn [{:keys [type] :as row}]
              (if (not= type "mcp")
                (dissoc row :token)
                row)))
       (vec)))

(def ^:private schema:get-current-mcp-token
  [:map {:title "get-current-mcp-token"}])

(sv/defmethod ::get-current-mcp-token
  {::doc/added "2.15"
   ::doc/deprecated true
   ::sm/params schema:get-current-mcp-token}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id ::rpc/request-at]}]
  (->> (db/query pool :access-token
                 {:profile-id profile-id
                  :type "mcp"}
                 {:order-by [[:expires-at :asc] [:created-at :asc]]
                  :columns [:token :expires-at]})
       (remove #(and (some? (:expires-at %))
                     (ct/is-after? request-at (:expires-at %))))
       (map decode-row)
       (first)))
