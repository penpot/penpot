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
  {:left                      :position
   :top                       :position
   :width                     :size
   :height                    :size
   :max-height                :size
   :max-block-size            :size
   :min-height                :size
   :min-block-size            :size
   :max-width                 :size
   :max-inline-size           :size
   :min-width                 :size
   :min-inline-size           :size
   :background                :color
   :border                    :border
   :border-radius             :string-or-size-array
   :border-start-start-radius :string-or-size-array
   :border-start-end-radius   :string-or-size-array
   :border-end-start-radius   :string-or-size-array
   :border-end-end-radius     :string-or-size-array
   :border-width              :border-width
   :border-style              :border-style
   :border-color              :border-color
   :box-shadow                :shadows
   :filter                    :blur
   :gap                       :size-array
   :row-gap                   :size-array
   :column-gap                :size-array
   :padding                   :size-array
   :padding-inline-start      :size-array
   :padding-inline-end        :size-array
   :padding-block-start       :size-array
   :padding-block-end         :size-array
   :margin                    :size-array
   :margin-block-start        :size-array
   :margin-block-end          :size-array
   :margin-inline-start       :size-array
   :margin-inline-end         :size-array
   :grid-template-rows        :tracks
   :grid-template-columns     :tracks})

(defn format-color-value
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

(defn format-shadow->css
  [{:keys [style offset-x offset-y blur spread color]} options]
  (let [css-color (format-color-value color options)]
    (dm/str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))

(defn- format-position
  [value]
  (cond
    (number? value) (fmt/format-pixels value)
    :else value))

(defn- format-size
  [value]
  (cond
    (= value :fill) "100%"
    (= value :auto) "auto"
    (number? value) (fmt/format-pixels value)
    :else value))

(defn- format-color
  [value options]
  (let [format (get options :format :hex)]
    (format-color-value value (assoc options :format format))))

(defn- format-color-array
  [value options]
  (->> value
       (map #(format-color-value % options))
       (str/join ", ")))

(defn- format-border
  [{:keys [color style width]} options]
  (dm/fmt "% % %"
          (fmt/format-pixels width)
          (d/name style)
          (format-color-value color options)))

(defn- format-border-style
  [value]
  (d/name (:style value)))

(defn- format-border-width
  [value]
  (fmt/format-pixels (:width value)))

(defn- format-border-color
  [value options]
  (format-color (:color value) options))

(defn- format-size-array
  [value]
  (cond
    (and (coll? value) (d/not-empty? value))
    (->> value
         (map fmt/format-pixels)
         (str/join " "))

    (some? value)
    value))

(defn format-string-or-size-array
  [value]
  (cond
    (string? value)
    value

    (and (coll? value) (d/not-empty? value))
    (->> value
         (map fmt/format-pixels)
         (str/join " "))

    (some? value)
    value))

(defn- format-keyword
  [value]
  (d/name value))

(defn- format-tracks
  [value]
  (->> value
       (map (fn [{:keys [type value]}]
              (case type
                :flex (dm/str (fmt/format-number value) "fr")
                :percent (fmt/format-percent (/ value 100))
                :auto "auto"
                (fmt/format-pixels value))))
       (str/join " ")))

(defn- format-shadow
  [value options]
  (->> value
       (map #(format-shadow->css % options))
       (str/join ", ")))

(defn- format-blur
  [value]
  (dm/fmt "blur(%)" (fmt/format-pixels value)))

(defn-  format-matrix
  [value]
  (fmt/format-matrix value))


(defn format-value
  "Get the appropriate value formatter function for a given CSS property."
  [property value options]
  (let [property (get css-formatters property)]
    (case property
      :position (format-position value)
      :size (format-size value)
      :color (format-color value options)
      :color-array (format-color-array value options)
      :border (format-border value options)
      :border-style (format-border-style value)
      :border-width (format-border-width value)
      :border-color (format-border-color value options)
      :size-array (format-size-array value)
      :string-or-size-array (format-string-or-size-array value)
      :keyword (format-keyword value)
      :tracks (format-tracks value)
      :shadow (format-shadow value options)
      :blur (format-blur value)
      :matrix (format-matrix value)
      (if (keyword? value) (d/name value) value))))
