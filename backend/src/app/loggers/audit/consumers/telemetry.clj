;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.telemetry
  "The telemetry transport of the audit log consumer.

  It reads the stream from its own cursor (id 0) and sends the anonymized
  copies (the shadow rows, `source LIKE 'telemetry:%'`) to the telemetry
  endpoint. The full rows are not its business: they count as skipped and
  the cursor passes over them, so a deployment with any full-row consumer
  still ships its telemetry.

  The payload is the one the legacy cron sent: the events alone, without
  the row id or the cursor columns. The endpoint always answers 200 and
  counts its own errors; a refusal rolls the chunk back, so the rows stay
  for the next pass and the cursor does not advance.

  The component starts the consumer loop at boot when its flag is on. The
  mechanics (the tick mutex, the cursor, the frontier of the pass, the GC)
  live in `app.loggers.audit.consumers`."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [app.setup :as-alias setup]
   [app.util.json :as json]
   [integrant.core :as ig]))

(defn- resolve-uri
  "The telemetry endpoint of this consumer, configured as `:telemetry-uri`
  (it has a default). Raises at boot when it is not available, so a consumer
  without a destination fails loudly instead of skipping silently."
  []
  (or (some-> (cf/get :telemetry-uri) u/uri)
      (ex/raise :type :internal
                :code :task-not-configured
                :hint "telemetry consumer not configured, missing uri")))

(defn- row->event
  "The view of a shadow row that travels to the endpoint, the same one the
  legacy cron built: no row id, no `created-at` and no `ip-addr` (the copy
  already carries `0.0.0.0`)."
  [{:keys [name type source tracked-at profile-id props context]}]
  (d/without-nils
   {:name       name
    :type       type
    :source     source
    :tracked-at tracked-at
    :profile-id profile-id
    :props      (or props {})
    :context    (or context {})}))

(defn- send-events
  "POST a batch of events to the telemetry endpoint. Answers the number of
  events sent; throws on transport failure or a status above 206, carrying
  the attempted count as :events in the error data so the caller records the
  failure. No metrics or logs here: the pass reports the aborted batch."
  [cfg events]
  (let [n      (count events)
        uri    (::uri cfg)
        body   (json/encode-str {:type        :telemetry-events
                                 :version     (:full cf/version)
                                 :instance-id (:instance-id (::setup/props cfg))
                                 :events      events})
        params {:uri uri
                :timeout 12000
                :method :post
                :headers {"content-type" "application/json"}
                :body body}
        resp   (try
                 (http/req cfg params {:skip-ssrf-check? true})
                 (catch Throwable cause
                   (throw (ex/raise :type :internal
                                    :code :telemetry-send-failed
                                    :hint "unable to send events to the telemetry endpoint"
                                    :cause cause
                                    :events n))))]
    (if (<= (:status resp) 206)
      n
      (throw (ex/raise :type :internal
                       :code :consumer-chunk-refused
                       :hint "telemetry endpoint refused the batch"
                       :resp-status (:status resp)
                       :events n)))))

(defn- process-chunk
  "Send the shadow rows of one chunk to the endpoint. Returns
  {:processed n :skipped n}: the full rows count as skipped and the cursor
  advances over them, they are not its business. Throws on refusal, so the
  chunk rolls back and the cursor stays where it was."
  [cfg rows]
  (let [events (into []
                     (comp (filter (comp audit/telemetry-shadow-source? :source))
                           (map (comp row->event consumers/decode-row)))
                     rows)
        total  (count rows)]
    (when (seq events)
      (send-events cfg events)
      (l/trc :hint "events chunk sent"
             :events (count events)))
    {:processed (count events)
     :skipped   (- total (count events))}))

(defn- prepare
  "Resolve the destination once at boot: per-pass logs carry counts, this
  says where the consumer sends. An incomplete configuration raises here and
  the worker does not start: intended."
  [cfg]
  (let [uri (resolve-uri)]
    (l/dbg :hint "audit log consumer initialized"
           :consumer "telemetry"
           :host (u/get-domain uri))
    (assoc cfg ::uri uri)))

(def options
  "The copies keep the pace of the legacy cron: one pass every three hours
  with up to 10 000 events per request. A pass every five seconds would
  multiply the requests to the endpoint by thousands."
  {:chunk-size 10000
   :pause-ms   10800000})

(def consumer
  "The anonymized copies travel to the telemetry endpoint."
  {:id      consumers/telemetry
   :flag    :telemetry
   :label   "telemetry"
   :options options
   :prepare prepare
   :process process-chunk})

(defmethod ig/init-key ::consumer
  [_ cfg]
  (consumers/init cfg consumer))

(defmethod ig/halt-key! ::consumer
  [_ thread]
  (consumers/halt thread))

(defmethod ig/assert-key ::consumer
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (http/client? (::http/client cfg)) "expected a valid http client")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance")
  (assert (some? (::setup/props cfg)) "expected setup props to be available"))
