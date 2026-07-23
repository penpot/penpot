;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.error-reports
  "RPC methods for listing and fetching server error reports.

  Access is restricted to access-token authentication with the
  `error-reports:read` permission. Grant via SQL (or REPL helper):

      UPDATE access_token
         SET perms = ARRAY['error-reports:read']::text[],
             updated_at = now()
       WHERE id = '<token-uuid>';

  Call with: Authorization: Token <jwt>"
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def ^:private max-limit 200)
(def ^:private default-limit 50)

(def ^:private source-names
  {1 "legacy-v1"
   2 "legacy-v2"
   3 "logging"
   4 "audit-log"
   5 "rlimit"})

(defn- source->name
  [source]
  (get source-names source (str "unknown-" source)))

(defn- name->source
  [name]
  (some (fn [[k v]] (when (= v name) k)) source-names))

(def ^:private schema:error-report-summary
  [:map
   [:id ::sm/uuid]
   [:created-at ct/schema:inst]
   [:source ::sm/text]
   [:profile-id {:optional true} ::sm/text]
   [:kind {:optional true} ::sm/text]
   [:tenant {:optional true} ::sm/text]
   [:version {:optional true} ::sm/text]
   [:hint {:optional true} ::sm/text]])

(def ^:private schema:get-error-reports-params
  [:map {:title "get-error-reports-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % max-limit)]]]
   [:source {:optional true} ::sm/text]
   [:profile-id {:optional true} ::sm/text]
   [:kind {:optional true} ::sm/text]
   [:tenant {:optional true} ::sm/text]
   [:version {:optional true} ::sm/text]
   [:hint {:optional true} ::sm/text]])

(def ^:private schema:get-error-reports-result
  [:map
   [:items [:vector schema:error-report-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(def ^:private schema:error-report
  [:map
   [:id ::sm/uuid]
   [:created-at ct/schema:inst]
   [:source ::sm/text]
   [:profile-id {:optional true} ::sm/text]
   [:kind {:optional true} ::sm/text]
   [:tenant {:optional true} ::sm/text]
   [:version {:optional true} ::sm/text]
   [:hint {:optional true} ::sm/text]
   [:report {:optional true} ::sm/text]
   [:href {:optional true} ::sm/text]
   [:context {:optional true} ::sm/text]])

(def ^:private schema:get-error-report-params
  [:map
   [:id ::sm/uuid]])

(def ^:private base-list-sql
  (str "SELECT id, created_at, source, "
       "COALESCE(content->>'~:kind', content->>'~:origin') AS kind, "
       "content->>'~:tenant' AS tenant, "
       "content->>'~:version' AS version, "
       "content->>'~:hint' AS hint, "
       "content->>'~:profile-id' AS profile_id "
       "FROM server_error_report"))

(defn- build-list-query
  [{:keys [since since-id source profile-id kind tenant version hint limit]
    :or {limit default-limit}}]
  (let [source-id (when source (name->source source))
        clauses (keep identity
                      [(when source-id
                         {:where "source = ?" :params [source-id]})
                       (when profile-id
                         {:where "content->>'~:profile-id' = ?"
                          :params [profile-id]})
                       (when kind
                         {:where "COALESCE(content->>'~:kind', content->>'~:origin') = ?"
                          :params [kind]})
                       (when tenant
                         {:where "content->>'~:tenant' = ?"
                          :params [tenant]})
                       (when version
                         {:where "content->>'~:version' = ?"
                          :params [version]})
                       (when hint
                         {:where "content->>'~:hint' ILIKE ?"
                          :params [(str "%" hint "%")]})
                       (when since
                         {:where "(created_at, id) < (?::timestamptz, ?::uuid)"
                          :params [since (or since-id uuid/zero)]})])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str base-list-sql
                        (when (seq sql-parts)
                          (str " WHERE " (str/join " AND " sql-parts)))
                        " ORDER BY created_at DESC, id DESC"
                        " LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-error-reports
  {::doc/added "2.20"
   ::rpc/auth-type :token
   ::rpc/perms #{"error-reports:read"}
   ::sm/params schema:get-error-reports-params
   ::sm/result schema:get-error-reports-result}
  [cfg params]
  (let [limit            (min (or (:limit params) default-limit) max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (build-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (if (seq rows)
      (let [items      (->> (take limit rows)
                            (mapv #(-> %
                                       (update :source source->name)
                                       d/without-nils)))
            last-item  (peek items)
            has-more?  (> (count rows) limit)]
        {:items      items
         :next-since (when has-more? (:created-at last-item))
         :next-id    (when has-more? (:id last-item))})
      {:items []})))

(sv/defmethod ::get-error-report
  {::doc/added "2.20"
   ::rpc/auth-type :token
   ::rpc/perms #{"error-reports:read"}
   ::sm/params schema:get-error-report-params
   ::sm/result schema:error-report}
  [cfg {:keys [id]}]
  (if-let [report (db/get-by-id cfg :server-error-report id {::db/check-deleted false})]
    (let [content (db/decode-transit-pgobject (:content report))]
       (-> report
           (dissoc :content)
           (merge content)
           (update :source source->name)
           (assoc :kind (or (:kind content) (:origin content)))
           (assoc :version (:version content))
           (d/without-nils)))
    (ex/raise :type :not-found
              :code :report-not-found
              :hint (str "error report " id " not found"))))
