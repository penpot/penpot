;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.time
  (:require [suricatta.proto :as sp]
            [suricatta.impl :as si]
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

(extend-protocol sp/IParam
  Instant
  (-param [self ctx]
    (si/sql->param "{0}::timestamptz" (.toString self))))

(extend-protocol sp/ISQLType
  Timestamp
  (-convert [self]
    (.toInstant self))

  java.time.OffsetDateTime
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
