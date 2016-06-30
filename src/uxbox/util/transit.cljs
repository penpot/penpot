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
            [uxbox.main.geom.point :as gpt]
            [uxbox.util.datetime :as dt]))

;; --- Transit Handlers

(def datetime-write-handler
  (t/write-handler (constantly "m")
                   #(str (dt/format % :offset))))

(def datetime-read-handler
  (t/read-handler
   #(dt/datetime (parse-int %))))

(def point-write-handler
  (t/write-handler
   (constantly "point")
   (fn [v]
     (let [ret #js []]
       (.push ret (:x v))
       (.push ret (:y v))
       ret))))

(def point-read-handler
  (t/read-handler
   #(gpt/point (js->clj %))))

(def ^:privare +read-handlers+
  {"u" uuid
   "m" datetime-read-handler
   "point" point-read-handler})

(def ^:privare +write-handlers+
  {dt/DateTime datetime-write-handler
   gpt/Point point-write-handler})

;; --- Public Api

(defn decode
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  [data]
  (let [w (t/writer :json {:handlers +write-handlers+})]
    (t/write w data)))
