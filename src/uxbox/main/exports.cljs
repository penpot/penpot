;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
(ns uxbox.main.exports
  "The main logic for SVG export functionality."
  (:require [uxbox.main.state :as st]
            [uxbox.main.ui.shapes.rect :refer (rect-shape)]
            [uxbox.main.ui.shapes.icon :refer (icon-shape)]
            [uxbox.main.ui.shapes.text :refer (text-shape)]
            [uxbox.main.ui.shapes.group :refer (group-shape)]
            [uxbox.main.ui.shapes.path :refer (path-shape)]
            [uxbox.main.ui.shapes.circle :refer (circle-shape)]
            [uxbox.util.mixins :as mx]))

(mx/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill "white"}])

(declare shape)
(declare shape*)

(mx/defc shape*
  [{:keys [type] :as s}]
  (case type
    :group (group-shape s shape)
    :text (text-shape s)
    :icon (icon-shape s)
    :rect (rect-shape s)
    :path (path-shape s)
    :circle (circle-shape s)))

(mx/defc shape
  [sid]
  (shape* (get-in @st/state [:shapes-by-id sid])))

(mx/defc page-svg
  [{:keys [width height] :as page}]
  [:svg {:width width
         :height height
         :version "1.1"
         :xmlns "http://www.w3.org/2000/svg"}
   (background)
   (for [item (reverse (:shapes page))]
     (-> (shape item)
         (mx/with-key (str item))))])

(defn render-page
  [id]
  (let [page (get-in @st/state [:pages-by-id id])]
    (mx/render-static-html (page-svg page))))
