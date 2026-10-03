;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.project
  "Admin project commands, served through `/api/admin/methods`.

  Access control lives in `wrap-authentication` (the `\"superuser\"`
  permission), not here, so a new command cannot forget it."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def ^:private projects-default-limit 50)
(def ^:private projects-max-limit 200)

(def schema:project-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:team-id ::sm/uuid]
   [:team-name ::sm/text]
   [:is-default ::sm/boolean]
   [:total-files ::sm/int]
   [:created-at ct/schema:inst]
   [:modified-at ct/schema:inst]
   [:deleted-at {:optional true} ct/schema:inst]])

(def schema:get-projects-params
  [:map {:title "get-projects-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % projects-max-limit)]]]
   [:search {:optional true} ::sm/text]
   [:team-id {:optional true} ::sm/uuid]])

(def schema:get-projects-result
  [:map
   [:items [:vector schema:project-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-projects-list-query
  [{:keys [since since-id search team-id limit]
    :or {limit projects-default-limit}}]
  (let [search-id (when (and (string? search) (not (str/blank? search)))
                    (uuid/parse* search))
        clauses   (keep identity
                        [(when (and (string? search) (not (str/blank? search)))
                           (if search-id
                             {:where "(p.name ILIKE ? OR p.id = ?)"
                              :params [(str "%" search "%") search-id]}
                             {:where "p.name ILIKE ?"
                              :params [(str "%" search "%")]}))
                         (when (uuid? team-id)
                           {:where "p.team_id = ?"
                            :params [team-id]})
                         (when since
                           {:where "(p.modified_at, p.id) < (?::timestamptz, ?::uuid)"
                            :params [since (or since-id uuid/zero)]})])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT p.id, p.name, p.team_id, "
                        "t.name AS team_name, "
                        "p.is_default, "
                        "(SELECT count(*) FROM file AS f "
                        "WHERE f.project_id = p.id "
                        "AND f.deleted_at IS NULL) AS total_files, "
                        "p.created_at, p.modified_at, p.deleted_at "
                        "FROM project AS p "
                        "JOIN team AS t ON (t.id = p.team_id) "
                        "WHERE t.deleted_at IS NULL "
                        (when (seq sql-parts)
                          (str "AND " (str/join " AND " sql-parts) " "))
                        "ORDER BY p.modified_at DESC, p.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-projects
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-projects-params
   ::sm/result schema:get-projects-result}
  [cfg params]
  (let [limit            (min (or (:limit params) projects-default-limit)
                              projects-max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (build-projects-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (if (seq rows)
      (let [items     (->> (take limit rows)
                           (mapv d/without-nils))
            last-item (peek items)
            has-more? (> (count rows) limit)]
        {:items      items
         :next-since (when has-more? (:modified-at last-item))
         :next-id    (when has-more? (:id last-item))})
      {:items []})))

(def schema:restore-project-params
  [:map {:title "restore-project"}
   [:id ::sm/uuid]
   [:recursive {:optional true} ::sm/boolean]])

(def schema:restore-project-result
  [:map
   [:id ::sm/uuid]
   [:recursive ::sm/boolean]])

(sv/defmethod ::restore-project
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:restore-project-params
   ::sm/result schema:restore-project-result}
  [cfg {:keys [id recursive]}]
  (let [row (db/get* cfg :project {:id id} {::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :project-not-found
                :hint (str "project " id " not found"))))
  (cascade/run-cascade cfg :project id
                       {:deleted-at nil
                        :recursive? (boolean recursive)})
  {:id id :recursive (boolean recursive)})
