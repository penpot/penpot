;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.transit
  "A lightweight abstraction for transit serialization."
  (:require
   [cognitect.transit :as t]
   [linked.core :as lk]
   [linked.set :as lks]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.util.time :as dt]))

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

(def bigint-read-handler
  (t/read-handler
   (fn [value]
     (js/parseInt value 10))))

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

(def ordered-set-write-handler
  (t/write-handler
   (constantly "ordered-set")
   (fn [v] (vec v))))

(def ordered-set-read-handler
  (t/read-handler #(into (lk/set) %)))

(def date-read-handler
  (t/read-handler (fn [value] (-> value (js/parseInt 10) (dt/datetime)))))

(def duration-read-handler
  (t/read-handler (fn [value] (dt/duration value))))

(def date-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (inst-ms v)))))

(def duration-write-handler
  (t/write-handler
   (constantly "duration")
   (fn [v] (inst-ms v))))

;; --- Transit Handlers

(def ^:privare +read-handlers+
  {"u"           uuid
   "n"           bigint-read-handler
   "ordered-set" ordered-set-read-handler
   "jsonblob"    blob-read-handler
   "matrix"      matrix-read-handler
   "m"           date-read-handler
   "duration"    duration-read-handler
   "point"       point-read-handler})

(def ^:privare +write-handlers+
  {gmt/Matrix    matrix-write-handler
   Blob          blob-write-handler
   dt/DateTime   date-write-handler
   dt/Duration   duration-write-handler
   lks/LinkedSet ordered-set-write-handler
   gpt/Point     point-write-handler})

;; --- Public Api

(defn decode
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  [data]
  (try
    (let [w (t/writer :json-verbose {:handlers +write-handlers+})]
      (t/write w data))
    (catch :default e
      (throw e))))

(defn transit?
  "Checks if a string can be decoded with transit"
  [str]
  (try
    (-> str decode nil? not)
    (catch js/SyntaxError e false)))
