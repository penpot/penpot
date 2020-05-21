;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.transit
  "A lightweight abstraction for transit serialization."
  (:require [cognitect.transit :as t]
            [uxbox.common.geom.point :as gpt]
            [uxbox.common.geom.matrix :as gmt]
            [uxbox.util.time :as dt]))

(deftype Blob [content]
  IDeref
  (-deref [_] content))

(defn blob?
  [v]
  (instance? Blob v))

(def blob-write-handler
  (t/write-handler
   (constantly "jsonblob")
   (fn [v] (js/JSON.stringify @v))))

(def blob-read-handler
  (t/read-handler
   (fn [value]
     (->Blob (js/JSON.parse value)))))

;; --- Transit adapters

(def point-write-handler
  (t/write-handler
   (constantly "point")
   (fn [v] (into {} v))))

(def point-read-handler
  (t/read-handler
   (fn [value]
     (gpt/map->Point value))))

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler
   (fn [value]
     (gmt/map->Matrix value))))

;; --- Transit Handlers

(def ^:privare +read-handlers+
  {"u" uuid
   "jsonblob" blob-read-handler
   "matrix" matrix-read-handler
   "point" point-read-handler})

(def ^:privare +write-handlers+
  {gmt/Matrix matrix-write-handler
   Blob       blob-write-handler
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
      (throw e))))
