;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.transit
  "A lightweight abstraction for transit serialization."
  (:require
   [cognitect.transit :as t]
   [linked.core :as lk]
   [linked.set :as lks]
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

;; --- Transit Handlers

(def ^:privare +read-handlers+
  {"u" uuid
   "ordered-set" ordered-set-read-handler
   "jsonblob" blob-read-handler
   "matrix" matrix-read-handler
   "point" point-read-handler})

(def ^:privare +write-handlers+
  {gmt/Matrix    matrix-write-handler
   Blob          blob-write-handler
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
    (let [w (t/writer :json {:handlers +write-handlers+})]
      (t/write w data))
    (catch :default e
      (throw e))))

(defn transit?
  "Checks if a string can be decoded with transit"
  [str]
  (try
    (-> str decode nil? not)
    (catch js/SyntaxError e false)))
