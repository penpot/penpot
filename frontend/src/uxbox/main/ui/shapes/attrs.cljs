;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.attrs
  (:require [cuerdas.core :as str]))


;; (defn camel-case
;;   "Returns camel case version of the key, e.g. :http-equiv becomes :httpEquiv."
;;   [k]
;;   (if (or (keyword? k)
;;           (string? k)
;;           (symbol? k))
;;     (let [[first-word & words] (str/split (name k) #"-")]
;;       (if (or (empty? words)
;;               (= "aria" first-word)
;;               (= "data" first-word))
;;         k
;;         (-> (map str/capital words)
;;             (conj first-word)
;;             str/join
;;             keyword)))
;;     k))

(defn- process-key
  [k]
  (if (keyword? k)
    (cond
      (keyword-identical? k :stroke-color) :stroke
      (keyword-identical? k :fill-color) :fill
      (str/includes? (name k) "-") (str/camel k)
      :else k)))

(defn- process-attrs
  [m]
  (persistent!
   (reduce-kv (fn [m k v]
                (assoc! m (process-key k) v))
              (transient {})
              m)))

(def shape-style-attrs
  #{:fill-color
    :fill-opacity
    :stroke-color
    :stroke-opacity
    :stroke-width
    :stroke-style
    :opacity
    :rx
    :ry})

(defn- stroke-type->dasharray
  [style]
  (case style
    :mixed "5,5,1,5"
    :dotted "5,5"
    :dashed "10,10"))

(defn- transform-stroke-attrs
  [{:keys [stroke-style] :or {stroke-style :none} :as attrs}]
  (case stroke-style
    :none (dissoc attrs :stroke-style :stroke-width :stroke-opacity :stroke-color)
    :solid (dissoc attrs :stroke-style)
    (-> attrs
        (assoc :stroke-dasharray (stroke-type->dasharray stroke-style))
        (dissoc :stroke-style))))

(defn extract-style-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (-> (select-keys shape shape-style-attrs)
      (transform-stroke-attrs)
      (process-attrs)))
