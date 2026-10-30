;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.nexus
  "The Nexus transport of the audit log consumer.

  It reads the whole stream from the Nexus cursor (the oldest events
  first) and POSTs them to Nexus in batches. A batch Nexus refuses rolls
  back and stays for the next tick: the cursor does not advance, the job
  aborts and the cron retries.

  The mechanics (the cursor, the lock that keeps the events in order,
  the GC) live in `app.loggers.audit.consumers`."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [app.setup :as-alias setup]
   [integrant.core :as ig]))

(defn- generate-nexus-uri
  "Joins relative path segments to the Nexus backend URI, same shape
  as the admin-console helper in `app.nitrate` (there the ingest path
  is `api/audit-log`; here it is `api/audit`).
  Segments must not start with `/`. Returns nil when no base URI is
  configured, so the caller keeps the `:task-not-configured` guard."
  [& segments]
  (when-let [base (cf/get :nexus-uri)]
    (apply cf/join-uri base segments)))

(defn- truncate-body
  [body limit]
  (when (some? body)
    (let [s (str body)]
      (if (> (count s) limit)
        (subs s 0 limit)
        s))))

(defn- send-to-nexus
  "POST events to Nexus. Returns the number of events sent; throws on
  transport failure or non-204 refusal, carrying the attempted count
  as :events in the error data so the caller records the failure.
  No metrics or logs here: the runner reports the aborted job."
  [{:keys [::uri] :as cfg} events]
  (let [n      (count events)
        skey   (-> cfg ::setup/shared-keys :nexus)
        body   (t/encode {:events events})
        params {:uri uri
                :timeout 12000
                :method :post
                :headers {"content-type" "application/transit+json"
                          "origin" (str (cf/get :public-uri))
                          "x-shared-key" (str "nexus " skey)}
                :body body}
        resp   (try
                 (http/req cfg params {:skip-ssrf-check? true})
                 (catch Throwable cause
                   (throw (ex/raise :type :internal
                                    :code :nexus-send-failed
                                    :hint "unable to send events to nexus"
                                    :cause cause
                                    :events n))))]
    (if (= (:status resp) 204)
      n
      (throw (ex/raise :type :internal
                       :code :consumer-chunk-refused
                       :hint "nexus refused archive chunk"
                       :resp-status (:status resp)
                       :resp-body (truncate-body (:body resp) 500)
                       :events n)))))

(defn- process-nexus-chunk
  "Send one chunk of the stream to Nexus. Returns {:processed n}; throws
  on refusal, so the chunk rolls back and the cursor stays where it was.
  No metrics here: a normal return means the transaction committed, so
  the caller records the success."
  [cfg rows]
  (let [events (mapv (comp consumers/row->event consumers/decode-row) rows)
        ;; send-to-nexus returns the count or throws: a refusal
        ;; aborts the job, the rows stay for the next tick.
        n      (send-to-nexus cfg events)]

    (l/trc :hint "consumer events chunk"
           :consumer "nexus"
           :events n)

    {:processed n :skipped 0}))

(def schema:audit-log-consumer-nexus-params
  "Overrides for the nexus job repl invocation defaults.
  Cron submits {}; system deps (pool, shared-keys, http) live on the
  job-def cfg closed over by the handler, not in job params.
  Closed on purpose: a stale key (like the old :enabled) must fail
  validation loudly instead of skipping silently."
  [:map {:closed true}
   [:uri {:optional true} ::sm/uri]])

(declare execute-audit-log-consumer-nexus)

(defmethod ig/assert-key ::job-def
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (http/client? (::http/client cfg)) "expected a valid http client")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  ;; Effective destination, once at boot: per-run logs carry counts,
  ;; the runner says the job started, this says where it sends.
  (l/dbg :hint "audit log consumer job initialized"
         :consumer "nexus"
         :uri-host (some-> (cf/get :nexus-uri) u/uri u/get-domain))
  {::jobs/name      :audit-log-consumer-nexus
   ::jobs/schema    schema:audit-log-consumer-nexus-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-consumer-nexus cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-consumer-nexus-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-consumer-nexus-params)})

(defn execute-audit-log-consumer-nexus
  "Nexus job handler: drain the stream from the Nexus cursor in chunks,
  then clean up (heartbeat per iteration: the sent chunk batches can be
  long)."
  [cfg params]
  ;; NOTE: this let allows overwrite default configured values from
  ;; the repl, when manually invoking the task.
  (if-not (contains? cf/flags :nexus)
    (l/dbg :hint "audit log consumer (nexus) skipped, flag off")
    (let [uri (or (:uri params) (generate-nexus-uri "api/audit"))
          ;; Normalize to an uri object; params may carry a plain string
          ;; on direct invocations (validation only applies on submit!).
          uri (u/uri uri)
          cfg (assoc cfg ::uri uri)]
      (when-not uri
        (ex/raise :type :internal
                  :code :task-not-configured
                  :hint "consumer task not configured, missing uri"))
      (consumers/drain-consumer cfg consumers/consumer-nexus process-nexus-chunk))))
