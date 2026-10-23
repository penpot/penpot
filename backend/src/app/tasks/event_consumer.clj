;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.event-consumer
  "The generalist of the audit-log event consumer: it reads the stream
  of events and lets each reaction decide what concerns it.

  The consumer itself (the id 3 of `audit_log_pending`) is not selective:
  it queues every event and hands it to the reactions. A reaction is a
  map:

    {:id     :team-storage-usage   ;; who it is
     :flag   :storage-usage        ;; the flag that turns it on
     :events #{...}                ;; the event names it cares about
     :apply  (fn [cfg events] ...)} ;; what it does with them

  Each domain contributes its own: the one that owns the table and the
  SQL of its mark is the one that knows which events affect it and
  where its key lives.

  A reaction receives the whole batch of events it declares, once: a
  reaction that groups work (marking a team dirty) must not write once
  per event. The dispatch does not catch exceptions: the reaction
  decides which errors are data (it skips the event) and which abort
  the batch (the event is retried on the next tick)."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumer-task :as consumer]
   [app.metrics :as mtx]
   [app.tasks.team-storage :as team-storage]
   [integrant.core :as ig]))

(def reactions
  "The registered reactions, in the order they are applied."
  [team-storage/team-storage-reaction])

(defn enabled-reactions
  "The reactions whose flag is on."
  []
  (filterv #(contains? cf/flags (:flag %)) reactions))

(defn- claimed-names
  "The names the enabled reactions claim."
  []
  (into #{} (mapcat :events) (enabled-reactions)))

(defn- claimed-by
  "The enabled reactions that declare the name of this event."
  [reactions event]
  (filterv #(contains? (:events %) (:name event)) reactions))

(defn dispatch-reactions
  "Applies the enabled reactions to the events. Each reaction receives
  only the events it declares, and it receives them in a single batch.

  Returns the number of events some reaction claimed: the ones nobody
  claimed are discarded, because the queue of the consumer is not
  selective and that event concerns nobody."
  [cfg events]
  (let [reactions (enabled-reactions)
        batches   (keep (fn [reaction]
                          (let [mine (filterv #(contains? (:events reaction) (:name %)) events)]
                            (when (seq mine)
                              [reaction mine])))
                        reactions)]
    (doseq [[reaction mine] batches]
      ((:apply reaction) cfg mine))
    (count (filter (fn [event]
                     (seq (claimed-by reactions event)))
                   events))))

(defn- process-events-chunk
  "Dispatch one pending chunk to the reactions. Returns {:sent :skipped}:
  the events a reaction claims count as sent, the rest as skipped.

  Only the claimed events are decoded: the queue is not selective, so
  most of the stream concerns nobody and opening its blob would be
  work thrown away."
  [cfg rows]
  (let [names   (claimed-names)
        claimed (filterv #(contains? names (:name %)) rows)
        batch   (mapv consumer/row->reaction-event claimed)
        sent    (dispatch-reactions cfg batch)]
    (l/trc :hint "consumer events chunk"
           :consumer "events"
           :events sent)
    {:sent sent :skipped (- (count rows) sent)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-audit-log-consumer-events
  "Events job handler: drain the events queue in chunks, dispatching
  each event to the reactions that claim it (heartbeat per iteration:
  a burst can span many chunks)."
  [cfg]
  (if-not (contains? cf/flags :event-consumer)
    (l/dbg :hint "audit log consumer (events) skipped, flag off")
    (consumer/drain-consumer cfg consumer/consumer-events process-events-chunk)))

(def schema:audit-log-consumer-events-params
  "Params of the events job: none. Cron submits {}; system deps (pool)
  live on the job-def cfg closed over by the handler, not in job params.
  Closed on purpose, same as the admin-console schema."
  [:map {:closed true}])

(defmethod ig/assert-key ::events-job-def
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::events-job-def
  [_ cfg]
  (l/dbg :hint "audit log consumer job initialized"
         :consumer "events")
  {::jobs/name      :audit-log-consumer-events
   ::jobs/schema    schema:audit-log-consumer-events-params
   ::jobs/handler
   (fn [_context _params]
     (execute-audit-log-consumer-events cfg))
   ::jobs/decoder   (sm/decoder schema:audit-log-consumer-events-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-consumer-events-params)})
