;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.transit
  "A lightweight abstraction for transit serialization."
  (:require [cognitect.transit :as t]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.time :as dt]))

;; --- Transit Handlers

(def ^:privare +read-handlers+
  {"u" uuid
   "m" dt/instant-read-handler
   "matrix" gmt/matrix-read-handler
   "point" gpt/point-read-handler})

(def ^:privare +write-handlers+
  {dt/Instant dt/instant-write-handler
   gmt/Matrix gmt/matrix-write-handler
   gpt/Point gpt/point-write-handler})

;; --- Public Api

(defn decode
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  [data]
  (try
    (let [w (t/writer :json {:handlers +write-handlers+})]
      (t/write w data))
    (catch :default e
      (println "data:" data)
      (throw e))))

