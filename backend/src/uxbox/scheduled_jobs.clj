;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.scheduled-jobs
  "Time-based scheduled jobs."
  (:require [mount.core :as mount :refer (defstate)]
            [uxbox.config :as cfg]
            [uxbox.db]
            [uxbox.util.quartz :as qtz]))

(defn- initialize
  []
  (let [nss #{'uxbox.scheduled-jobs.garbage
              'uxbox.scheduled-jobs.emails}]
    (-> (qtz/scheduler)
        (qtz/start! {:search-on nss}))))

(defstate scheduler
  :start (initialize)
  :stop (qtz/stop! scheduler))
