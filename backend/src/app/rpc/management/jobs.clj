;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.jobs
  "Generic job management API for external workers (media and future
  subsystems). External workers never touch the database: this API is
  the ledger. The methods are family-agnostic (they operate on any row
  of the `job` table) and use the plain JSON schemas of `app.jobs`.

  Auth: every method declares ::rpc/auth false because these endpoints
  do not require a profile. Actual authentication is enforced by the
  management route resolver (resolve-management-methods in app.rpc),
  which mandates a valid shared-key before dispatching to these methods.
  If the route resolver changes, these methods must be updated accordingly."
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.jobs :as jobs]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]))

;; ---- RPC METHOD: CLAIM-JOB

(def ^:private schema:claim-job-params
  [:map {:title "claim-job-params"}
   [:job-id ::sm/uuid]
   [:scheduled-at ::ct/inst]])

(def ^:private schema:claim-job-result
  [:map {:title "claim-job-result"}
   [:action [:enum :run :skip]]
   [:name {:optional true} ::sm/text]
   ;; Opaque per-job params blob; intentionally untyped.
   [:props {:optional true} :any]])

(sv/defmethod ::claim-job
  {::doc/added "2.19"
   ::sm/params schema:claim-job-params
   ::sm/result schema:claim-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id scheduled-at]}]
  (let [row (jobs/get-job cfg job-id)]
    ;; Race condition: if the job is claimed between get-job and claim! by
    ;; another worker, claim! returns 0 and we return :skip. The conditional
    ;; claim (UPDATE ... WHERE status='new') handles this correctly.
    (if (and row (pos? (jobs/claim! cfg job-id scheduled-at)))
      {:action :run
       :name   (:name row)
       :props  (:props row)}
      {:action :skip})))

;; ---- RPC METHOD: REPORT-JOB-PROGRESS

(def ^:private schema:report-job-progress-params
  [:map {:title "report-job-progress-params"}
   [:job-id ::sm/uuid]
   [:progress [:map
               [:total :int]
               [:current :int]
               [:stage {:optional true} ::sm/text]]]])

(def ^:private schema:report-job-progress-result
  [:map {:title "report-job-progress-result"}])

(sv/defmethod ::report-job-progress
  {::doc/added "2.19"
   ::sm/params schema:report-job-progress-params
   ::sm/result schema:report-job-progress-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id progress]}]
  (jobs/progress! cfg job-id progress)
  {})

;; ---- RPC METHOD: COMPLETE-JOB

(def ^:private schema:complete-job-params
  [:map {:title "complete-job-params"}
   [:job-id ::sm/uuid]
   ;; Per-job result: flat map of JSON scalars, nil when the job returns
   ;; nothing. The key is always present; results must survive db/json.
   [:result [:maybe [:map-of :keyword [:or :string :int :double :boolean :nil]]]]])

(def ^:private schema:complete-job-result
  [:map {:title "complete-job-result"}])

(sv/defmethod ::complete-job
  {::doc/added "2.19"
   ::sm/params schema:complete-job-params
   ::sm/result schema:complete-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id result]}]
  (jobs/complete! cfg job-id result)
  {})

;; ---- RPC METHOD: FAIL-JOB

(def ^:private schema:fail-job-params
  [:map {:title "fail-job-params"}
   [:job-id ::sm/uuid]
   ;; Rich error report: type/code/hint are required, the map stays
   ;; open to worker-defined details (see jobs/fail!).
   [:error [:map
            [:type :keyword]
            [:code ::sm/text]
            [:hint ::sm/text]]]])

(def ^:private schema:fail-job-result
  [:map {:title "fail-job-result"}])

(sv/defmethod ::fail-job
  {::doc/added "2.19"
   ::sm/params schema:fail-job-params
   ::sm/result schema:fail-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id error]}]
  (jobs/fail! cfg job-id error)
  {})
