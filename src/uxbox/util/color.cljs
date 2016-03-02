;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.color
  "Color conversion utils."
  (:require [cuerdas.core :as str]))

(defn hex->rgb
  [^string data]
  (some->> (re-find #"^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$" data)
           (rest)
           (mapv #(js/parseInt % 16))))

(defn hex->rgba
  [^string data ^number opacity]
  (-> (hex->rgb data)
      (conj opacity)))

(defn rgb->str
  [color]
  {:pre [(vector? color)]}
  (if (= (count color) 3)
    (apply str/format "rgb(%s,%s,%s)" color)
    (apply str/format "rgba(%s,%s,%s,%s)" color)))

(defn rgb->hex
  [[r g b]]
  (letfn [(to-hex [c]
            (let [hexdata (.toString c 16)]
              (if (= (count hexdata) 1)
                (str "0" hexdata)
                hexdata)))]
    (str "#" (to-hex r) (to-hex g) (to-hex b))))
