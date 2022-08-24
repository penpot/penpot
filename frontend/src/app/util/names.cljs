;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.names
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [cljs.spec.alpha :as s]))

(s/def ::set-of-string (s/every string? :kind set?))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[_ p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn retrieve-used-names
  [objects]
  (into #{} (comp (map :name) (remove nil?)) (vals objects)))

(defn generate-unique-name
  "A unique name generator"
  [used basename]
  (s/assert ::set-of-string used)
  (s/assert ::us/string basename)
  (if-not (contains? used basename)
    basename
    (let [[prefix initial] (extract-numeric-suffix basename)]
      (loop [counter initial]
        (let [candidate (str prefix "-" counter)]
          (if (contains? used candidate)
            (recur (inc counter))
            candidate))))))

