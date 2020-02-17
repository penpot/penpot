;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks
  "Async tasks abstraction (impl)."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.core :refer [system]]
   [uxbox.db :as db]
   [uxbox.tasks.sendmail]
   [uxbox.tasks.remove-media]
   [uxbox.tasks.delete-profile]
   [uxbox.tasks.delete-object]
   [uxbox.tasks.impl :as impl]
   [uxbox.util.time :as dt]
   [vertx.core :as vc]
   [vertx.timers :as vt]))

;; --- Public API

(defn schedule!
  ([opts] (schedule! db/pool opts))
  ([conn opts]
   (s/assert ::impl/task-options opts)
   (impl/schedule! conn opts)))

;; --- State initialization

;; TODO: missing self maintanance task; when the queue table is full
;; of completed/failed task, the performance starts degrading
;; linearly, so after some arbitrary number of tasks is processed, we
;; need to perform a maintenance and delete some old tasks.

(def ^:private tasks
  {"delete-profile" #'uxbox.tasks.delete-profile/handler
   "delete-object" #'uxbox.tasks.delete-object/handler
   "remove-media" #'uxbox.tasks.remove-media/handler
   "sendmail" #'uxbox.tasks.sendmail/handler})

(defstate tasks-worker
  :start (as-> (impl/worker-verticle {:tasks tasks}) $$
           (vc/deploy! system $$ {:instances 1})
           (deref $$)))

;; (def ^:private schedule
;;   [{:id "every 1 hour"
;;     :cron (dt/cron "1 1 */1 * * ? *")
;;     :fn #'uxbox.tasks.gc/handler
;;     :props {:foo 1}}])

;; (defstate scheduler
;;   :start (as-> (impl/scheduler-verticle {:schedule schedule}) $$
;;            (vc/deploy! system $$ {:instances 1 :worker true})
;;            (deref $$)))
