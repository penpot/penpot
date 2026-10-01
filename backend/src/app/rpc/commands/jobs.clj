;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.jobs
  "Creation of durable, user-facing jobs.

  The commands are generic on purpose: the type of job travels in `:name`
  and its business params in `:params`, and the registry decides whether
  that name is a job of the family the command serves. Creating a job
  never runs it: it freezes what the job will do, and the work belongs to
  the runner."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as bf.v3]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media.validation :as media.v]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.media :as media]
   [app.rpc.commands.projects :as projects]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.util.services :as sv]
   [datoteka.fs :as fs]))

(def ^:private schema:job-params
  "The business params of a job. The job-def of the name the caller asked
  for is what validates them, so a new type of job needs no change here."
  [:map])

(def ^:private schema:create-export-job
  [:map {:title "create-export-job" :closed true}
   [:name   ::sm/keyword]
   [:params schema:job-params]])

(def ^:private schema:create-import-job
  [:and
   [:map {:title "create-import-job" :closed true}
    [:name      ::sm/keyword]
    [:params    schema:job-params]
    [:file      {:optional true} media.v/schema:upload]
    [:upload-id {:optional true} ::sm/uuid]]
   [:fn {:error/message "one of :file or :upload-id is required"}
    (fn [{:keys [file upload-id]}]
      (or (some? file) (some? upload-id)))]])

(def ^:private schema:job-summary
  "What the caller gets when a job is created: enough to follow it, and
  nothing about the work it will do."
  [:map {:title "job-summary"}
   [:id ::sm/uuid]
   [:status ::sm/text]
   [:name ::sm/text]
   [:created-at ::ct/inst]
   [:expires-at [:maybe ::ct/inst]]])

(defn- check-family
  "The caller asks for a job by name, so the registry is the source of
  truth: a name that is not a job of `family` is refused before anything
  is read or stored. The job-def is what validates the business params."
  [cfg family name]
  (let [job-def (get (jobs/get-defs cfg) (keyword name))]
    (when-not (= family (::jobs/family job-def))
      (ex/raise :type :validation
                :code :not-a-job-of-the-family
                :hint "the job name is not a registered job of that family"
                :name (d/name name)
                :family (d/name family)))
    job-def))

(defn- job-summary
  [cfg job-id]
  (let [job (jobs/get-job cfg job-id)]
    {:id         (:id job)
     :status     (jobs/get-user-status (:status job))
     :name       (:name job)
     :created-at (:created-at job)
     :expires-at (:expires-at job)}))

(defn- submit-job
  "Create the job row with what every user job shares: the queue of the
  heavy work, no automatic retry and the expiry of the ledger."
  [cfg name params profile-id & {:keys [resource-id]}]
  (jobs/submit cfg (cond-> {::jobs/name        name
                            ::jobs/params      params
                            ::jobs/queue       :binfile
                            ::jobs/profile-id  profile-id
                            ::jobs/max-retries 0
                            ::jobs/expires-at  (ct/plus (ct/now)
                                                        (cf/get-jobs-user-ttl))}
                     (some? resource-id)
                     (assoc ::jobs/resource-id resource-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- export-params
  "The business params of an export, with the export type the legacy
  callers expect when they do not say one."
  [params]
  (update params :export-type #(or % :detach-libraries)))

(sv/defmethod ::create-export-job
  "Create a durable job that exports a set of files as a `.penpot`
  package.

  The job is created, not run: the caller follows it by its id. The read
  permission of every file is checked here so a refusal arrives
  synchronously, and the handler checks it again before reading."
  {::doc/added "2.20"
   ::webhooks/event? true
   ::sm/params schema:create-export-job
   ::sm/result schema:job-summary}
  [cfg {:keys [::rpc/profile-id] :as envelope}]
  (let [name     (:name envelope)
        job-def  (check-family cfg :export name)
        params   (jobs/validate-params job-def (export-params (:params envelope)))
        file-ids (:file-ids params)]

    (when (empty? file-ids)
      (ex/raise :type :validation
                :code :no-files-to-export
                :hint "expected at least one file to export"))

    (doseq [file-id file-ids]
      (files/check-read-permissions! cfg profile-id file-id))

    (quotes/check! cfg {::quotes/id ::quotes/export-jobs-per-profile
                        ::quotes/profile-id profile-id})

    (let [summary (job-summary cfg (submit-job cfg name params profile-id))]

      (with-meta summary
        {::audit/props {:job-id      (:id summary)
                        :files       (count file-ids)
                        :export-type (d/name (:export-type params))}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- assemble-upload
  "The package the caller uploaded, as a local file. A chunked upload is
  assembled here and belongs to this call; a plain upload arrives as a
  file the http layer owns."
  [cfg profile-id {:keys [file upload-id]}]
  (if (some? upload-id)
    (assoc (db/tx-run! cfg media/assemble-chunks profile-id upload-id)
           ::owned? true)
    (assoc file ::owned? false)))

(defn- resolve-version
  "The version of the package: what the caller says, or what the header
  of the file says."
  [path version]
  (or version
      (case (bfc/parse-file-format path)
        :binfile-v3 3
        1)))

(sv/defmethod ::create-import-job
  "Create a durable job that imports a `.penpot` package into a project.

  The package is assembled, checked and stored as the resource of the
  job, so any worker can consume it by `resource_id`. The job is created,
  not run, and the caller follows it by its id."
  {::doc/added "2.20"
   ::webhooks/event? true
   ::sm/params schema:create-import-job
   ::sm/result schema:job-summary}
  [cfg {:keys [::rpc/profile-id] :as envelope}]
  (let [name       (:name envelope)
        job-def    (check-family cfg :import name)
        project-id (:project-id (:params envelope))]

    ;; the edition permission is checked here so a refusal arrives
    ;; synchronously and before the upload is assembled; the handler
    ;; checks it again before writing
    (projects/check-edition-permissions! cfg profile-id project-id)

    (quotes/check! cfg {::quotes/id ::quotes/import-jobs-per-profile
                        ::quotes/profile-id profile-id})

    (let [file     (assemble-upload cfg profile-id envelope)
          path     (:path file)
          version  (resolve-version path (:version (:params envelope)))
          manifest (when (= 3 version)
                     (bf.v3/get-manifest cfg path))
          params   (cond-> (assoc (:params envelope) :version version)
                     manifest
                     (assoc :generated-by (:generated-by manifest)
                            :referer (or (:referer manifest) (:refer manifest))))

          ;; the params are complete here: the version came from the
          ;; caller or from the package, and the manifest filled the audit
          _        (jobs/validate-params job-def params)]

      (try
        (let [staged (js/put-resource cfg profile-id
                                      {:content  (sto/content path)
                                       :filename "package.penpot"
                                       :mtype    "application/zip"})]
          (try
            (let [summary (job-summary cfg (submit-job cfg name params profile-id
                                                       :resource-id (:resource-id staged)))]
              (with-meta summary
                {::audit/props {:job-id      (:id summary)
                                :generated-by (:generated-by manifest)
                                :referer      (:referer manifest)}}))

            (catch Throwable cause
              ;; the job never came to own the package, so it must not be
              ;; left for the storage GC to find late
              (js/release-input cfg {:id          (uuid/next)
                                     :resource-id (:resource-id staged)})
              (throw cause))))

        (finally
          (when (::owned? file)
            (fs/delete path)))))))
