;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.consumer.config
  "The worker role of the exporter configuration.

  The exporter process may serve its http surfaces, consume the
  backend `:exporter` queue, or both: `PENPOT_EXPORTER_ROLES` names the
  roles (`http` by default, `http,worker` during the transition). The
  worker pieces never read these values at import time: each accessor
  reads the live configuration, so a long-lived process can be
  restarted with another role without code changes."
  (:require
   [app.config :as cf]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(def queue-name
  "The queue this worker consumes: the one the `:export-assets` job-def
  routes its jobs to through `::jobs/queue-name`."
  "exporter")

(defn- scan-roles
  [spec]
  (->> (if (string? spec)
         (str/split spec ",")
         spec)
       (map str/trim)
       (remove str/blank?)
       (map keyword)
       (into #{})))

(defn roles
  "The set of roles this instance serves, as keywords."
  ([] (roles (cf/get :exporter-roles)))
  ([spec]
   (if (nil? spec)
     #{:http}
     (scan-roles spec))))

(defn worker-enabled?
  "True when this instance also consumes the exporter queue."
  ([] (contains? (roles) :worker))
  ([roles]
   (contains? roles :worker)))

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
