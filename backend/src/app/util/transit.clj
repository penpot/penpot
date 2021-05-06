;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.transit
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [cognitect.transit :as t]
   [linked.core :as lk])
  (:import
   app.common.geom.matrix.Matrix
   app.common.geom.point.Point
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.File
   java.time.Instant
   java.time.OffsetDateTime
   linked.set.LinkedSet))

;; --- Handlers

(def ^:private file-write-handler
  (t/write-handler
   (constantly "file")
   (fn [v] (str v))))

;; --- GEOM

(def point-write-handler
  (t/write-handler
   (constantly "point")
   (fn [v] (into {} v))))

(def point-read-handler
  (t/read-handler gpt/map->Point))

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler gmt/map->Matrix))

;; --- Ordered Set

(def ordered-set-write-handler
  (t/write-handler
   (constantly "ordered-set")
   (fn [v] (vec v))))

(def ordered-set-read-handler
  (t/read-handler #(into (lk/set) %)))


;; --- TIME

(def ^:private instant-read-handler
  (t/read-handler
   (fn [v] (-> (Long/parseLong v)
               (Instant/ofEpochMilli)))))

(def ^:private instant-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli ^Instant v)))))

(def ^:private offset-datetime-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli (.toInstant ^OffsetDateTime v))))))

(def +read-handlers+
  {"matrix"      matrix-read-handler
   "ordered-set" ordered-set-read-handler
   "point"       point-read-handler
   "m"           instant-read-handler
   "instant"     instant-read-handler})

(def +write-handlers+
  {File           file-write-handler
   LinkedSet      ordered-set-write-handler
   Matrix         matrix-write-handler
   Point          point-write-handler
   Instant        instant-write-handler
   OffsetDateTime offset-datetime-write-handler})

;; --- Low-Level Api

(defn reader
  ([istream]
   (reader istream nil))
  ([istream {:keys [type] :or {type :json}}]
   (t/reader istream type {:handlers +read-handlers+})))

(defn read!
  "Read value from streamed transit reader."
  [reader]
  (t/read reader))

(defn writer
  ([ostream]
   (writer ostream nil))
  ([ostream {:keys [type] :or {type :json}}]
   (t/writer ostream type {:handlers +write-handlers+})))

(defn write!
  [writer data]
  (t/write writer data))

;; --- High-Level Api

(declare str->bytes)
(declare bytes->str)

(defn decode-stream
  ([input]
   (decode-stream input nil))
  ([input opts]
   (read! (reader input opts))))

(defn decode
  ([data]
   (decode data nil))
  ([data opts]
   (with-open [input (ByteArrayInputStream. ^bytes data)]
     (decode-stream input opts))))

(defn encode-stream
  ([data out]
   (encode-stream data out nil))
  ([data out opts]
   (let [w (writer out opts)]
     (write! w data))))

(defn encode
  ([data]
   (encode data nil))
  ([data opts]
   (with-open [out (ByteArrayOutputStream.)]
     (encode-stream data out opts)
     (.toByteArray out))))

(defn decode-str
  [message]
  (->> (str->bytes message)
       (decode)))

(defn encode-str
  ([message]
   (->> (encode message)
        (bytes->str)))
  ([message opts]
   (->> (encode message opts)
        (bytes->str))))

(defn encode-verbose-str
  [message]
  (->> (encode message {:type :json-verbose})
       (bytes->str)))

;; --- Helpers

(defn str->bytes
  "Convert string to byte array."
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn bytes->str
  "Convert byte array to String."
  ([^bytes data]
   (bytes->str data "UTF-8"))
  ([^bytes data, ^String encoding]
   (String. data encoding)))
