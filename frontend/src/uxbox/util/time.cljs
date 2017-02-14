;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.time
  (:require [cljsjs.moment]
            [cognitect.transit :as t]))

;; --- Instant Impl

(deftype Instant [^number v]
  IComparable
  (-compare [this other]
    (if (instance? Instant other)
      (compare v (.-v other))
      (throw (js/Error. (str "Cannot compare " this " to " other)))))

  IEquiv
  (-equiv [this other]
    (if (instance? Instant other)
      (-equiv v (.-v other))
      false))

  IPrintWithWriter
  (-pr-writer [_ writer _]
    (let [m (js/moment v)
          ms (.toISOString m)]
      (-write writer (str "#instant \"" ms "\""))))

  IHash
  (-hash [_] v)

  Object
  (toString [this]
    (let [m (js/moment v)]
      (.toISOString m)))

  (equiv [this other]
    (-equiv this other)))

(defn instant
  "Create a new Instant instance from
  unix offset."
  [v]
  {:pre [(number? v)]}
  (Instant. v))

(defn instant?
  "Return true if `v` is an instance of Instant."
  [v]
  (instance? Instant v))

(defn parse
  "Parse a string representation of instant
  with an optional `format` parameter."
  ([v] (parse v :offset))
  ([v fmt]
   (cond
     (instant? v) v
     (= fmt :offset) (Instant. v)
     :else (let [m (if (= fmt :unix)
                     (js/moment.unix v)
                     (js/moment v fmt))]
             (Instant. (.valueOf m))))))

(defn format
  "Returns a string representation of the Instant
  instance with optional `fmt` format parameter.

  You can use `:iso` and `:unix` shortcuts as
  format parameter.

  You can read more about format tokens here:
  http://momentjs.com/docs/#/displaying/format/
  "
  ([v] (format v :iso))
  ([v fmt]
   {:pre [(instant? v)]}
   (let [vm (js/moment (.-v v))]
     (case fmt
       :unix (.unix vm)
       :offset (.valueOf vm)
       :iso (.toISOString vm)
       (.format vm fmt)))))

(defn now
  "Return the current Instant."
  []
  (let [vm (js/moment)]
    (Instant. (.valueOf vm))))

(defn timeago
  [v]
  (let [dt (parse v)
        vm (js/moment (.-v dt))]
    (.fromNow vm)))

;; --- Transit Adapter

(def instant-write-handler
  (t/write-handler
   (constantly "m")
   #(str (format % :offset))))

(def instant-read-handler
  (t/read-handler #(instant (js/parseInt % 10))))
