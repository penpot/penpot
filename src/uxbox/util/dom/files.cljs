;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.dom.files
  "A interop helpers for work with files."
  (:require [beicon.core :as rx]
            [cuerdas.core :as str]))

(defn- read-as-dataurl
  [file]
  (rx/create
   (fn [sick]
     (let [fr (js/FileReader.)]
       (aset fr "onload" #(sick (rx/end (.-result fr))))
       (.readAsDataURL fr file))
     (constantly nil))))

(defn- retrieve-image-size
  [data]
  (rx/create
   (fn [sick]
     (let [img (js/Image.)]
       (aset img "onload" #(sick (rx/end [(.-width img) (.-height img)])))
       (set! (.-src img) data))
     (constantly nil))))

(defn get-image-size
  "Get the real image size."
  [file]
  (->> (read-as-dataurl file)
       (rx/flat-map retrieve-image-size)))
