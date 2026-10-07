;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.jobs
  "Creation of durable, user-facing jobs.

  One command per job type: the body carries the business params its
  job-def declares and nothing else, and the job-def schema is the single
  place that says what those params are. Creating a job never runs it: it
  freezes what the job will do, and the work belongs to the runner."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as bf.v3]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.media :as media]
   [app.rpc.commands.projects :as projects]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.tasks.export-binfile :as export-binfile]
   [app.tasks.import-binfile :as import-binfile]
   [app.util.services :as sv]
   [datoteka.fs :as fs]))

(def ^:private schema:create-export-binfile-job
  "The `.penpot` package of the files frozen in the params, produced by
  the backend runner; the business params are what the `:export-binfile`
  job-def declares."
  [:map {:title "create-export-binfile-job" :closed true}
   [:params export-binfile/schema:params]])

(def ^:private schema:create-import-binfile-job
  "The `.penpot` package the caller uploaded, imported into a project by
  the backend runner; the business params are what the `:import-binfile`
  job-def declares for creation."
  [:map {:title "create-import-binfile-job" :closed true}
   [:params import-binfile/schema:create-params]
   ;; the package arrives as a chunked upload and never as a path the
   ;; caller names: see `assemble-upload`
   [:upload-id ::sm/uuid]])

(def ^:private schema:job-summary
  "What the caller gets when a job is created: enough to follow it, and
  nothing about the work it will do."
  [:map {:title "job-summary"}
   [:id ::sm/uuid]
   [:status ::sm/text]
   [:name ::sm/text]
   [:created-at ::ct/inst]
   [:expires-at [:maybe ::ct/inst]]])

(defn- resolve-job-def
  "The job the caller named, refused when it is not a job of `family`: the
  registry is the source of truth, and a name that is not one of its jobs
  is rejected before anything is read or stored.

  The job-def is returned because the caller needs it right away: it is
  what decodes and validates the business params."
  [cfg family name]
  (let [job-def (get (jobs/get-defs cfg) (keyword name))]
    (when-not (= family (::jobs/family job-def))
      (ex/raise :type :validation
                :code :not-a-job-of-the-family
                :hint "the job name is not a registered job of that family"
                :name (d/name name)
                :family (d/name family)))
    job-def))

(defn- get-job-summary
  [cfg job-id]
  (let [job (jobs/get-job cfg job-id)]
    {:id         (:id job)
     :status     (jobs/get-user-status (:status job))
     :name       (:name job)
     :created-at (:created-at job)
     :expires-at (:expires-at job)}))

(defn- submit-job
  "Create the job row with what every user job shares: the queue its
  job-def routes the heavy work to (`::jobs/queue-name`, `:binfile` when
  the def says nothing), no automatic retry and the expiry of the ledger."
  [cfg job-def name params profile-id & {:keys [resource-id]}]
  (jobs/submit cfg (cond-> {::jobs/name        name
                            ::jobs/params      params
                            ::jobs/queue       (or (::jobs/queue-name job-def)
                                                   :binfile)
                            ::jobs/profile-id  profile-id
                            ::jobs/max-retries 0
                            ::jobs/expires-at  (ct/plus (ct/now)
                                                        (cf/get-jobs-user-ttl))}
                     (some? resource-id)
                     (assoc ::jobs/resource-id resource-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sv/defmethod ::create-export-binfile-job
  "Create a durable job that exports a set of files as a `.penpot`
   package.

   Its params are `:file-ids`, the set of files to export, and
   `:export-type`, how the libraries of those files are handled: what
   the job-def declares, and nothing more. There is no default for the
   type, because a caller that does not say it would get a package that
   is not the one it asked for.

   The job is created, not run: the caller follows it by its id. The
   read permission of every file is checked here so a refusal arrives
   synchronously, and the handler checks it again before reading."
  {::doc/added "2.20"
   ::webhooks/event? true
   ::sm/params schema:create-export-binfile-job
   ::sm/result schema:job-summary}
  [cfg {:keys [::rpc/profile-id] :as envelope}]
  (let [job-def  (resolve-job-def cfg :export :export-binfile)
        ;; the RPC layer decoded the params against the job-def schema
        ;; before the command ran
        params   (jobs/validate-params job-def (:params envelope))
        file-ids (:file-ids params)]

    (when (empty? file-ids)
      (ex/raise :type :validation
                :code :no-files-to-export
                :hint "expected at least one file to export"))

    (doseq [file-id file-ids]
      (files/check-read-permissions! cfg profile-id file-id))

    (quotes/check! cfg {::quotes/id ::quotes/export-jobs-per-profile
                        ::quotes/profile-id profile-id})

    (let [summary (get-job-summary cfg (submit-job cfg job-def :export-binfile
                                                   params profile-id))]
      (with-meta summary
        {::audit/props {:job-id      (:id summary)
                        :files       (count file-ids)
                        :export-type (d/name (:export-type params))}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- assemble-upload
  "The package the caller uploaded, assembled into a local file that
  belongs to this call.

  The command only takes a chunked upload: a plain file upload would mean
  reading a path the caller named, and the server must never read a file
  it did not write itself."
  [cfg profile-id upload-id]
  (db/tx-run! cfg media/assemble-chunks profile-id upload-id))

(defn- resolve-version
  "The version of the package: what the caller says, or what the header
  of the file says."
  [path version]
  (or version
      (case (bfc/parse-file-format path)
        :binfile-v3 3
        1)))

(sv/defmethod ::create-import-binfile-job
  "Create a durable job that imports a `.penpot` package into a project.

  The package arrives as a chunked upload, which the server assembles
  itself: the command never reads a path the caller names. The package is
  then checked and stored as the resource of the job, so any worker can
  consume it by `resource_id`. The job is created, not run, and the caller
  follows it by its id."
  {::doc/added "2.20"
   ::webhooks/event? true
   ::sm/params schema:create-import-binfile-job
   ::sm/result schema:job-summary
   ;; assembling the upload and storing the package happens here, in the
   ;; request: the same limit the legacy import command declares
   ::climit/id [[:create-import-binfile-job/by-profile ::rpc/profile-id]
                [:create-import-binfile-job/global]]}
  [cfg {:keys [::rpc/profile-id] :as envelope}]
  (let [job-def    (resolve-job-def cfg :import :import-binfile)
        ;; the RPC layer decoded the params against the create schema
        ;; before the command ran
        given      (:params envelope)
        project-id (:project-id given)]

    ;; the edition permission is checked here so a refusal arrives
    ;; synchronously and before the upload is assembled; the handler
    ;; checks it again before writing
    (projects/check-edition-permissions! cfg profile-id project-id)

    (quotes/check! cfg {::quotes/id ::quotes/import-jobs-per-profile
                        ::quotes/profile-id profile-id})

    ;; the shape of what the caller sent is checked before the upload
    ;; is assembled: the version is known good here (the create schema
    ;; rejects one out of range, and the caller may omit it), so the
    ;; merged map below only adds what the package says
    (jobs/validate-params job-def (assoc given :version (or (:version given) 1)))

    (let [file (assemble-upload cfg profile-id (:upload-id envelope))
          path (:path file)]

      ;; everything that happens once the upload is on disk belongs to
      ;; this try: a failure anywhere in it must not leave the temporary
      ;; file behind
      ;;
      ;; the version and the manifest metadata are resolved here, in the
      ;; command, and not in the job: only the creation path reads the
      ;; package, and the job keeps what the audit will show
      (try
        (let [version  (resolve-version path (:version given))
              manifest (when (= 3 version)
                         (bf.v3/get-manifest cfg path))
              ;; the manifest may name the tool that produced the package
              ;; with either spelling: resolve it once, for the params and
              ;; for the audit
              referer  (or (:referer manifest) (:refer manifest))
              params   (cond-> (assoc given :version version)
                         manifest
                         (assoc :generated-by (:generated-by manifest)
                                :referer referer))]

          ;; the params are complete here: the version came from the
          ;; caller or from the package, and the manifest filled the audit
          (jobs/validate-params job-def params)

          (let [staged (js/put-resource cfg profile-id
                                        {:content  (sto/content path)
                                         :filename "package.penpot"
                                         :mtype    "application/zip"})]
            (try
              (let [summary (get-job-summary cfg (submit-job cfg job-def :import-binfile params profile-id
                                                             :resource-id (:resource-id staged)))]
                (with-meta summary
                  {::audit/props {:job-id      (:id summary)
                                  :generated-by (:generated-by manifest)
                                  :referer      referer}}))

              (catch Throwable cause
                ;; the job never came to own the package, so it must not be
                ;; left for the storage GC to find late
                (js/release-resource cfg (:resource-id staged))
                (throw cause)))))

        (finally
          ;; the assembled file belongs to this call: the package is
          ;; already in storage by the time we get here, or the creation
          ;; failed and it is of no use to anyone
          (fs/delete path))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; READ
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:get-job
  [:map {:title "get-job" :closed true}
   [:id ::sm/uuid]])

(def ^:private schema:job-detail
  "The summary of a job plus what only exists once it is over: the result
  it produced or, when it failed, its public error."
  [:map {:title "job-detail"}
   [:id ::sm/uuid]
   [:status ::sm/text]
   [:name ::sm/text]
   [:created-at ::ct/inst]
   [:expires-at [:maybe ::ct/inst]]
   [:result {:optional true} :any]
   [:error {:optional true} :any]])

(defn- get-job-detail
  "The job the caller owns, as the answer of `get-job`. A job that does
  not exist and a job of another profile get the same answer: the caller
  cannot tell them apart."
  [cfg profile-id job-id]
  (let [job (jobs/get-job cfg job-id)]
    (when-not (and (some? job) (= profile-id (:profile-id job)))
      (ex/raise :type :not-found
                :code :job-not-found
                :hint "the job does not exist or belongs to another profile"
                :job-id job-id))
    (cond-> {:id         (:id job)
             :status     (jobs/get-user-status (:status job))
             :name       (:name job)
             :created-at (:created-at job)
             :expires-at (:expires-at job)}
      (some? (:result job))
      (assoc :result (:result job))

      (some? (:error job))
      (assoc :error (jobs/decode-job-error (:error job))))))

(sv/defmethod ::get-job
  "Read a job the caller created: its state and, once it is over, what it
  produced or why it failed.

  The result of a finished job is the plain JSON the row holds, because a
  result has no schema of its own like the params do: a uuid comes back as
  the text it is on disk. The progress and the history of the job are not
  part of the answer: a client that follows a job takes its progress from
  the websocket, and the event log still has no read surface."
  {::doc/added "2.20"
   ::sm/params schema:get-job
   ::sm/result schema:job-detail}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (get-job-detail cfg profile-id (:id params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CANCEL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:cancel-job
  [:map {:title "cancel-job" :closed true}
   [:id ::sm/uuid]])

(sv/defmethod ::cancel-job
  "Cancel a job the caller created, whether it is still waiting or
  already running.

  A running job stops at its next beat and rolls its work back; a job
  that already finished between the request and the update is answered
  with the state it is in, never an error. Like every other command
  here, a job of another profile is indistinguishable from one that does
  not exist."
  {::doc/added "2.20"
   ::sm/params schema:cancel-job
   ::sm/result schema:job-summary}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (let [job-id (:id params)
        job    (jobs/get-job cfg job-id)]
    (when-not (and (some? job) (= profile-id (:profile-id job)))
      (ex/raise :type :not-found
                :code :job-not-found
                :hint "the job does not exist or belongs to another profile"
                :job-id job-id))
    (jobs/cancel cfg job-id)
    ;; the answer names the outcome of this call, not the collapsed
    ;; user status: a job the caller just cancelled reads "cancelled"
    (assoc (get-job-summary cfg job-id)
           :status (:status (jobs/get-job cfg job-id)))))
