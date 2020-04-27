;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.avatars
  (:require
   [cuerdas.core :as str]
   [uxbox.util.object :as obj]
   ["randomcolor" :as rdcolor]))

(defn- impl-generate-image
  [{:keys [name color size]
    :or {color "#303236" size 128}}]
  (let [parts   (str/words (str/upper name))
        letters (if (= 1 (count parts))
                  (ffirst parts)
                  (str (ffirst parts) (first (second parts))))
        canvas  (.createElement js/document "canvas")
        context (.getContext canvas "2d")]

    (set! (.-width canvas) size)
    (set! (.-height canvas) size)
    (set! (.-fillStyle context) "#303236")
    (.fillRect context 0 0 size size)

    (set! (.-font context) (str (/ size 2) "px Arial"))
    (set! (.-textAlign context) "center")
    (set! (.-fillStyle context) "#FFFFFF")
    (.fillText context letters (/ size 2) (/ size 1.5))
    (.toDataURL canvas)))

(defn assign
  [{:keys [id photo-uri fullname color] :as profile}]
  (cond-> profile
    (not photo-uri) (assoc :photo-uri (impl-generate-image {:name fullname}))
    (not color) (assoc :color (rdcolor))))
