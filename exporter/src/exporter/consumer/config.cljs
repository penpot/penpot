;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.consumer.config
  "The knobs of the consumer: how many jobs run at once and which
  queue they come from. Read live, so a long-lived process obeys a
  configuration change on the next poll."
  (:require
   [app.config :as cf]
   [cljs.core :as c]))

(def queue-name
  "The queue this worker consumes: the one the `:export-assets` job-def
  routes its jobs to through `::jobs/queue-name`."
  "exporter")

(defn concurrency
  "K pollers: each one owns its Redis connection, and the blocking
  connection is the semaphore of the slot, so K is the whole concurrency
  of the process. At least one: a worker role with zero pollers is a
  misconfiguration, not a rest.

  Takes the configured value as an argument, so the tests do not
  journey through the global config; the zero-arity reads it live."
  ([]
   (concurrency (c/get cf/config :exporter-worker-concurrency)))
  ([value]
   (max 1 (if (number? value) value 2))))

(defn queue-key
  "The Redis list the dispatcher pushes this queue's payloads through:
  `penpot.worker.queue:<tenant>:<queue>`."
  ([] (queue-key (cf/get :tenant)))
  ([tenant]
   (str "penpot.worker.queue:" tenant ":" queue-name)))
