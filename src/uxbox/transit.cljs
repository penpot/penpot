;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.transit
  "A lightweight abstraction for transit serialization."
  (:refer-clojure :exclude [do])
  (:require [cognitect.transit :as t]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.util.datetime :as dt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read/Write Transit handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private datetime-write-handler
  (reify
    Object
    (tag [_ v] "m")
    (rep [_ v] (dt/format v :offset))
    (stringRep [this v] (str (dt/format v :offset)))))

(defn- datetime-read-handler
  [v]
  (dt/datetime (parse-int v)))

(def ^:privare +read-handlers+
  {"u" uuid
   "m" datetime-read-handler})

(def ^:privare +write-handlers+
  {js/moment datetime-write-handler})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn decode
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  [data]
  (let [w (t/writer :json {:handlers +write-handlers+})]
    (t/write w data)))
