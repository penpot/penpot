;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]))

(defn get-used-names
  "Return a set with the all unique names used in the
  elements (any entity thas has a :name)"
  [elements]
  (let [elements (if (map? elements)
                   (vals elements)
                   elements)]
    (into #{} (keep :name) elements)))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[_ p1 p2] (re-find #"(.*) ([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn generate-unique-name
  "A unique name generator"
  [used basename]
  (dm/assert!
   "expected a set of strings"
   (sm/set-of-strings? used))

  (dm/assert!
   "expected a string for `basename`."
   (string? basename))

  (if-not (contains? used basename)
    basename
    (let [[prefix initial] (extract-numeric-suffix basename)]
      (loop [counter initial]
        (let [candidate (str prefix " " counter)]
          (if (contains? used candidate)
            (recur (inc counter))
            candidate))))))
