;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.exports
  "The main logic for SVG export functionality."
  (:require [uxbox.main.store :as st]
            [uxbox.main.ui.shapes.rect :refer [rect-shape]]
            [uxbox.main.ui.shapes.icon :refer [icon-shape]]
            [uxbox.main.ui.shapes.text :refer [text-shape]]
            [uxbox.main.ui.shapes.group :refer [group-shape]]
            [uxbox.main.ui.shapes.path :refer [path-shape]]
            [uxbox.main.ui.shapes.circle :refer [circle-shape]]
            [uxbox.main.ui.shapes.image :refer [image-shape]]
            [uxbox.util.dom :as dom]
            [rumext.core :as mx :include-macros true]))

(def ^:dynamic *state* st/state)

(mx/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill "white"}])

(declare shape-component)
(declare shape-wrapper)

(mx/defc shape-wrapper
  [{:keys [type] :as shape}]
  (case type
    :group (group-shape shape shape-component)
    :text (text-shape shape)
    :icon (icon-shape shape)
    :rect (rect-shape shape)
    :path (path-shape shape)
    :circle (circle-shape shape)
    :image (let [image-id (:image shape)
                 image (get-in @*state* [:images image-id])]
             (image-shape (assoc shape :image image)))))

(mx/defc shape-component
  [id]
  (when-let [shape (get-in @*state* [:shapes id])]
    (shape-wrapper shape)))

(mx/defc page-svg
  [{:keys [id metadata] :as page}]
  (let [{:keys [width height]} metadata]
    [:svg {:width width
           :height height
           :view-box (str "0 0 " width " " height)
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     ;; TODO: properly handle background
     #_(background)
     (for [item (reverse (:shapes page))]
       (-> (shape-component item)
           (mx/with-key (str item))))]))

(defn render-page
  [id]
  (try
    (let [page (get-in @*state* [:pages id])]
      (when (:shapes page)
        (dom/render-to-html (page-svg page))))
    (catch :default e
      (js/console.log e)
      nil)))
