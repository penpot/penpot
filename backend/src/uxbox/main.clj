;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main
  (:require [mount.core :as mount]
            [uxbox.config :as cfg]
            [uxbox.migrations]
            [uxbox.db]
            [uxbox.http]
            #_[uxbox.scheduled-jobs])
  (:gen-class))

;; --- Entry point (only for uberjar)

(defn -main
  [& args]
  (mount/start))
