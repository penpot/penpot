;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.core
  (:require
   [vertx.core :as vx]
   [mount.core :as mount :refer [defstate]]))

(defstate system
  :start (vx/system)
  :stop (.close system))


