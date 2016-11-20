;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.transit
  (:require [cognitect.transit :as t]
            [catacumba.handlers.parse :as cparse]
            [uxbox.util.time :as dt])
  (:import ratpack.http.TypedData
           ratpack.handling.Context
           java.io.ByteArrayInputStream
           java.io.ByteArrayOutputStream))

;; --- Handlers

(def ^:private +reader-handlers+
  dt/+read-handlers+)

(def ^:private +write-handlers+
  dt/+write-handlers+)

;; --- Low-Level Api

(defn reader
  ([istream]
   (reader istream nil))
  ([istream {:keys [type] :or {type :json}}]
   (t/reader istream type {:handlers +reader-handlers+})))

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


;; --- Catacumba Extension

(defmethod cparse/parse-body :application/transit+json
  [^Context ctx ^TypedData body]
  (let [reader (reader (.getInputStream body) {:type :json})]
    (read! reader)))

;; --- High-Level Api

(defn decode
  ([data]
   (decode data nil))
  ([data opts]
   (with-open [input (ByteArrayInputStream. data)]
     (read! (reader input opts)))))

(defn encode
  ([data]
   (encode data nil))
  ([data opts]
   (with-open [out (ByteArrayOutputStream.)]
     (let [w (writer out opts)]
       (write! w data)
       (.toByteArray out)))))
