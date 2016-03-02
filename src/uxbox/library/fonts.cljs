;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.library.fonts)

(def ^:const +collections+
  [{:id "sourcesanspro"
    :name "Source Sans Pro"
    :styles [{:name "Extra-Light"
              :weight "100"
              :style "normal"}
             {:name "Extra-Light Italic"
              :weight "100"
              :style "italic"}
             {:name "Light"
              :weight "200"
              :style "normal"}
             {:name "Light Italic"
              :weight "200"
              :style "italic"}
             {:name "Regular"
              :weight "normal"
              :style "normal"}
             {:name "Italic"
              :weight "normal"
              :style "italic"}
             {:name "Semi-Bold"
              :weight "500"
              :style "normal"}
             {:name "Semi-Bold Italic"
              :weight "500"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}
             {:name "Black"
              :weight "900"
              :style "normal"}
             {:name "Black Italic"
              :weight "900"
              :style "italic"}]}
   {:id "bebas"
    :name "Bebas"
    :styles [{:name "Normal"
              :weight "normal"
              :style "normal"}]}])
