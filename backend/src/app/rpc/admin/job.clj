;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.job
  "Admin job commands, served through `/api/admin/methods`.

  Read-only inspection of the `job` table plus cancellation, for the
  Jobs section of the `/admin` panel. Access control lives in
  `wrap-authentication` (the `\"superuser\"` permission), not here,
  so a new command cannot forget it.

  Cancellation reuses the substrate writer `app.jobs/cancel`, which
  moves pending and running jobs to `cancelled` with their `end`
  event in one transaction. A running job stops at its next
  heartbeat; terminal jobs are left untouched."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.pprint :as pp]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.rpc :as-alias rpc]
   [app.rpc.admin.list :as adml]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def ^:private jobs-default-limit 50)
(def ^:private jobs-max-limit 200)

(def schema:job-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:queue ::sm/text]
   [:label {:optional true} ::sm/text]
   [:status ::sm/text]
   [:kind ::sm/text]
   [:profile-id {:optional true} ::sm/uuid]
   [:retry-num ::sm/int]
   [:max-retries ::sm/int]
   [:scheduled-at ct/schema:inst]
   [:created-at ct/schema:inst]
   [:started-at {:optional true} ct/schema:inst]
   [:completed-at {:optional true} ct/schema:inst]])

(def schema:get-jobs-params
  [:map {:title "get-jobs-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % jobs-max-limit)]]]
   [:id {:optional true} ::sm/uuid]
   [:name {:optional true} ::sm/text]
   [:status {:optional true} [:enum "new" "scheduled" "running" "retry"
                              "completed" "failed" "cancelled" "aborted"]]])

(def schema:get-jobs-result
  [:map
   [:items [:vector schema:job-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- summarize
  "Light list row: scalar columns only, never the business payload.
  `kind` is derived: a job without a profile is a system job."
  [row]
  (cond-> {:id           (:id row)
           :name         (:name row)
           :queue        (:queue row)
           :status       (:status row)
           :kind         (if (some? (:profile-id row)) "user" "system")
           :retry-num    (:retry-num row)
           :max-retries  (:max-retries row)
           :scheduled-at (:scheduled-at row)
           :created-at   (:created-at row)}
    (some? (:label row))        (assoc :label (:label row))
    (some? (:profile-id row))   (assoc :profile-id (:profile-id row))
    (some? (:started-at row))    (assoc :started-at (:started-at row))
    (some? (:completed-at row)) (assoc :completed-at (:completed-at row))))

(defn- build-jobs-list-query
  "List jobs of this instance newest-first with keyset pagination.

  Lookup is by exact `id` only, filters by exact `name` and `status`;
  see `app.rpc.admin.list` for the shared list contract. Served by
  `job__admin_list__idx` (migration 0158)."
  [tenant {:keys [since since-id id name status limit]
           :or {limit jobs-default-limit}}]
  (let [clauses    (keep identity
                         [{:where  "j.tenant = ?"
                           :params [tenant]}
                          (when id
                            {:where  "j.id = ?"
                             :params [id]})
                          (when name
                            {:where  "j.name = ?"
                             :params [name]})
                          (when status
                            {:where  "j.status = ?"
                             :params [status]})
                          (adml/since-clause "j.created_at" "j.id" since since-id)])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT j.id, j.name, j.queue, j.label, j.status, "
                        "j.profile_id, j.retry_num, j.max_retries, "
                        "j.scheduled_at, j.created_at, "
                        "j.started_at, j.completed_at "
                        "FROM job AS j "
                        "WHERE " (str/join " AND " sql-parts) " "
                        "ORDER BY j.created_at DESC, j.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-jobs
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-jobs-params
   ::sm/result schema:get-jobs-result}
  [cfg params]
  (let [[limit params]   (adml/with-fetch-limit params jobs-default-limit jobs-max-limit)
        [sql & sql-args] (build-jobs-list-query (cf/get :tenant) params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (update (adml/paginate rows limit) :items #(mapv summarize %))))

(def schema:job-event
  [:map
   [:kind ::sm/text]
   [:payload :any]
   [:created-at ct/schema:inst]])

(def schema:job-detail
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:queue ::sm/text]
   [:label {:optional true} ::sm/text]
   [:status ::sm/text]
   [:kind ::sm/text]
   [:profile-id {:optional true} ::sm/uuid]
   [:owner-email {:optional true} ::sm/text]
   [:owner-fullname {:optional true} ::sm/text]
   [:tenant ::sm/text]
   [:priority ::sm/int]
   [:retry-num ::sm/int]
   [:max-retries ::sm/int]
   [:scheduled-at ct/schema:inst]
   [:created-at ct/schema:inst]
   [:modified-at ct/schema:inst]
   [:started-at {:optional true} ct/schema:inst]
   [:completed-at {:optional true} ct/schema:inst]
   [:expires-at {:optional true} ct/schema:inst]
   [:resource-id {:optional true} ::sm/uuid]
   [:error {:optional true} :any]
   [:result {:optional true} :any]
   [:params-pretty ::sm/text]
   [:events [:vector schema:job-event]]])

(def ^:private params-pretty-cap
  "Hard cap for the pretty-printed params: depth/length bounds keep
  most payloads small, and this stops a pathological one from
  flooding the detail view."
  4000)

(defn- pretty-params
  [params]
  (let [text (pp/pprint-str params {:width 100 :level 5 :length 20})]
    (if (> (count text) params-pretty-cap)
      (str (subs text 0 params-pretty-cap) "… (truncated)")
      text)))

(defn- get-live-job
  "Fetch a job by id. Missing rows are `:job-not-found`: the panel
  links by id, and a GC-swept row must read as gone, not empty."
  [cfg id]
  (or (db/get* cfg :job {:id id})
      (ex/raise :type :not-found
                :code :job-not-found
                :hint (str "job " id " not found"))))

(defn- get-job-events
  "Append-only history, oldest first: insertion order is the truth,
  `created_at` alone could tie on fast transitions."
  [cfg job-id]
  (->> (db/exec! cfg ["SELECT kind, payload, created_at
                       FROM job_event WHERE job_id = ?
                       ORDER BY id" job-id])
       (mapv (fn [row]
               {:kind       (:kind row)
                :payload    (db/decode-json-pgobject (:payload row))
                :created-at (:created-at row)}))))

(def schema:get-job-params
  [:map {:title "get-job-params"}
   [:id ::sm/uuid]])

(def schema:cancel-job-params
  [:map {:title "cancel-job-params"}
   [:id ::sm/uuid]])

(def schema:cancel-job-result
  [:map
   [:id ::sm/uuid]
   [:cancelled ::sm/boolean]])

(sv/defmethod ::cancel-job
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:cancel-job-params
   ::sm/result schema:cancel-job-result}
  [cfg {:keys [id]}]
  ;; existence first: a GC-swept id reads as gone, while a job the
  ;; runner just finished reads as a no-op success, never an error
  (get-live-job cfg id)
  {:id        id
   :cancelled (pos? (jobs/cancel cfg id))})

(sv/defmethod ::get-job
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-job-params
   ::sm/result schema:job-detail}
  [cfg {:keys [id]}]
  (let [job     (get-live-job cfg id)
        owner   (when-let [profile-id (:profile-id job)]
                  (db/get* cfg :profile {:id profile-id}))
        detail  (cond-> {:id           (:id job)
                         :name         (:name job)
                         :queue        (:queue job)
                         :status       (:status job)
                         :kind         (if (some? (:profile-id job)) "user" "system")
                         :tenant       (:tenant job)
                         :priority     (:priority job)
                         :retry-num    (:retry-num job)
                         :max-retries  (:max-retries job)
                         :scheduled-at (:scheduled-at job)
                         :created-at   (:created-at job)
                         :modified-at  (:modified-at job)
                         :params-pretty (pretty-params
                                         (db/decode-json-pgobject (:params job)))
                         :events       (get-job-events cfg id)}
                  (some? (:label job))        (assoc :label (:label job))
                  (some? (:profile-id job))   (assoc :profile-id (:profile-id job))
                  (some? owner)               (assoc :owner-email (:email owner)
                                                     :owner-fullname (:fullname owner))
                  (some? (:started-at job))   (assoc :started-at (:started-at job))
                  (some? (:completed-at job)) (assoc :completed-at (:completed-at job))
                  (some? (:expires-at job))   (assoc :expires-at (:expires-at job))
                  (some? (:resource-id job))  (assoc :resource-id (:resource-id job))
                  (some? (:error job))        (assoc :error (jobs/decode-job-error (:error job)))
                  (some? (:result job))       (assoc :result (db/decode-json-pgobject (:result job))))]
    (d/without-nils detail)))
