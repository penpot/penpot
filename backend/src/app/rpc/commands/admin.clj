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
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.error-reports :as error-reports]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
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
                         [{:where "(deleted_at IS NULL OR deleted_at > now())"}
                          (when (and (string? search) (not (str/blank? search)))
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
                        "is_demo, auth_backend "
                        "FROM profile "
                        "WHERE " (str/join " AND " sql-parts) " "
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
