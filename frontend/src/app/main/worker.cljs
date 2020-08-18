;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.worker
  (:require
   [cljs.spec.alpha :as s]
   [app.config :as cfg]
   [app.common.spec :as us]
   [app.util.worker :as uw]))

(defn on-error
  [instance error]
  (js/console.error "Error on worker" (.-data error)))

(defonce instance
  (when (not= *target* "nodejs")
    (uw/init cfg/worker-uri on-error)))

(defn ask!
  [message]
  (uw/ask! instance message))
