;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.codec
  "Verifies that measurements survive transit encoding.

  This ns exists mainly to preserve NaNs in the transit encoding of results.
  We treat two NaN values as the same measurement.

  Any data we cannot represent raises an error."
  (:require
   [app.common.transit :as transit]))

(declare equivalent?)

(defn check-value
  "Checks optional transport data within depth and size limits.

  Args:
    value: Trace or diagnostic partial data to validate.

  Returns:
    The supplied value when traversal stays within depth 32, 100000 visited
    values and 1048576 UTF-16 text units. Sequential collections have a finite
    count. Numbers retain NaN and infinities.

  Throws:
    An error for unsupported values or data that exceeds either limit."
  [value]
  (loop [pending (list [value 0])
         budget  100000
         text-budget 1048576]
    (when (neg? text-budget)
      (throw (ex-info "Transport data exceeds text limits" {:type ::data-limit})))
    (if-let [[item depth] (first pending)]
      (do
        (when (or (zero? budget) (> depth 32))
          (throw (ex-info "Transport data exceeds traversal limits" {:type ::data-limit})))
        (let [children (cond
                         (map? item) (mapcat (fn [[key value]] [[key (inc depth)] [value (inc depth)]]) item)
                         (or (and (sequential? item) (counted? item)) (set? item)) (map #(vector % (inc depth)) item)
                         (or (nil? item) (boolean? item) (number? item) (string? item)
                             (keyword? item) (symbol? item) (uuid? item) (inst? item)) nil
                         :else (throw (ex-info "Unsupported transport value" {:type ::unsupported-value})))]
          (recur (concat children (rest pending)) (dec budget)
                 (- text-budget (cond
                                  (string? item) (count item)
                                  (or (keyword? item) (symbol? item)) (+ (count (name item)) (count (namespace item)))
                                  :else 0)))))
      value)))

(defn- equivalent-entry?
  "Finds a map key and compares its value, including keys that contain NaN."
  [other [k v]]
  (if-let [entry (find other k)]
    (equivalent? v (val entry))
    (boolean (some (fn [[other-k other-v]]
                     (and (equivalent? k other-k) (equivalent? v other-v))) other))))

(defn equivalent?
  "Checks Transit value equality while retaining NaN as a valid raw value."
  [a b]
  (or (= a b)
      (cond
        (and (number? a) (number? b)) (and (js/Number.isNaN a) (js/Number.isNaN b))
        (and (map? a) (map? b)) (and (= (count a) (count b))
                                     (every? #(equivalent-entry? b %) a))
        (and (sequential? a) (sequential? b)) (and (= (count a) (count b))
                                                   (every? true? (map equivalent? a b)))
        (and (set? a) (set? b)) (and (= (count a) (count b))
                                     (every? (fn [item] (some #(equivalent? item %) b)) a))
        :else false)))

(defn encode-str
  "Encodes through app.common.transit and rejects any loss of supplied evidence."
  ([value] (encode-str value nil))
  ([value options]
   (let [text (transit/encode-str value options)]
     (when-not (equivalent? value (transit/decode-str text))
       (throw (ex-info "Transit cannot preserve this value" {:type ::unrepresentable-value})))
     text)))
