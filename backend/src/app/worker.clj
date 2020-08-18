;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.worker
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.tasks.delete-object]
   [app.tasks.delete-profile]
   [app.tasks.gc]
   [app.tasks.remove-media]
   [app.tasks.sendmail]
   [app.tasks.trim-file]
   [app.util.time :as dt]
   [app.worker-impl :as impl]))

;; --- State initialization

(def ^:private tasks
  {"delete-profile" #'app.tasks.delete-profile/handler
   "delete-object" #'app.tasks.delete-object/handler
   "remove-media" #'app.tasks.remove-media/handler
   "sendmail" #'app.tasks.sendmail/handler})

(def ^:private schedule
  [{:id "remove-deleted-media"
    :cron (dt/cron "0 0 0 */1 * ? *") ;; daily
    :fn #'app.tasks.gc/remove-deleted-media}
   {:id "trim-file"
    :cron (dt/cron "0 0 0 */1 * ? *") ;; daily
    :fn #'app.tasks.trim-file/handler}
   ])


(defstate executor
  :start (impl/thread-pool {:idle-timeout 10000
                            :min-threads 0
                            :max-threads 256})
  :stop (impl/stop! executor))

(defstate worker
  :start (impl/start-worker!
          {:tasks tasks
           :name "worker1"
           :batch-size 1
           :executor executor})
  :stop (impl/stop! worker))

(defstate scheduler-worker
  :start (impl/start-scheduler-worker! {:schedule schedule
                                        :executor executor})
  :stop (impl/stop! scheduler-worker))
