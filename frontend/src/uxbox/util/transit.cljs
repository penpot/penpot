;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.transit
  "A lightweight abstraction for transit serialization."
  (:require [cognitect.transit :as t]
            [com.cognitect.transit :as tr]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.time :as dt]))

;; --- Transit Handlers

(def instant-write-handler
  (t/write-handler (constantly "m")
                   #(str (dt/format % :offset))))

(def instant-read-handler
  (t/read-handler
   #(dt/instant (parse-int %))))

(def point-write-handler
  (t/write-handler
   (constantly "point")
   (fn [v] (into {} v))))

(def point-read-handler
  (t/read-handler
   (fn [value]
     (if (array? value)
       (gpt/point (vec value))
       (gpt/map->Point value)))))

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler
   (fn [value]
     (gmt/map->Matrix value))))

(def ^:privare +read-handlers+
  {"u" uuid
   "m" instant-read-handler
   "matrix" matrix-read-handler
   "point" point-read-handler})

(def ^:privare +write-handlers+
  {dt/Instant instant-write-handler
   gmt/Matrix matrix-write-handler
   gpt/Point point-write-handler})

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

