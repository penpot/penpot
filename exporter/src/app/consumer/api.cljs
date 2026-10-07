;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.consumer.api
  "The backend management API client.

  The only place of the worker that speaks HTTP: every method of the
  backend ledger (claim, progress, session, settle) travels through
  here, with the shared key the exporter was configured with and, when
  a call renders as a user, the Bearer token of its session. Request
  bodies travel as transit+json (except the artifact form of
  `complete-job`, which is multipart, like every other upload) and the
  answers come back as transit too."
  (:require
   ["node:fs/promises" :as fsp]
   ["undici" :as http]
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [cljs.core :as c]
   [promesa.core :as p]))

(def fetch
  "The network entry point of the client: a plain def, redefined by the
  tests of the client, which never need a network."
  http/fetch)

;; ---- THE REQUEST

(defn- dispatcher
  "The agent of one request: a self-signed certificate happens in
  deployments the same way the existing uploads already accept."
  []
  (new http/Agent #js {:connect #js {:rejectUnauthorized false}}))

(defn- method-uri
  [method]
  (-> (cf/get-internal-uri)
      (u/ensure-path-slash)
      (u/join (str "api/management/methods/" (c/name method)))
      (str)))

(defn- shared-key
  "What every management call carries: the key id of this exporter plus
  the key, and the wish to read transit back."
  []
  #js {"X-Shared-Key" (str "exporter " cf/management-key)
       "Accept" "application/transit+json"})

(def ^:private parse-response
  "The answer of a management call: transit when it lands. A non-200 is
  the failure of the call, with the error body the backend answered;
  a body that does not decode stays as the plain text it came as."
  (fn [response]
    (let [status (.-status response)]
      (->> (.text response)
           (p/fmap (fn [text]
                     (let [body (let [decoded (ex/try! (t/decode-str text))]
                                  (if-not (ex/exception? decoded) decoded text))]
                       (if (= status 200)
                         body
                         (ex/raise :type :internal
                                   :code :failed-management-request
                                   :status status
                                   :response body)))))))))

(defn- request
  "POST `params` to a management method as transit+json."
  [method params]
  (->> (fetch (method-uri method)
              #js {:method     "POST"
                   :headers    (shared-key)
                   :body       (t/encode-str params)
                   :dispatcher (dispatcher)})
       (p/mcat parse-response)))

(defn- request-multipart
  "POST a FormData body to a management method. The content type is
  settled by the FormData itself (boundary included), so only the
  shared key goes in the headers."
  [method form-data]
  (->> (fetch (method-uri method)
              #js {:method     "POST"
                   :headers    (shared-key)
                   :body       form-data
                   :dispatcher (dispatcher)})
       (p/mcat parse-response)))

;; ---- THE METHODS

(defn claim-job
  "Request a queued job from the backend. The answer is either
  `{:action :run :name ... :params ...}` (this poller owns the job) or
  `{:action :skip ...}` (something else won it, or it is gone)."
  [job-id scheduled-at]
  (request :claim-job {:job-id job-id :scheduled-at scheduled-at}))

(defn report-job-progress
  "The heartbeat of a running job: the stage hitos of the progress
  contract plus the counters of the current work. A `:skip` says the
  job can no longer hear the worker (cancelled, aborted, settled)."
  [job-id progress]
  (request :report-job-progress {:job-id job-id :progress progress}))

(defn create-job-session
  "Mint an authentication session of the owner of a running job, so the
  render views authenticate as the user, not as a machine."
  [job-id]
  (request :create-job-session {:job-id job-id}))

(defn complete-job
  "Close a job whose result is the plain value `result`, with an
  optional `:resource-id` this worker already owns (the internal path)
  and an optional `:session-id` to close."
  ([job-id result]
   (complete-job job-id result nil))
  ([job-id result {:keys [resource-id session-id]}]
   (request :complete-job
            (cond-> {:job-id job-id :result result}
              (some? resource-id) (assoc :resource-id resource-id)
              (some? session-id)  (assoc :session-id session-id)))))

(defn complete-job-with-artifact
  "Close a job whose result is the artifact itself: the backend stores
  the bytes and derives the result descriptor. `artifact` says what it
  is (`:path`, `:filename`, `:mtype`); `ctx` names the job and, when
  the worker rendered with a session, the one to close."
  [{:keys [job-id session-id]} {:keys [path filename mtype]}]
  (->> (fsp/readFile (str path))
       (p/fmap (fn [buffer]
                 (let [blob  (new js/Blob #js [buffer] #js {:type mtype})
                       fdata (new http/FormData)]
                   (.append fdata "job-id" (str job-id))
                   (.append fdata "content" blob filename)
                   (.append fdata "filename" filename)
                   (.append fdata "mtype" (str mtype))
                   (when session-id
                     (.append fdata "session-id" (str session-id)))
                   fdata)))
       (p/mcat (fn [fdata]
                 (request-multipart :complete-job fdata)))))

(defn fail-job
  "Close a job as failed with the rich error the backend stores for
  `job.error`. `:session-id` closes the render session the same way."
  [job-id error & {:keys [session-id]}]
  (request :fail-job
           (cond-> {:job-id job-id :error error}
             (some? session-id) (assoc :session-id session-id))))
