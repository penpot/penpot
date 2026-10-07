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
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.jobs :as jobs]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.setup :as-alias setup]
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
   [:params {:optional true} :any]])

(sv/defmethod ::claim-job
  {::doc/added "2.20"
   ::sm/params schema:claim-job-params
   ::sm/result schema:claim-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id scheduled-at]}]
  (let [row (jobs/get-job cfg job-id)]
    ;; Race condition: if the job is claimed between get-job and claim! by
    ;; another worker, claim! returns 0 and we return :skip. The conditional
    ;; claim (UPDATE ... WHERE status IN ('new','scheduled','retry') AND
    ;; scheduled_at=?) handles this correctly: only a pending row with an
    ;; exact scheduled-at match is claimed, a stale payload is skipped.
    (if (and row (pos? (jobs/claim cfg job-id scheduled-at)))
      {:action :run
       :name   (:name row)
       :params (:params row)}
      {:action :skip})))

;; ---- RPC METHOD: REPORT-JOB-PROGRESS

(def ^:private schema:report-job-progress-params
  [:map {:title "report-job-progress-params"}
   ;; The progress schema is the same one the jobs substrate validates:
   ;; `:stage` is mandatory (a keyword of the worker vocabulary) and
   ;; `:counters` is an optional map of `scope -> {:current :total?}`.
   [:job-id ::sm/uuid]
   [:progress jobs/schema:progress]])

(def ^:private schema:report-job-progress-result
  [:map {:title "report-job-progress-result"}
   [:action [:enum :run :skip]]])

(sv/defmethod ::report-job-progress
  {::doc/added "2.20"
   ::sm/params schema:report-job-progress-params
   ::sm/result schema:report-job-progress-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id progress]}]
  ;; A lost race (row already terminal) reports :skip so the worker
  ;; stops retrying a report that can never land, mirroring claim-job.
  ;; The forced route propagates database errors: the external worker
  ;; must be able to retry a report it was not sure about. The interrupt
  ;; the beat raises when the job is no longer active is caught here for
  ;; the same reason, and it never reaches the HTTP error mapping: it is
  ;; an internal signal, not a failure of the request.
  (let [result (ex/try! (jobs/heartbeat cfg
                                        :job-id job-id
                                        :progress progress
                                        ::jobs/force? true))]
    (cond
      (ex/exception? result)
      (if (= :interrupt (:type (ex-data result)))
        {:action :skip}
        (throw result))

      (pos? result)
      {:action :run}

      :else
      {:action :skip})))

;; ---- RPC METHOD: COMPLETE-JOB

(def ^:private schema:complete-job-params
  [:map {:title "complete-job-params"}
   [:job-id ::sm/uuid]
   ;; Per-job result: any JSON-encodable value, nil when the job returns
   ;; nothing. Nested maps and vectors are allowed; unserializable
   ;; values are dropped to nil by jobs/encode-result with a warning.
   [:result [:maybe :any]]
   ;; Storage object the worker produced for this job. A resource is only
   ;; set, never replaced: a job that already has one is rejected.
   [:resource-id {:optional true} ::sm/uuid]])

(def ^:private schema:complete-job-result
  [:map {:title "complete-job-result"}
   [:action [:enum :run :skip]]])

(sv/defmethod ::complete-job
  {::doc/added "2.20"
   ::sm/params schema:complete-job-params
   ::sm/result schema:complete-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id result resource-id]}]
  (if (pos? (jobs/complete cfg
                           :job-id job-id
                           :result result
                           :resource-id resource-id))
    {:action :run}
    {:action :skip}))

;; ---- RPC METHOD: FAIL-JOB

(def ^:private schema:fail-job-params
  [:map {:title "fail-job-params"}
   [:job-id ::sm/uuid]
   ;; Rich error report: type/code/hint are required, the map stays
   ;; open to worker-defined details (see jobs/fail).
   [:error jobs/schema:job-error]])

(def ^:private schema:fail-job-result
  [:map {:title "fail-job-result"}
   [:action [:enum :run :skip]]])

(sv/defmethod ::fail-job
  {::doc/added "2.20"
   ::sm/params schema:fail-job-params
   ::sm/result schema:fail-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id error]}]
  (if (pos? (jobs/fail cfg job-id error))
    {:action :run}
    {:action :skip}))

;; ---- RPC METHOD: CREATE-JOB-SESSION

(def ^:private schema:create-job-session-params
  [:map {:title "create-job-session-params"}
   [:job-id ::sm/uuid]])

(def ^:private schema:create-job-session-result
  [:map {:title "create-job-session-result"}
   [:session-id ::sm/uuid]
   [:session-token ::sm/text]])

(defn- validate-job!
  "The job whose owner will render: it must exist, be `running` (the
  claim already passed: a session for a waiting job would outlive a
  job that may never run) and have an owner. A job of another tenant is
  indistinguishable here on purpose: the management API is the ledger
  of any row of the table, and the profile, not the tenant, is the
  boundary of what the session can touch."
  [cfg job-id]
  (let [job (jobs/get-job cfg job-id)]
    (when (nil? job)
      (ex/raise :type :not-found
                :code :job-not-found
                :hint "the job does not exist"
                :job-id job-id))
    (when-not (= "running" (:status job))
      (ex/raise :type :validation
                :code :invalid-job-state
                :hint "the session is minted for a running job"
                :status (:status job)
                :job-id job-id))
    (when-not (uuid? (:profile-id job))
      (ex/raise :type :validation
                :code :job-without-profile
                :hint "the job has no owner profile to mint the session for"
                :job-id job-id))
    (:profile-id job)))

(defn- validate-owner!
  "The profile the session will render as: the same one the login
  accepts (exists, active, not blocked, not deleted). A session is a
  key to everything the owner can touch, so a worker that settles a job
  of a dead profile never gets one."
  [cfg profile-id]
  (let [profile (db/get* cfg :profile {:id profile-id})]
    (when (or (nil? profile) (some? (:deleted-at profile)))
      (ex/raise :type :not-found
                :code :profile-not-found
                :hint "the owner profile of the job does not exist"
                :profile-id profile-id))
    (when-not (:is-active profile)
      (ex/raise :type :validation
                :code :profile-not-active
                :hint "the owner profile of the job is not active"
                :profile-id profile-id))
    (when (:is-blocked profile)
      (ex/raise :type :restriction
                :code :profile-blocked
                :hint "the owner profile of the job is marked as blocked"
                :profile-id profile-id))))

(sv/defmethod ::create-job-session
  "Mint an authentication session of the owner of a running job, for
  the worker to render as the user: render.html and assets authenticate
  by cookie and the RPC by Bearer, through the surfaces that already
  exist. Every call mints a fresh session (worker retries never wait on
  one a settle may have closed), and the token carries the short `:exp`
  of `:job-session-ttl`: a worker that dies without settling leaves
  nothing usable for long, and the sessions idle GC sweeps the row."
  {::doc/added "2.20"
   ::sm/params schema:create-job-session-params
   ::sm/result schema:create-job-session-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id]}]
  (let [profile-id (validate-job! cfg job-id)]
    (validate-owner! cfg profile-id)
    (let [session (session/create-session (::session/manager cfg)
                                          {:profile-id profile-id})
          token   (session/generate-token cfg session
                                          :exp (ct/plus (ct/now)
                                                        (cf/get-job-session-ttl)))]
      {:session-id    (:id session)
       :session-token token})))
