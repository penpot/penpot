;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.worker
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.metrics :as mtx]
   [uxbox.tasks.delete-object]
   [uxbox.tasks.delete-profile]
   [uxbox.tasks.gc]
   [uxbox.tasks.remove-media]
   [uxbox.tasks.sendmail]
   [uxbox.tasks.trim-file]
   [uxbox.util.time :as dt]
   [uxbox.worker-impl :as impl]))

;; --- State initialization

(def ^:private tasks
  {"delete-profile" #'uxbox.tasks.delete-profile/handler
   "delete-object" #'uxbox.tasks.delete-object/handler
   "remove-media" #'uxbox.tasks.remove-media/handler
   "sendmail" #'uxbox.tasks.sendmail/handler})

(def ^:private schedule
  [{:id "remove-deleted-media"
    :cron (dt/cron "0 0 0 */1 * ? *") ;; daily
    :fn #'uxbox.tasks.gc/remove-deleted-media}
   {:id "trim-file"
    :cron (dt/cron "0 0 0 */1 * ? *") ;; daily
    :fn #'uxbox.tasks.trim-file/handler}
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
