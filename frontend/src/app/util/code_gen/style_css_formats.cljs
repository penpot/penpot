;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen.style-css-formats
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.formats :as fmt]
   [app.util.color :as uc]
   [cuerdas.core :as str]))

(def css-formatters
  {:left                  :position
   :top                   :position
   :width                 :size
   :height                :size
   :background            :color
   :background-color      :color
   :background-image      :color-array
   :border                :border
   :border-radius         :size-array
   :box-shadow            :shadows
   :filter                :blur
   :gap                   :size-array
   :padding               :size-array
   :grid-template-rows    :tracks
   :grid-template-columns :tracks
   })

(defmulti format-value
  (fn [property _value _options] (css-formatters property)))

(defmethod format-value :position
  [_ value _options]
  (cond
    (number? value) (fmt/format-pixels value)
    :else value))

(defmethod format-value :size
  [_ value _options]
  (cond
    (= value :fill) "100%"
    (= value :auto) "auto"
    (number? value) (fmt/format-pixels value)
    :else value))

(defn format-color
  [value _options]
  (cond
    (not= (:opacity value) 1)
    (uc/color->background value)

    :else
    (str/upper (:color value))))

(defmethod format-value :color
  [_ value options]
  (format-color value options))

(defmethod format-value :color-array
  [_ value options]
  (->> value
       (map #(format-color % options))
       (str/join ", ")))

(defmethod format-value :border
  [_ {:keys [color style width]} options]
  (dm/fmt "% % %"
          (fmt/format-pixels width)
          (d/name style)
          (format-color color options)))

(defmethod format-value :size-array
  [_ value _options]
  (cond
    (and (coll? value) (d/not-empty? value))
    (->> value
         (map fmt/format-pixels)
         (str/join " "))

    (some? value)
    value))

(defmethod format-value :keyword
  [_ value _options]
  (d/name value))

(defmethod format-value :tracks
  [_ value _options]
  (->> value
       (map (fn [{:keys [type value]}]
              (case type
                :flex (dm/str (fmt/format-number value) "fr")
                :percent (fmt/format-percent (/ value 100))
                :auto "auto"
                (fmt/format-pixels value))))
       (str/join " ")))

(defn format-shadow
  [{:keys [style offset-x offset-y blur spread color]} options]
  (let [css-color (format-color color options)]
    (dm/str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))

(defmethod format-value :shadows
  [_ value options]
  (->> value
       (map #(format-shadow % options))
       (str/join ", " )))

(defmethod format-value :blur
  [_ value _options]
  (dm/fmt "blur(%)" (fmt/format-pixels value)))

(defmethod format-value :default
  [_ value _options]
  (if (keyword? value)
    (d/name value)
    value))
