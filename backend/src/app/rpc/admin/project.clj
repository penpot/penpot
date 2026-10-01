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
   [app.config :as cf]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.jobs :as jobs]
   [app.rpc :as-alias rpc]
   [app.rpc.admin.list :as adml]
   [app.rpc.doc :as doc]
   [app.tasks.restore-object]
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
   [:id {:optional true} ::sm/uuid]
   [:team-id {:optional true} ::sm/uuid]
   [:deleted {:optional true} ::sm/boolean]])

(def schema:get-projects-result
  [:map
   [:items [:vector schema:project-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-projects-list-query
  "List projects newest-first with keyset pagination.

  Lookup is by exact `id` only; see `app.rpc.admin.list` for the
  shared list contract. The parent-team guard stays regardless."
  [{:keys [since since-id id team-id deleted limit]
    :or {limit projects-default-limit}}]
  (let [clauses   (keep identity
                        [(when id
                           {:where "p.id = ?"
                            :params [id]})
                         (when team-id
                           {:where "p.team_id = ?"
                            :params [team-id]})
                         (adml/deleted-clause "p.deleted_at" deleted)
                         (adml/since-clause "p.created_at" "p.id" since since-id)])
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
                        "ORDER BY p.created_at DESC, p.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-projects
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-projects-params
   ::sm/result schema:get-projects-result}
  [cfg params]
  (let [[limit params]   (adml/with-fetch-limit params projects-default-limit projects-max-limit)
        [sql & sql-args] (build-projects-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (adml/paginate rows limit)))

(def schema:get-project-params
  [:map
   [:id ::sm/uuid]])

(def ^:private sql:project
  (str "SELECT p.id, p.name, p.team_id, "
       "t.name AS team_name, "
       "p.is_default, "
       "(SELECT count(*) FROM file AS f "
       "WHERE f.project_id = p.id "
       "AND f.deleted_at IS NULL) AS total_files, "
       "p.created_at, p.modified_at, p.deleted_at "
       "FROM project AS p "
       "JOIN team AS t ON (t.id = p.team_id) "
       "WHERE p.id = ?"))

(sv/defmethod ::get-project
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-project-params
   ::sm/result schema:project-summary}
  [cfg {:keys [id]}]
  (or (some-> (db/exec-one! cfg [sql:project id])
              (d/without-nils))
      (ex/raise :type :not-found
                :code :project-not-found
                :hint (str "project " id " not found"))))

(def schema:restore-project-params
  [:map {:title "restore-project"}
   [:id ::sm/uuid]])

(def schema:restore-project-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::restore-project
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:restore-project-params
   ::sm/result schema:restore-project-result}
  [cfg {:keys [id]}]
  (let [row (db/get* cfg :project {:id id} {::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :project-not-found
                :hint (str "project " id " not found"))))
  ;; NOTE: the project row and its team chain are restored inline so
  ;; they read back instantly; the files cascade runs as a worker
  ;; task. Refuses ownerless teams before writing anything.
  (cascade/restore-chain (::db/conn cfg) :project id)
  (db/update! cfg :project
              {:deleted-at nil}
              {:id id}
              {::db/return-keys false})
  (jobs/submit cfg
               {::jobs/name :restore-object
                ::jobs/params {:object :project
                               :id id}})
  {:id id})

(def schema:delete-project-params
  [:map {:title "delete-project"}
   [:id ::sm/uuid]])

(def schema:delete-project-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::delete-project
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:delete-project-params
   ::sm/result schema:delete-project-result}
  [cfg {:keys [id]}]
  (let [row (db/get* cfg :project {:id id} {::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :project-not-found
                :hint (str "project " id " not found")))
    (if (some? (:deleted-at row))
      ;; NOTE: already deleted is a no-op success: the date stays
      ;; untouched and no task is submitted.
      {:id id}
      (do
        (when (:is-default row)
          (ex/raise :type :validation
                    :code :non-deletable-project
                    :hint "impossible to delete default project"))
        (let [deleted-at (ct/minus (ct/now) (cf/get-deletion-delay))]
          ;; NOTE: the project row is stamped inline so it reads back
          ;; instantly; the files cascade runs as a worker task with
          ;; the same date.
          (db/update! cfg :project
                      {:deleted-at deleted-at}
                      {:id id}
                      {::db/return-keys false})
          (jobs/submit cfg
                       {::jobs/name :delete-object
                        ::jobs/params {:object :project
                                       :deleted-at deleted-at
                                       :id id}})
          {:id id})))))
