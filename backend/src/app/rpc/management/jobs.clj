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
  If the route resolver changes, these methods must be updated accordingly.

  Every write method answers `{:action :run}` when it landed and
  `{:action :skip}` when it did not, with the state of the row it found
  (read fresh after the failed write) in `:status`: the worker tells a
  cancelled job apart from one it was too late for, without guessing."
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.media.validation :as media.v]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.setup :as-alias setup]
   [app.storage :as sto]
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
   [:params {:optional true} :any]
   ;; On `:skip`, the state of the row the write lost against.
   [:status {:optional true} ::sm/text]])

(defn- row-status
  "The state of the row after a write that touched nothing: one fresh
  read, never the row the method already saw, so the answer names the
  state the race produced."
  [cfg job-id]
  (:status (jobs/get-job cfg job-id)))

(defn- skip-with-status
  "The answer of a landed-nowhere write: one fresh read of the row
  after the failure, so the worker sees the state the race produced."
  [cfg job-id]
  (if-let [status (row-status cfg job-id)]
    {:action :skip :status status}
    {:action :skip}))

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
      (skip-with-status cfg job-id))))

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
   [:action [:enum :run :skip]]
   [:status {:optional true} ::sm/text]])

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
        (skip-with-status cfg job-id)
        (throw result))

      (pos? result)
      {:action :run}

      :else
      (skip-with-status cfg job-id))))

;; ---- RPC METHOD: COMPLETE-JOB

(def ^:private schema:complete-job-params
  [:and
   [:map {:title "complete-job-params"}
    [:job-id ::sm/uuid]
    ;; Per-job result: any JSON-encodable value, nil when the job returns
    ;; nothing. Nested maps and vectors are allowed; unserializable
    ;; values are dropped to nil by jobs/encode-result with a warning.
    [:result {:optional true} [:maybe :any]]
    ;; Storage object the worker produced for this job. A resource is only
    ;; set, never replaced: a job that already has one is rejected.
    [:resource-id {:optional true} ::sm/uuid]
    ;; The multipart form: the worker sends the artifact itself, the
    ;; server stores it. `filename` and `mtype` name what the user will
    ;; download; they say nothing about the bytes (the `content` field
    ;; carries its own type).
    [:content {:optional true} media.v/schema:upload]
    [:filename {:optional true} ::sm/text]
    [:mtype {:optional true} ::sm/text]
    ;; The render session the worker used, closed once the job settles:
    ;; complete is terminal, the session has no reader after it.
    [:session-id {:optional true} ::sm/uuid]]
   [:fn {:error/message "one of :content, :result or :resource-id is required"}
    (fn [{:keys [content resource-id] :as params}]
      (boolean (or (some? content)
                   (contains? params :result)
                   (some? resource-id))))]])

(def ^:private schema:complete-job-result
  [:map {:title "complete-job-result"}
   [:action [:enum :run :skip]]
   [:status {:optional true} ::sm/text]])

(defn- close-job-session!
  "The session a worker rendered with, deleted once the job settles.
  Best-effort: the settle already decided the outcome, and a session
  that cannot be deleted now is swept later by the sessions idle GC."
  [cfg session-id]
  (when (uuid? session-id)
    (ex/ignoring (session/delete-session (::session/manager cfg) session-id))))

(defn- check-artifact!
  "The bytes a worker offers as the result: the type must be one the
  export produces and the size under the configured cap. Both checks
  refuse to *use* the bytes, not to receive them (the multipart parser
  already wrote them to a server tempfile); refusing early costs
  nothing, storing early costs a GC cycle."
  [content]
  (media.v/validate-media-type! content cm/export-artifact-types)
  (let [max-size (cf/get-exporter-max-result-size)]
    (when (> (:size content) max-size)
      (ex/raise :type :validation
                :code :request-body-too-large
                :hint "the result of the job is over the maximum size"
                :size (:size content)
                :max-size max-size)))
  content)

(defn- completable-job
  "The row a completion may land on: running (or retrying), whatever
  the JSON form asks for."
  [cfg job-id]
  (let [job (jobs/get-job cfg job-id)]
    (when (and (some? job)
               (contains? #{"running" "retry"} (:status job)))
      job)))

(defn- artifact-owner
  "The job an artifact completion may land on: completable AND owned,
  because the object needs an owner and a job without one owns nothing
  (it may only settle without an artifact). Read before anything is
  stored: a lost race with a cancel never turns the bytes of the worker
  into an object."
  [cfg job-id]
  (let [job (completable-job cfg job-id)]
    (when (and (some? job)
               (uuid? (:profile-id job)))
      job)))

(defn- complete-with-artifact
  "The multipart form of a completion, on a job that is still
  completable: the artifact is stored out of any transaction (storage
  waits for no database), owned by the profile of the job, and the
  completion itself writes the `resource_id` and the result descriptor
  `{resource-uri filename mtype size}` in its own transaction, where
  the first terminal state wins. A completion that lost the race
  releases the artifact it stored: no object lingers for the storage GC
  to find later."
  [cfg job content filename mtype session-id]
  (let [resource (js/put-resource cfg
                                  (:profile-id job)
                                  {:content  (sto/content (:path content))
                                   :filename filename
                                   :mtype    mtype})]
    (if (pos? (jobs/complete cfg
                             :job-id (:id job)
                             :resource-id (:resource-id resource)
                             :result {:resource-uri (:resource-uri resource)
                                      :filename     filename
                                      :mtype        mtype
                                      :size         (:size resource)}))
      (do
        (close-job-session! cfg session-id)
        {:action :run})
      (do
        (js/release-resource cfg (:resource-id resource))
        (skip-with-status cfg (:id job))))))

(sv/defmethod ::complete-job
  "Close a running job as completed, in one of two forms.

  The JSON form carries the plain `:result` the job produced and an
  optional storage object (`:resource-id`) the worker wrote itself: the
  internal binfile path.

  The multipart form carries the artifact itself (`:content`, with
  `:filename` and `:mtype` naming the download): the server stores it,
  owned by the profile of the job, and coalesces the resource into the
  same completion. The checks (completable, type, size) run before any
  object is written: a lost race against a cancel never stores bytes.

  With `:session-id`, the render session the worker minted through
  `create-job-session` is deleted after the completion commits.

  A completion of a job that already ended answers `{:action :skip,
  :status ...}` and changes nothing."
  {::doc/added "2.20"
   ::sm/params schema:complete-job-params
   ::sm/result schema:complete-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id result resource-id content filename mtype session-id]}]
  (cond
    ;; the multipart form stores the artifact itself: only for a job
    ;; that can still complete and has an owner, because the object
    ;; needs one
    (some? content)
    (if-let [job (artifact-owner cfg job-id)]
      (do
        (check-artifact! content)
        (complete-with-artifact cfg job content filename mtype session-id))
      (skip-with-status cfg job-id))

    ;; the JSON form: nothing to store here, the resource (if any)
    ;; already lives in storage
    :else
    (if (pos? (jobs/complete cfg
                             :job-id job-id
                             :result result
                             :resource-id resource-id))
      (do
        (close-job-session! cfg session-id)
        {:action :run})
      (skip-with-status cfg job-id))))

;; ---- RPC METHOD: FAIL-JOB

(def ^:private schema:fail-job-params
  [:map {:title "fail-job-params"}
   [:job-id ::sm/uuid]
   ;; Rich error report: type/code/hint are required, the map stays
   ;; open to worker-defined details (see jobs/fail).
   [:error jobs/schema:job-error]
   ;; The render session the worker used, closed once the job settles:
   ;; fail is terminal too.
   [:session-id {:optional true} ::sm/uuid]])

(def ^:private schema:fail-job-result
  [:map {:title "fail-job-result"}
   [:action [:enum :run :skip]]
   [:status {:optional true} ::sm/text]])

(sv/defmethod ::fail-job
  {::doc/added "2.20"
   ::sm/params schema:fail-job-params
   ::sm/result schema:fail-job-result
   ::rpc/auth false} ;; shared-key enforced by route resolver
  [cfg {:keys [job-id error session-id]}]
  (if (pos? (jobs/fail cfg job-id error))
    (do
      (close-job-session! cfg session-id)
      {:action :run})
    (skip-with-status cfg job-id)))

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
