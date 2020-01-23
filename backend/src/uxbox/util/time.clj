;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.time
  (:require
   #_[suricatta.proto :as sp]
   #_[suricatta.impl :as si]
   [cognitect.transit :as t])
  (:import java.time.Instant
           java.time.OffsetDateTime
           java.time.Duration))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Serialization Layer conversions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare from-string)

(def ^:private instant-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli v)))))

(def ^:private offset-datetime-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli (.toInstant v))))))

(def ^:private read-handler
  (t/read-handler
   (fn [v] (-> (Long/parseLong v)
               (Instant/ofEpochMilli)))))

(def +read-handlers+
  {"m" read-handler})

(def +write-handlers+
  {Instant instant-write-handler
   OffsetDateTime offset-datetime-write-handler})

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

(defn- obj->duration
  [{:keys [days minutes seconds hours nanos millis]}]
  (cond-> (Duration/ofMillis (if (int? millis) ^long millis 0))
    (int? days) (.plusDays ^long days)
    (int? hours) (.plusHours ^long hours)
    (int? minutes) (.plusMinutes ^long minutes)
    (int? seconds) (.plusSeconds ^long seconds)
    (int? nanos) (.plusNanos ^long nanos)))

(defn duration?
  [v]
  (instance? Duration v))

(defn duration
  [ms-or-obj]
  (cond
    (duration? ms-or-obj)
    ms-or-obj

    (integer? ms-or-obj)
    (Duration/ofMillis ms-or-obj)

    :else
    (obj->duration ms-or-obj)))

(extend-protocol clojure.core/Inst
  java.time.Duration
  (inst-ms* [v] (.toMillis ^java.time.Duration v)))


