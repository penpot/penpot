;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns app.util.files
  "A interop helpers for work with files."
  (:require [beicon.core :as rx]
            [cuerdas.core :as str]
            [app.util.blob :as blob]))

;; TODO: DEPRECATED

(defn read-as-text
  [file]
  (rx/create
   (fn [sink]
     (let [fr (js/FileReader.)]
       (aset fr "onload" #(sink (rx/end (.-result fr))))
       (.readAsText fr file)
       (constantly nil)))))

(defn read-as-dataurl
  [file]
  (rx/create
   (fn [sick]
     (let [fr (js/FileReader.)]
       (aset fr "onload" #(sick (rx/end (.-result fr))))
       (.readAsDataURL fr file))
     (constantly nil))))

(defn get-image-size
  [file]
  (letfn [(on-load [sink img]
            (let [size [(.-width img) (.-height img)]]
              (sink (rx/end size))))
          (on-subscribe [sink]
            (let [img (js/Image.)
                  uri (blob/create-uri file)]
              (set! (.-onload img) (partial on-load sink img))
              (set! (.-src img) uri)
              #(blob/revoke-uri uri)))]
    (rx/create on-subscribe)))
