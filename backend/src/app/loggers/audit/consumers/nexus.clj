;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.nexus
  "The Nexus transport of the audit log consumer.

  It reads the whole stream from the Nexus cursor (the oldest events
  first) and POSTs them to Nexus in batches. A batch Nexus refuses rolls
  back and stays for the next pass: the cursor does not advance, the pass
  aborts and the loop retries after its pause.

  The component starts the consumer loop at boot when its flag is on. The
  mechanics (the tick mutex, the cursor, the frontier of the pass, the GC)
  live in `app.loggers.audit.consumers`."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [app.setup :as-alias setup]
   [integrant.core :as ig]))

(defn- generate-uri
  "Joins relative path segments to the Nexus backend URI, same shape
  as the admin-console helper in `app.nitrate` (there the ingest path
  is `api/audit-log`; here it is `api/audit`).
  Segments must not start with `/`. Returns nil when no base URI is
  configured, so the caller keeps the `:task-not-configured` guard."
  [& segments]
  (when-let [base (cf/get :nexus-uri)]
    (apply cf/join-uri base segments)))

(defn- resolve-uri
  "The Nexus endpoint of this consumer: the override on the cfg, or the one
  built from `:nexus-uri`. Raises when neither is available, at boot and on
  every pass, so a consumer without a destination fails loudly instead of
  skipping silently. Failing the boot is deliberate: a worker that starts
  with its archive destination silently disabled is worse than one that
  refuses to start."
  [cfg]
  (or (some-> (::uri cfg) u/uri)
      (generate-uri "api/audit")
      (ex/raise :type :internal
                :code :task-not-configured
                :hint "consumer task not configured, missing uri")))

(defn- truncate-body
  [body limit]
  (when (some? body)
    (let [s (str body)]
      (if (> (count s) limit)
        (subs s 0 limit)
        s))))

(defn- send-events
  "POST events to Nexus. Returns the number of events sent; throws on
  transport failure or non-204 refusal, carrying the attempted count
  as :events in the error data so the caller records the failure.
  No metrics or logs here: the loop reports the aborted pass."
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

(defn- process-chunk
  "Send one chunk of the stream to Nexus. Returns {:processed n}; throws
  on refusal, so the chunk rolls back and the cursor stays where it was.
  No metrics here: a normal return means the transaction committed, so
  the caller records the success."
  [cfg rows]
  (let [events (mapv (comp consumers/row->event consumers/decode-row) rows)
        ;; send-events returns the count or throws: a refusal
        ;; aborts the pass, the rows stay for the next one.
        n      (send-events cfg events)]

    (l/trc :hint "consumer events chunk"
           :consumer "nexus"
           :events n)

    {:processed n :skipped 0}))

(defn run-pass
  "One pass of the Nexus consumer: drain the stream from its cursor up to
  the frontier of the pass. The loop of `::consumer` calls it once per
  pass; direct callers (REPL, tests) get the same behavior."
  [cfg]
  (consumers/drain-consumer (assoc cfg ::uri (resolve-uri cfg))
                            consumers/nexus
                            process-chunk))

(defmethod ig/assert-key ::consumer
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (http/client? (::http/client cfg)) "expected a valid http client")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defn- prepare
  "Resolve the destination once at boot: per-pass logs carry counts, this
  says where the consumer sends. An incomplete configuration raises here
  (see `resolve-uri`) and the worker does not start: intended."
  [cfg]
  (let [uri (resolve-uri cfg)]
    (l/dbg :hint "audit log consumer initialized"
           :consumer "nexus"
           :uri-host (u/get-domain uri))
    (assoc cfg ::uri uri)))

(defmethod ig/init-key ::consumer
  [_ cfg]
  (consumers/init-component cfg
                            consumers/nexus
                            prepare
                            run-pass))

(defmethod ig/halt-key! ::consumer
  [_ thread]
  (consumers/halt-component thread))
