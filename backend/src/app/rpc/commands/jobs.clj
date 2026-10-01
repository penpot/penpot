;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.jobs
  "Creation of durable, user-facing jobs.

  The command is generic on purpose: the type of job travels in `:name`
  and is resolved against the job registry, so a future export of assets
  joins the same surface without another one. Creating a job never runs
  it, it only freezes what it will do: the work belongs to the runner."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.jobs :as jobs]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]))

(def ^:private schema:create-export-job
  [:map {:title "create-export-job" :closed true}
   [:name        ::sm/keyword]
   [:file-ids    [::sm/set ::sm/uuid]]
   [:export-type {:optional true}
    [::sm/one-of #{:include-libraries :merge-libraries
                   :detach-libraries :link-later}]]])

(def ^:private schema:job-summary
  "What the caller gets when a job is created: enough to follow it, and
  nothing about the work it will do."
  [:map {:title "job-summary"}
   [:id ::sm/uuid]
   [:status ::sm/text]
   [:name ::sm/text]
   [:created-at ::ct/inst]
   [:expires-at [:maybe ::ct/inst]]])

(defn- check-export-job
  "The caller asks for a job by name, so the registry is the source of
  truth: a name that is not a registered export job is refused before
  anything is read or stored."
  [cfg name]
  (let [job-def (get (jobs/get-defs cfg) (keyword name))]
    (when-not (= :export (::jobs/family job-def))
      (ex/raise :type :validation
                :code :not-an-export-job
                :hint "the job name is not a registered export job"
                :name (d/name name)))))

(defn- export-params
  "The business params of the job, frozen at creation: the set of files
  does not change afterwards, so the job is reproducible."
  [params]
  {:file-ids    (set (:file-ids params))
   :export-type (get params :export-type :detach-libraries)})

(defn- job-summary
  [cfg job-id]
  (let [job (jobs/get-job cfg job-id)]
    {:id         (:id job)
     :status     (jobs/get-user-status (:status job))
     :name       (:name job)
     :created-at (:created-at job)
     :expires-at (:expires-at job)}))

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
  [cfg {:keys [::rpc/profile-id] :as params}]
  (check-export-job cfg (:name params))

  (let [name     (:name params)
        params   (export-params params)
        file-ids (:file-ids params)]

    (when (empty? file-ids)
      (ex/raise :type :validation
                :code :no-files-to-export
                :hint "expected at least one file to export"))

    (doseq [file-id file-ids]
      (files/check-read-permissions! cfg profile-id file-id))

    (quotes/check! cfg {::quotes/id ::quotes/export-jobs-per-profile
                        ::quotes/profile-id profile-id})

    (let [job-id  (jobs/submit cfg {::jobs/name        name
                                    ::jobs/params      params
                                    ::jobs/queue       :binfile
                                    ::jobs/profile-id  profile-id
                                    ::jobs/max-retries 0
                                    ::jobs/expires-at  (ct/plus (ct/now)
                                                                (cf/get-jobs-user-ttl))})
          summary (job-summary cfg job-id)]

      (with-meta summary
        {::audit/props {:job-id      job-id
                        :files       (count file-ids)
                        :export-type (d/name (:export-type params))}}))))
