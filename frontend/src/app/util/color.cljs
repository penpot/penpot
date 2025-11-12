;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.color
  "FIXME: this is legacy namespace, all functions of this ns should be
  relocated under app.common.types on the respective colors related
  namespace. All generic color conversion and other helpers are moved to
  app.common.types.color namespace."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]))

(defn gradient->css [{:keys [type stops]}]
  (let [parse-stop
        (fn [{:keys [offset color opacity]}]
          (let [[r g b] (cc/hex->rgb color)]
            (str/fmt "rgba(%s, %s, %s, %s) %s" r g b opacity (str (* offset 100) "%"))))

        stops-css (str/join "," (map parse-stop stops))]

    (if (= type :linear)
      (str/fmt "linear-gradient(to bottom, %s)" stops-css)
      (str/fmt "radial-gradient(circle, %s)" stops-css))))

(defn gradient-type->string [type]
  (case type
    :linear (tr "workspace.gradients.linear")
    :radial (tr "workspace.gradients.radial")
    nil))

;; TODO: REMOVE `VALUE` WHEN COLOR IS INTEGRATED
(defn color->background [{:keys [color opacity gradient value]}]
  (let [color (d/nilv color value)
        opacity (or opacity 1)]

    (cond
      (and gradient (not= :multiple gradient))
      (gradient->css gradient)

      (and (some? color) (not= color :multiple))
      (let [color
            (-> (str/replace color "#" "")
                (cc/expand-hex)
                (cc/prepend-hash))
            [r g b] (cc/hex->rgb color)]
        (str/fmt "rgba(%s, %s, %s, %s)" r g b opacity))

      :else "transparent")))

(defn color->format->background [{:keys [color opacity gradient]} format]
  (let [opacity (or opacity 1)]
    (cond
      (and gradient (not= :multiple gradient))
      (gradient->css gradient)

      (not= color :multiple)
      (case format
        :rgba (let [[r g b] (cc/hex->rgb color)]
                (str/fmt "rgba(%s)" (cc/format-rgba [r g b opacity])))

        :hsla (let [[h s l] (cc/hex->hsl color)]
                (str/fmt "hsla(%s)" (cc/format-hsla [h s l opacity])))

        :hex (str color (str/upper (d/opacity-to-hex opacity))))

      :else "transparent")))

(defn multiple?
  [{:keys [id file-id value color gradient]}]
  (or (= value :multiple)
      (= color :multiple)
      (= gradient :multiple)
      (= id :multiple)
      (= file-id :multiple)))

(defn get-color-name
  [color]
  (or (:name (meta color))
      (:name color)
      (:color color)
      (gradient-type->string (:type (:gradient color)))))

(defn random-color
  []
  (dm/fmt "rgb(%, %, %)"
          (mth/floor (* (js/Math.random) 256))
          (mth/floor (* (js/Math.random) 256))
          (mth/floor (* (js/Math.random) 256))))
