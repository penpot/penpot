;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.attrs)

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

(def shape-default-attrs
  {:stroke-color "#000000"
   :stroke-opacity 1
   :fill-color "#000000"
   :fill-opacity 1})

(defn- stroke-type->dasharray
  [style]
  (case style
    :mixed "5,5,1,5"
    :dotted "5,5"
    :dashed "10,10"))

(defn- rename-attr
  [[key value :as pair]]
  (case key
    :stroke-color [:stroke value]
    :fill-color [:fill value]
    pair))

(defn- rename-attrs
  [attrs]
  (into {} (map rename-attr) attrs))

(defn- transform-stroke-attrs
  [{:keys [stroke-style] :or {stroke-style :none} :as attrs}]
  (case stroke-style
    :none (dissoc attrs :stroke-style :stroke-width :stroke-opacity :stroke-color)
    :solid (-> (merge shape-default-attrs attrs)
               (dissoc :stroke-style))
    (-> (merge shape-default-attrs attrs)
        (assoc :stroke-dasharray (stroke-type->dasharray stroke-style))
        (dissoc :stroke-style))))

(defn extract-style-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (-> (select-keys shape shape-style-attrs)
      (transform-stroke-attrs)
      (rename-attrs)))
