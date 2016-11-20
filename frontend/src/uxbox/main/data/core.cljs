;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.core
  "Worker related api and initialization events."
  (:require [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.workers :as uw]
            [uxbox.main.constants :as c]))

;; This excludes webworker instantiation on nodejs where
;; the tests are run.

(when (not= *target* "nodejs")
  (defonce worker (uw/init "/js/worker.js")))

