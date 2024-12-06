;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.time
  "Minimal cross-platoform date time api for specific use cases on types
  definition and other common code."
  #?(:cljs
     (:require
      ["luxon" :as lxn])
     :clj
     (:import
      java.time.format.DateTimeFormatter
      java.time.Instant
      java.time.Duration)))

#?(:cljs
   (def DateTime lxn/DateTime))

#?(:cljs
   (def Duration lxn/Duration))

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
