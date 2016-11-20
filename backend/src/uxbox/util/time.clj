;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.time
  (:require [suricatta.proto :as proto]
            [cognitect.transit :as t])
  (:import java.time.Instant
           java.sql.Timestamp))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Serialization Layer conversions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare from-string)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Persistence Layer Conversions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-protocol proto/IParamType
  Instant
  (-render [self ctx]
    (if (proto/-inline? ctx)
      (str "'" (.toString self) "'::timestamptz")
      "?::timestamptz"))

  (-bind [self ctx]
    (when-not (proto/-inline? ctx)
      (let [stmt (proto/-statement ctx)
            idx  (proto/-next-bind-index ctx)
            obj (Timestamp/from self)]
        (.setTimestamp stmt idx obj)))))

(extend-protocol proto/ISQLType
  Timestamp
  (-convert [self]
    (.toInstant self)))

(defmethod print-method Instant
  [mv ^java.io.Writer writer]
  (.write writer (str "#instant \"" (.toString mv) "\"")))

(defmethod print-dup Instant [o w]
  (print-method o w))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn from-string
  [s]
  {:pre [(string? s)]}
  (Instant/parse s))

(defn now
  []
  (Instant/now))
