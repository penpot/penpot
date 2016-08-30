;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.datetime
  (:require [cljsjs.moment]))

;; A simplified immutable date time representation type.

(deftype DateTime [^number v]
  IComparable
  (-compare [this other]
    (if (instance? DateTime other)
      (compare v (.-v other))
      (throw (js/Error. (str "Cannot compare " this " to " other)))))

  IPrintWithWriter
  (-pr-writer [_ writer _]
    (let [m (js/moment v)
          ms (.toISOString m)]
      (-write writer (str "#datetime \"" ms "\""))))

  Object
  (toString [this]
    (let [m (js/moment v)]
      (.toISOString m)))

  (equiv [this other]
    (-equiv this other))

  IHash
  (-hash [_] v))

(alter-meta! #'->DateTime assoc :private true)

(defn datetime
  "Create a new DateTime instance from
  unix offset."
  [v]
  {:pre [(number? v)]}
  (DateTime. v))

(defn datetime?
  "Return true if `v` is an instance of DateTime."
  [v]
  (instance? DateTime v))

(defn parse
  "Parse a string representation of datetime
  with an optional `format` parameter."
  ([v] (parse v :offset))
  ([v fmt]
   (cond
     (datetime? v) v
     (= fmt :offset) (DateTime. v)
     :else (let [m (if (= fmt :unix)
                     (js/moment.unix v)
                     (js/moment v fmt))]
             (DateTime. (.valueOf m))))))

(defn format
  "Returns a string representation of the DateTime
  instance with optional `fmt` format parameter.

  You can use `:iso` and `:unix` shortcuts as
  format parameter.

  You can read more about format tokens here:
  http://momentjs.com/docs/#/displaying/format/
  "
  ([v] (format v :iso))
  ([v fmt]
   {:pre [(datetime? v)]}
   (let [vm (js/moment (.-v v))]
     (case fmt
       :unix (.unix vm)
       :offset (.valueOf vm)
       :iso (.toISOString vm)
       (.format vm fmt)))))

(defn now
  "Return the current DateTime."
  []
  (let [vm (js/moment)]
    (DateTime. (.valueOf vm))))

(defn timeago
  [v]
  (let [dt (parse v)
        vm (js/moment (.-v dt))]
    (.fromNow vm)))

;; (defn day
;;   [v]
;;   (let [dt (parse v)
;;         vm (js/moment (.-v dt))
;;         fmt #js {:sameDay "[Today]"
;;                  :sameElse "[Today]"
;;                  :lastDay "[Yesterday]"
;;                  :lastWeek "[Last] dddd"}]
;;     (.calendar vm nil fmt)))
