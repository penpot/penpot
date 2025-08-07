;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.time
  "Minimal cross-platoform date time api for specific use cases on types
  definition and other common code."
  (:refer-clojure :exclude [inst?])
  #?(:cljs
     (:require
      ["luxon" :as lxn])
     :clj
     (:import
      java.time.Duration
      java.time.Instant
      java.time.format.DateTimeFormatter
      java.time.temporal.ChronoUnit
      java.time.temporal.TemporalUnit)))

#?(:cljs
   (def DateTime lxn/DateTime))

#?(:cljs
   (def Duration lxn/Duration))

(defn- resolve-temporal-unit
  [o]
  (case o
    (:nanos :nano)
    #?(:clj ChronoUnit/NANOS
       :cljs (throw (js/Error. "not supported nanos")))

    (:micros :microsecond :micro)
    #?(:clj ChronoUnit/MICROS
       :cljs (throw (js/Error. "not supported nanos")))

    (:millis :millisecond :milli)
    #?(:clj ChronoUnit/MILLIS
       :cljs "millisecond")

    (:seconds :second)
    #?(:clj ChronoUnit/SECONDS
       :cljs "second")

    (:minutes :minute)
    #?(:clj ChronoUnit/MINUTES
       :cljs "minute")

    (:hours :hour)
    #?(:clj ChronoUnit/HOURS
       :cljs "hour")

    (:days :day)
    #?(:clj ChronoUnit/DAYS
       :cljs "day")))

(defn temporal-unit
  [o]
  #?(:clj (if (instance? TemporalUnit o) o (resolve-temporal-unit o))
     :cljs (resolve-temporal-unit o)))

(defn now
  []
  #?(:clj (Instant/now)
     :cljs (.local ^js DateTime)))

(defn is-after?
  "Analgous to: da > db"
  [da db]
  (let [result (compare da db)]
    (cond
      (neg? result) false
      (zero? result) false
      :else true)))

(defn is-before?
  [da db]
  (let [result (compare da db)]
    (cond
      (neg? result)   true
      (zero? result)  false
      :else false)))

(defn instant?
  [o]
  #?(:clj (instance? Instant o)
     :cljs (instance? DateTime o)))

(defn inst?
  [o]
  #?(:clj (instance? Instant o)
     :cljs (instance? DateTime o)))

(defn parse-instant
  [s]
  (cond
    (instant? s)
    s

    (int? s)
    #?(:clj  (Instant/ofEpochMilli s)
       :cljs (.fromMillis ^js DateTime s #js {:zone "local" :setZone false}))

    (string? s)
    #?(:clj (Instant/parse s)
       :cljs (.fromISO ^js DateTime s))))

(defn inst
  [s]
  (parse-instant s))

#?(:clj
   (defn truncate
     [o unit]
     (let [unit (temporal-unit unit)]
       (cond
         (inst? o)
         (.truncatedTo ^Instant o ^TemporalUnit unit)

         (instance? Duration o)
         (.truncatedTo ^Duration o ^TemporalUnit unit)

         :else
         (throw (IllegalArgumentException. "only instant and duration allowed"))))))

(defn format-instant
  [v]
  #?(:clj (.format DateTimeFormatter/ISO_INSTANT ^Instant v)
     :cljs (.toISO ^js v)))

;; To check for valid date time we can just use the core inst? function

#?(:cljs
   (extend-protocol IComparable
     DateTime
     (-compare [it other]
       (if ^boolean (.equals it other)
         0
         (if (< (inst-ms it) (inst-ms other)) -1 1)))

     Duration
     (-compare [it other]
       (if ^boolean (.equals it other)
         0
         (if (< (inst-ms it) (inst-ms other)) -1 1)))))

#?(:cljs
   (extend-type DateTime
     cljs.core/IEquiv
     (-equiv [o other]
       (and (instance? DateTime other)
            (== (.valueOf o) (.valueOf other))))))

#?(:cljs
   (extend-protocol cljs.core/Inst
     DateTime
     (inst-ms* [inst] (.toMillis ^js inst))

     Duration
     (inst-ms* [inst] (.toMillis ^js inst)))

   :clj
   (extend-protocol clojure.core/Inst
     Duration
     (inst-ms* [v] (.toMillis ^Duration v))

     Instant
     (inst-ms* [v] (.toEpochMilli ^Instant v))))
