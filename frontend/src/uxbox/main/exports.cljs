;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.exports
  "The main logic for SVG export functionality."
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.circle :refer [circle-shape]]
   [uxbox.main.ui.shapes.group :refer [group-shape]]
   [uxbox.main.ui.shapes.icon :refer [icon-shape]]
   [uxbox.main.ui.shapes.image :refer [image-shape]]
   [uxbox.main.ui.shapes.path :refer [path-shape]]
   [uxbox.main.ui.shapes.rect :refer [rect-shape]]
   [uxbox.main.ui.shapes.text :refer [text-shape]]
   [uxbox.util.dom :as dom]))

(def ^:dynamic *state* st/state)

(mf/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill "white"}])

(declare shape-component)
(declare shape-wrapper)

(defn- make-shape-element
  [state shape]
  (mf/html
   (case (:type shape)
     ;; :text [:& text-shape {:shape shape}]
     :icon [:& icon-shape {:shape shape}]
     :rect [:& rect-shape {:shape shape}]
     :path [:& path-shape {:shape shape}]
     :circle [:& circle-shape {:shape shape}]
     :image (let [image-id (:image shape)
                  image (get-in state [:images image-id])]
              [:& image-shape {:shape shape :image image}]))))

(mf/defc page-svg
  [{:keys [page state] :as props}]
  (let [{:keys [width height]} (:metadata page)]
    [:svg {:width width
           :height height
           :view-box (str "0 0 " width " " height)
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     ;; TODO: properly handle background
     #_(background)
     (for [sid (reverse (:shapes page))]
       (when-let [shape (get-in state [:shapes sid])]
         [:g {:key sid} (make-shape-element state shape)]))]))

(defn render-page
  [id]
  (try
    (let [state (deref st/state)
          page (get-in state [:pages id])]
      (when (:shapes page)
        (dom/render-to-html
         (mf/element page-svg #js {:page page :state state}))))
    (catch :default e
      (js/console.log e)
      nil)))
