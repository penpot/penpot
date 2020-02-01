;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.core
  (:require
   [vertx.core :as vc]
   [vertx.timers :as vt]
   [mount.core :as mount :refer [defstate]]))

(defstate system
  :start (vc/system)
  :stop (vc/stop system))
