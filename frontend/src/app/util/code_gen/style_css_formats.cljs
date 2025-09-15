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

(defn format-color->css
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
  (let [css-color (format-color->css color options)]
    (dm/str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))

(defn- format-position
  [_ value _options]
  (cond
    (number? value) (fmt/format-pixels value)
    :else value))

(defn- format-size
  [_ value _options]
  (cond
    (= value :fill) "100%"
    (= value :auto) "auto"
    (number? value) (fmt/format-pixels value)
    :else value))

(defn- format-color
  [_ value options]
  (let [format (get options :format :hex)]
    (format-color->css value (assoc options :format format))))

(defn- format-color-array
  [_ value options]
  (->> value
       (map #(format-color->css % options))
       (str/join ", ")))

(defn- format-border
  [_ {:keys [color style width]} options]
  (dm/fmt "% % %"
          (fmt/format-pixels width)
          (d/name style)
          (format-color->css color options)))

(defn- format-border-style
  [_ value _options]
  (d/name (:style value)))

(defn- format-border-width
  [_ value _options]
  (fmt/format-pixels (:width value)))

(defn- format-size-array
  [_ value _options]
  (cond
    (and (coll? value) (d/not-empty? value))
    (->> value
         (map fmt/format-pixels)
         (str/join " "))

    (some? value)
    value))

(defn format-string-or-size-array
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

(defn- format-keyword
  [_ value _options]
  (d/name value))

(defn- format-tracks
  [_ value _options]
  (->> value
       (map (fn [{:keys [type value]}]
              (case type
                :flex (dm/str (fmt/format-number value) "fr")
                :percent (fmt/format-percent (/ value 100))
                :auto "auto"
                (fmt/format-pixels value))))
       (str/join " ")))

(defn- format-shadow
  [_ value options]
  (->> value
       (map #(format-shadow->css % options))
       (str/join ", ")))

(defn- format-blur
  [_ value _options]
  (dm/fmt "blur(%)" (fmt/format-pixels value)))

(defn-  format-matrix
  [_ value _options]
  (fmt/format-matrix value))


(defn format-value
  "Get the appropriate value formatter function for a given CSS property."
  [property value options]
  (let [property (css-formatters property)]
    (case property
      :position (format-position property value options)
      :size (format-size property value options)
      :color (format-color property value options)
      :color-array (format-color-array property value options)
      :border (format-border property value options)
      :border-style (format-border-style property value options)
      :border-width (format-border-width property value options)
      :size-array (format-size-array property value options)
      :string-or-size-array (format-string-or-size-array property value options)
      :keyword (format-keyword property value options)
      :tracks (format-tracks property value options)
      :shadow (format-shadow property value options)
      :blur (format-blur property value options)
      :matrix (format-matrix property value options)
      (if (keyword? value) (d/name value) value))))
