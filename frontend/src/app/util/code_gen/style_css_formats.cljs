;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen.style-css-formats
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cfg]
   [app.main.ui.formats :as fmt]
   [app.util.color :as uc]
   [cuerdas.core :as str]))

(def css-formatters
  {:left                  :position
   :top                   :position
   :width                 :size
   :height                :size
   :min-width             :size
   :min-height            :size
   :max-width             :size
   :max-height            :size
   :background            :color
   :border                :border
   :border-radius         :string-or-size-array
   :box-shadow            :shadows
   :filter                :blur
   :gap                   :size-array
   :row-gap               :size-array
   :column-gap            :size-array
   :padding               :size-array
   :margin                :size-array
   :grid-template-rows    :tracks
   :grid-template-columns :tracks})

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
  "Format a color value to a CSS compatible string based on the given format."
  [value options]
  (let [format (get options :format :hex)]
    (cond
      (:image value)
      (let [image-url (cfg/resolve-file-media (:image value))
            opacity-color (when (not= (:opacity value) 1)
                            (uc/gradient->css {:type :linear
                                               :stops [{:color "#FFFFFF" :opacity (:opacity value)}
                                                       {:color "#FFFFFF" :opacity (:opacity value)}]}))]
        (if opacity-color
          ;; CSS doesn't allow setting directly opacity to background image, we should add a dummy gradient to get it
          (dm/fmt "%, url(%) no-repeat center center / cover" opacity-color image-url)
          (dm/fmt "url(%) no-repeat center center / cover" image-url)))

      (not= (:opacity value) 1)
      (uc/color->format->background value format)

      :else
      (uc/color->format->background value format))))

(defmethod format-value :color
  [_ value options]
  (let [format (get options :format :hex)]
    (format-color value (assoc options :format format))))

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

(defmethod format-value :string-or-size-array
  [_ value _]
  (cond
    (string? value)
    value

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
       (str/join ", ")))

(defmethod format-value :blur
  [_ value _options]
  (dm/fmt "blur(%)" (fmt/format-pixels value)))

(defmethod format-value :matrix
  [_ value _options]
  (fmt/format-matrix value))

(defmethod format-value :default
  [_ value _options]
  (if (keyword? value)
    (d/name value)
    value))
