;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.worker
  (:require
   [app.config :as cf]
   [app.main.errors :as err]
   [app.util.worker :as uw]
   [beicon.v2.core :as rx]))

(defonce instance nil)

(defn init!
  []
  (let [worker (uw/init cf/worker-uri err/on-error)]
    (uw/ask! worker {:cmd :configure
                     :key :public-uri
                     :val cf/public-uri})
    (set! instance worker)))

(defn ask!
  ([message]
   (if instance
     (uw/ask! instance message)
     (rx/empty)))
  ([message transfer]
   (if instance
     (uw/ask! instance message transfer)
     (rx/empty))))

(defn ask-buffered!
  ([message]
   (if instance
     (uw/ask-buffered! instance message)
     (rx/empty)))
  ([message transfer]
   (if instance
     (uw/ask-buffered! instance message transfer)
     (rx/empty))))

(defn ask-many!
  ([message]
   (if instance
     (uw/ask-many! instance message)
     (rx/empty)))
  ([message transfer]
   (if instance
     (uw/ask-many! instance message transfer)
     (rx/empty))))
