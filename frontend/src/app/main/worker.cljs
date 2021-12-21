;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.worker
  (:require
   [app.config :as cfg]
   [app.util.worker :as uw]))

(defn on-error
  [error]
  (js/console.error "Error on worker" error))

(defonce instance (atom nil))

(defn init!
  []
  (reset!
   instance
   (uw/init cfg/worker-uri on-error)))

(defn ask!
  [message]
  (when @instance (uw/ask! @instance message)))

(defn ask-buffered!
  [message]
  (when @instance (uw/ask-buffered! @instance message)))

(defn ask-many!
  [message]
  (when @instance (uw/ask-many! @instance message)))
