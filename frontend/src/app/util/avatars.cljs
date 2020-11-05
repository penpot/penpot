;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.avatars
  (:require
   [cuerdas.core :as str]
   [app.util.object :as obj]
   ["randomcolor" :as rdcolor]))

(defn generate
  [{:keys [name color size]
    :or {color "#000000" size 128}}]
  (let [parts   (str/words (str/upper name))
        letters (if (= 1 (count parts))
                  (ffirst parts)
                  (str (ffirst parts) (first (second parts))))
        canvas  (.createElement js/document "canvas")
        context (.getContext canvas "2d")]

    (obj/set! canvas "width" size)
    (obj/set! canvas "height" size)

    (obj/set! context "fillStyle" color)
    (.fillRect context 0 0 size size)

    (obj/set! context "font" (str (/ size 2) "px Arial"))
    (obj/set! context "textAlign" "center")
    (obj/set! context "fillStyle" "#FFFFFF")
    (.fillText context letters (/ size 2) (/ size 1.5))

    (.toDataURL canvas)))
