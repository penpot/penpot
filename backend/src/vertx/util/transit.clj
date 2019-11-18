;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.util.transit
  (:require [cognitect.transit :as t]
            [clojure.java.io :as io])
  (:import java.io.ByteArrayInputStream
           java.io.ByteArrayOutputStream
           java.time.Instant))

(def ^:private write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli v)))))

(def ^:private read-handler
  (t/read-handler
   (fn [v] (-> (Long/parseLong v)
               (Instant/ofEpochMilli)))))

(def +read-handlers+
  {"m" read-handler})

(def +write-handlers+
  {Instant write-handler})

(defmethod print-method Instant
  [mv ^java.io.Writer writer]
  (.write writer (str "#instant \"" (.toString mv) "\"")))

(defmethod print-dup Instant [o w]
  (print-method o w))

;; --- Low-Level Api

(defn reader
  ([istream]
   (reader istream nil))
  ([istream {:keys [type] :or {type :msgpack}}]
   (t/reader istream type {:handlers +read-handlers+})))

(defn read!
  "Read value from streamed transit reader."
  [reader]
  (t/read reader))

(defn writer
  ([ostream]
   (writer ostream nil))
  ([ostream {:keys [type] :or {type :msgpack}}]
   (t/writer ostream type {:handlers +write-handlers+})))

(defn write!
  [writer data]
  (t/write writer data))

;; --- High-Level Api

;; TODO: check performance of different options

(defn decode
  ([data]
   (decode data nil))
  ([data opts]
   (cond
     (string? data)
     (decode (.getBytes data "UTF-8") opts)

     (bytes? data)
     (with-open [input (ByteArrayInputStream. data)]
       (read! (reader input opts)))

     :else
     (with-open [input (io/input-stream data)]
       (read! (reader input opts))))))

(defn encode
  (^bytes [data]
   (encode data nil))
  (^bytes [data opts]
   (with-open [out (ByteArrayOutputStream.)]
     (let [w (writer out opts)]
       (write! w data)
       (.toByteArray out)))))
