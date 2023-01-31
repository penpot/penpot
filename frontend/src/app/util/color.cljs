;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.color
  "Color conversion utils."
  (:require
   [app.util.object :as obj]
   [app.util.strings :as ust]
   [cuerdas.core :as str]
   [goog.color :as gcolor]))

(defn rgb->str
  [color]
  {:pre [(vector? color)]}
  (if (= (count color) 3)
    (apply str/format "rgb(%s,%s,%s)" color)
    (apply str/format "rgba(%s,%s,%s,%s)" color)))

(defn rgb->hsv
  [[r g b]]
  (into [] (gcolor/rgbToHsv r g b)))

(defn hsv->rgb
  [[h s v]]
  (into [] (gcolor/hsvToRgb h s v)))

(defn hex->rgb
  [v]
  (try
    (into [] (gcolor/hexToRgb v))
    (catch :default _e [0 0 0])))

(defn rgb->hex
  [[r g b]]
  (gcolor/rgbToHex r g b))

(defn hex->hsv
  [v]
  (into [] (gcolor/hexToHsv v)))

(defn hex->rgba
  [^string data ^number opacity]
  (-> (hex->rgb data)
      (conj opacity)))

(defn hex->hsl [hex]
  (try
    (into [] (gcolor/hexToHsl hex))
    (catch :default _e [0 0 0])))

(defn hex->hsla
  [^string data ^number opacity]
  (-> (hex->hsl data)
      (conj opacity)))

(defn format-hsla
  [[h s l a]]
  (let [precision 2
        rounded-s (* 100 (ust/format-precision s precision))
        rounded-l (* 100 (ust/format-precision l precision))]

    (str/fmt "%s, %s%, %s%, %s" h rounded-s rounded-l a)))

(defn hsl->rgb
  [[h s l]]
  (gcolor/hslToRgb h s l))

(defn hsl->hex
  [[h s l]]
  (gcolor/hslToHex h s l))

(defn hex?
  [v]
  (and (string? v)
       (re-seq #"^#[0-9A-Fa-f]{6}$" v)))

(defn hsl->hsv
  [[h s l]]
  (gcolor/hslToHsv h s l))

(defn hsv->hex
  [[h s v]]
  (gcolor/hsvToHex h s v))

(defn hsv->hsl
  [hsv]
  (hex->hsl (hsv->hex hsv)))

(defn expand-hex
  [v]
  (cond
    (re-matches #"^[0-9A-Fa-f]$" v)
    (str v v v v v v)

    (re-matches #"^[0-9A-Fa-f]{2}$" v)
    (str v v v)

    (re-matches #"^[0-9A-Fa-f]{3}$" v)
    (let [a (nth v 0)
          b (nth v 1)
          c (nth v 2)]
      (str a a b b c c))

    :else
    v))

(defn prepend-hash
  [color]
  (gcolor/prependHashIfNecessaryHelper color))

(defn remove-hash
  [color]
  (if (str/starts-with? color "#")
    (subs color 1)
    color))

(defn gradient->css [{:keys [type stops]}]
  (let [parse-stop
        (fn [{:keys [offset color opacity]}]
          (let [[r g b] (hex->rgb color)]
            (str/fmt "rgba(%s, %s, %s, %s) %s" r g b opacity (str (* offset 100) "%"))))

        stops-css (str/join "," (map parse-stop stops))]

    (if (= type :linear)
      (str/fmt "linear-gradient(to bottom, %s)" stops-css)
      (str/fmt "radial-gradient(circle, %s)" stops-css))))

;; TODO: REMOVE `VALUE` WHEN COLOR IS INTEGRATED
(defn color->background [{:keys [color opacity gradient value]}]
  (let [color (or color value)
        opacity (or opacity 1)]
    (cond
      (and gradient (not= :multiple gradient))
      (gradient->css gradient)

      (not= color :multiple)
      (let [[r g b] (hex->rgb (or color value))]
        (str/fmt "rgba(%s, %s, %s, %s)" r g b opacity))

      :else "transparent")))

(defn multiple? [{:keys [id file-id value color gradient]}]
  (or (= value :multiple)
      (= color :multiple)
      (= gradient :multiple)
      (= id :multiple)
      (= file-id :multiple)))

(defn color? [^string color-str]
  (and (not (nil? color-str))
       (seq color-str)
       (gcolor/isValidColor color-str)))

(defn parse-color [^string color-str]
  (let [result (gcolor/parse color-str)]
    (str (.-hex ^js result))))

(def color-names
  (obj/get-keys ^js gcolor/names))

(def empty-color
  (into {} (map #(vector % nil)) [:color :id :file-id :gradient :opacity]))

(defn next-rgb
  "Given a color in rgb returns the next color"
  [[r g b]]
  (cond
    (and (= 255 r) (= 255 g) (= 255 b))
    (throw (ex-info "cannot get next color" {:r r :g g :b b}))

    (and (= 255 g) (= 255 b))
    [(inc r) 0 0]

    (= 255 b)
    [r (inc g) 0]

    :else
    [r g (inc b)]))
