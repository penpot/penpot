;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.worker
  "Interface to communicate with the web worker"
  (:require
   [app.config :as cf]
   [app.util.worker :as uw]
   [beicon.v2.core :as rx]))

;; Injected from `app.main.errors` to remove circular dependency
(defonce on-error nil)

(defonce instance nil)

;; Changes when an update is posted, before an older reply can reach the UI.
(defonce selection-index-revision (volatile! 0))

(defn init!
  []
  (let [worker (uw/init cf/worker-uri on-error)]
    (vswap! selection-index-revision inc)
    (uw/ask! worker {:cmd :configure
                     :config {:public-uri cf/public-uri
                              :build-data cf/build-date
                              :version cf/version
                              :version-tag cf/version-tag}})
    (set! instance worker)))

(defn ask!
  ([message]
   (ask! message nil))
  ([message transfer]
   (if instance
     (do
       (when (#{:index/initialize :index/update} (:cmd message))
         (vswap! selection-index-revision inc))
       (uw/ask! instance message transfer))
     (rx/empty))))

(defn emit!
  ([message]
   (if instance
     (uw/emit! instance message)
     (rx/empty)))
  ([message transfer]
   (if instance
     (uw/emit! instance message transfer)
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
