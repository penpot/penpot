;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.exports
  "The main logic for SVG export functionality."
  (:require [uxbox.store :as st]
            [uxbox.main.ui.shapes.rect :refer (rect-shape)]
            [uxbox.main.ui.shapes.icon :refer (icon-shape)]
            [uxbox.main.ui.shapes.text :refer (text-shape)]
            [uxbox.main.ui.shapes.group :refer (group-shape)]
            [uxbox.main.ui.shapes.path :refer (path-shape)]
            [uxbox.main.ui.shapes.circle :refer (circle-shape)]
            [uxbox.main.ui.shapes.image :refer (image-shape)]
            [uxbox.util.mixins :as mx :include-macros true]))

(def ^:dynamic *state* st/state)

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
    :circle (circle-shape s)
    :image (let [image-id (:image s)
                 image (get-in @*state* [:images image-id])]
             (image-shape (assoc s :image image)))))

(mx/defc shape
  [sid]
  (shape* (get-in @*state* [:shapes sid])))

(mx/defc page-svg
  [{:keys [metadata] :as page}]
  (let [{:keys [width height]} metadata]
    [:svg {:width width
           :height height
           :view-box (str "0 0 " width " " height)
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     #_(background)
     (for [item (reverse (:shapes page))]
       (-> (shape item)
           (mx/with-key (str item))))]))

(defn render-page
  [id]
  (let [page (get-in @st/state [:pages id])]
    (mx/render-static-html (page-svg page))))

(defn render-page*
  [id]
  (let [page (get-in @st/state [:pages id])]
    (when (:shapes page)
      (mx/render-static-html (page-svg page)))))
