;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.shapes.attrs)

(def ^:private +style-attrs+
  #{:fill :fill-opacity :opacity
    :stroke :stroke-opacity :stroke-width
    :stroke-type :rx :ry})

(defn- transform-stroke-type
  [attrs]
  (if-let [type (:stroke-type attrs)]
    (let [value (case type
                  :mixed "5,5,1,5"
                  :dotted "5,5"
                  :dashed "10,10"
                  nil)]
      (if value
        (-> attrs
            (assoc! :stroke-dasharray value)
            (dissoc! :stroke-type))
        (dissoc! attrs :stroke-type)))
    attrs))

(defn- transform-stroke-attrs
  [attrs]
  (if (= (:stroke-type attrs :none) :none)
    (dissoc! attrs :stroke-type :stroke-width :stroke-opacity :stroke)
    (transform-stroke-type attrs)))

(defn- extract-style-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (let [attrs (select-keys shape +style-attrs+)]
    (-> (transient attrs)
        (transform-stroke-attrs)
        (persistent!))))

(defn- make-debug-attrs
  [shape]
  (let [attrs (select-keys shape [:rotation :width :height :x :y])
        xf (map (fn [[x v]]
                    [(keyword (str "data-" (name x))) v]))]
      (into {} xf attrs)))
