;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.worker
  (:require
   [app.config :as cf]
   [app.main.errors :as err]
   [app.util.worker :as uw]))

(defonce instance (atom nil))

(defn- update-public-uri!
  [instance val]
  (uw/ask! instance {:cmd :configure
                     :key :public-uri
                     :val val}))

(defn init!
  []
  (let [worker (uw/init cf/worker-uri err/on-error)]
    (update-public-uri! worker @cf/public-uri)
    (add-watch cf/public-uri ::worker-public-uri (fn [_ _ _ val] (update-public-uri! worker val)))
    (reset! instance worker)))

(defn ask!
  [message]
  (when @instance (uw/ask! @instance message)))

(defn ask-buffered!
  [message]
  (when @instance (uw/ask-buffered! @instance message)))

(defn ask-many!
  [message]
  (when @instance (uw/ask-many! @instance message)))
