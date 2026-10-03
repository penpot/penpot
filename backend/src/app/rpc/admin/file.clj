;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.file
  "Admin file commands, served through `/api/admin/methods`.

  Access control lives in `wrap-authentication` (the `\"superuser\"`
  permission), not here, so a new command cannot forget it."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.changes :as cfc]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.srepl.helpers :as h]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def schema:validation-error
  [:map
   [:code ::sm/text]
   [:hint ::sm/text]
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]
   [:shape-id {:optional true} ::sm/uuid]])

(defn- sanitize-error
  "Trim a validation error down to JSON-safe scalars.

  The raw error carries the broken `:shape` (arbitrary, potentially
  huge or unserializable data) and `:args`; neither travels over
  the wire."
  [error]
  (d/without-nils
   {:code     (name (:code error))
    :hint     (:hint error)
    :file-id  (:file-id error)
    :page-id  (:page-id error)
    :shape-id (:shape-id error)}))

(defn- get-live-file
  "Fetch a file that is neither missing nor marked for deletion."
  [cfg file-id]
  (or (bfc/get-file cfg file-id
                    :throw-if-not-exists? false
                    :realize? true)
      (ex/raise :type :not-found
                :code :file-not-found
                :hint (str "file " file-id " not found"))))

(defn- validate-file*
  [cfg file-id]
  (let [file   (get-live-file cfg file-id)
        libs   (bfc/get-resolved-file-libraries cfg file)
        errors (or (cfv/validate-file file libs) [])]
    (mapv sanitize-error errors)))

(def schema:validate-file-params
  [:map
   [:file-id ::sm/uuid]])

(def schema:validate-file-result
  [:map
   [:file-id ::sm/uuid]
   [:errors [:vector schema:validation-error]]])

(sv/defmethod ::validate-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:validate-file-params
   ::sm/result schema:validate-file-result}
  [cfg {:keys [file-id]}]
  (let [errors (db/tx-run! (assoc cfg ::db/rollback true)
                           (fn [cfg] (validate-file* cfg file-id)))]
    {:file-id file-id
     :errors  errors}))

(def schema:repair-file-params
  [:map
   [:file-id ::sm/uuid]
   [:skip-snapshot {:optional true} ::sm/boolean]])

(def schema:repair-file-result
  [:map
   [:file-id ::sm/uuid]
   [:errors [:vector schema:validation-error]]
   [:changes ::sm/int]
   [:snapshot-taken ::sm/boolean]])

;; NOTE: no `::db/transaction` here on purpose: `h/process-file!`
;; opens its own transaction, and nesting `tx-run!` calls reuses the
;; outer one instead of a savepoint.
(sv/defmethod ::repair-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:repair-file-params
   ::sm/result schema:repair-file-result}
  [cfg {:keys [file-id skip-snapshot ::rpc/profile-id]}]
  ;; Existence (and not-deleted) check up front: `process-file!`
  ;; silently does nothing on a missing file.
  (db/tx-run! (assoc cfg ::db/rollback true)
              (fn [cfg] (get-live-file cfg file-id)))

  (let [state     (atom {:changes 0})
        update-fn (fn [file libs _opts]
                    (let [errors (or (cfv/validate-file file libs) [])]
                      (if (empty? errors)
                        file
                        (let [changes (cfr/repair-file file libs errors)]
                          (swap! state assoc :changes (count changes))
                          (-> file
                              (update :data cfc/process-changes changes))))))
        wrote?    (db/tx-run! cfg h/process-file!
                              file-id update-fn
                              {::h/with-libraries? true
                               ::h/validate? false
                               ::h/profile-id profile-id
                               ::h/snapshot-label (when-not skip-snapshot "repair")})
        errors    (db/tx-run! (assoc cfg ::db/rollback true)
                              (fn [cfg] (validate-file* cfg file-id)))]
    {:file-id        file-id
     :errors         errors
     :changes        (:changes @state)
     :snapshot-taken (boolean (and wrote? (not skip-snapshot)))}))

(def ^:private files-default-limit 50)
(def ^:private files-max-limit 200)

(def schema:file-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:project-id ::sm/uuid]
   [:project-name ::sm/text]
   [:team-id ::sm/uuid]
   [:team-name ::sm/text]
   [:modified-at ct/schema:inst]
   [:deleted-at {:optional true} ct/schema:inst]])

(def schema:get-files-params
  [:map {:title "get-files-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % files-max-limit)]]]
   [:search {:optional true} ::sm/text]
   [:team-id {:optional true} ::sm/uuid]
   [:project-id {:optional true} ::sm/uuid]])

(def schema:get-files-result
  [:map
   [:items [:vector schema:file-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-files-list-query
  [{:keys [since since-id search team-id project-id limit]
    :or {limit files-default-limit}}]
  (let [search-id (when (and (string? search) (not (str/blank? search)))
                    (uuid/parse* search))
        clauses   (keep identity
                        [(when (and (string? search) (not (str/blank? search)))
                           (if search-id
                             {:where "(f.name ILIKE ? OR f.id = ?)"
                              :params [(str "%" search "%") search-id]}
                             {:where "f.name ILIKE ?"
                              :params [(str "%" search "%")]}))
                         (when (uuid? team-id)
                           {:where "p.team_id = ?"
                            :params [team-id]})
                         (when (uuid? project-id)
                           {:where "f.project_id = ?"
                            :params [project-id]})
                         (when since
                           {:where "(f.modified_at, f.id) < (?::timestamptz, ?::uuid)"
                            :params [since (or since-id uuid/zero)]})])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT f.id, f.name, f.project_id, "
                        "p.name AS project_name, "
                        "p.team_id AS team_id, "
                        "t.name AS team_name, "
                        "f.modified_at, f.deleted_at "
                        "FROM file AS f "
                        "JOIN project AS p ON (p.id = f.project_id) "
                        "JOIN team AS t ON (t.id = p.team_id) "
                        "WHERE p.deleted_at IS NULL "
                        "AND t.deleted_at IS NULL "
                        (when (seq sql-parts)
                          (str "AND " (str/join " AND " sql-parts) " "))
                        "ORDER BY f.modified_at DESC, f.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-files
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-files-params
   ::sm/result schema:get-files-result}
  [cfg params]
  (let [limit            (min (or (:limit params) files-default-limit)
                              files-max-limit)
        params           (assoc params :limit (inc limit))
        [sql & sql-args] (build-files-list-query params)
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

(def schema:restore-file-params
  [:map {:title "restore-file"}
   [:id ::sm/uuid]
   [:recursive {:optional true} ::sm/boolean]])

(def schema:restore-file-result
  [:map
   [:id ::sm/uuid]
   [:recursive ::sm/boolean]])

(sv/defmethod ::restore-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:restore-file-params
   ::sm/result schema:restore-file-result}
  [cfg {:keys [id recursive]}]
  (let [row (db/get* cfg :file {:id id} {::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :file-not-found
                :hint (str "file " id " not found"))))
  (cascade/run-cascade cfg :file id
                       {:deleted-at nil
                        :recursive? (boolean recursive)})
  {:id id :recursive (boolean recursive)})
