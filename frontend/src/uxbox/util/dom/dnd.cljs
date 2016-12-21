;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.dom.dnd
  "Drag & Drop interop helpers."
  (:require [uxbox.util.data :refer (read-string)]))

(defn event->data-transfer
  [e]
  (.-dataTransfer e))

(defn set-allowed-effect!
  [e effect]
  (let [dt (.-dataTransfer e)]
    (set! (.-effectAllowed dt) effect)
    e))

(defn set-drop-effect!
  [e effect]
  (let [dt (.-dataTransfer e)]
    (set! (.-dropEffect dt) effect)
    e))

(defn set-data!
  ([e data]
   (set-data! e "uxbox/data" data))
  ([e key data]
   (let [dt (.-dataTransfer e)]
     (.setData dt (str key) (pr-str data)))))

(defn set-image!
  ([e data]
   (set-image! e data 0 0))
  ([e data x y]
   (let [dt (.-dataTransfer e)
         st (.-style data)]
     (.setDragImage dt data x y))))

(defn get-data
  ([e]
   (get-data e "uxbox/data"))
  ([e key]
   (let [dt (.-dataTransfer e)]
     (read-string (.getData dt (str key))))))

(defn get-hover-position
  [event group?]
  (let [target (.-currentTarget event)
        brect (.getBoundingClientRect target)
        width (.-offsetHeight target)
        y (- (.-clientY event) (.-top brect))
        part (/ (* 30 width) 100)]
    (if group?
      (cond
        (> part y) :top
        (< (- width part) y) :bottom
        :else :middle)
      (if (>= y (/ width 2))
        :bottom
        :top))))
