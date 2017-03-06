;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.time
  (:require [vendor.datefns]
            [cognitect.transit :as t]))

(def ^:private dateFns js/dateFns)

(defn format
  "Returns a string representation of the Instant
  instace with optional `fmt` format parameter.

  You can use `:iso` and `:unix` shortcuts as
  format parameter.

  You can read more about format tokens here:
  http://momentjs.com/docs/#/displaying/format/
  "
  ([v] (format v :iso))
  ([v fmt]
   {:pre [(inst? v)]}
   (case fmt
     :offset (.getTime v)
     :iso (.format dateFns v)
     (.format dateFns v fmt))))

(defn now
  "Return the current Instant."
  []
  (js/Date.))

(defn timeago
  [v]
  {:pre [(inst? v)]}
  (.distanceInWordsToNow dateFns v))
