;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.errors
  "Admin error-report commands, served through `/api/admin/methods`.

  They mirror `get-error-reports` / `get-error-report` (which stay
  token-only for integrations) for session callers, reusing their
  query and schemas. Access control lives in `wrap-authentication`
  (the `\"superuser\"` permission), not here."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as-alias sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.error-reports :as error-reports]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]))

(sv/defmethod ::get-error-reports
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

(sv/defmethod ::get-error-report
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
