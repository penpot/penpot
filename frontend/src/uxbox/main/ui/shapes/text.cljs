;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require
   [cuerdas.core :as str]
   [goog.events :as events]
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.color :as color]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]))

;; --- Events

(defn handle-mouse-down
  [event {:keys [id group] :as shape} selected]
  (if (and (not (:blocked shape))
           (or @refs/selected-drawing-tool
               @refs/selected-edition))
    (dom/stop-propagation event)
    (common/on-mouse-down event shape selected)))

;; --- Text Wrapper

(declare text-shape-html)
(declare text-shape-edit)
(declare text-shape)

(mf/defc text-wrapper
  [{:keys [shape] :as props}]
  (let [{:keys [id x1 y1 content group]} shape
        selected (mf/deref refs/selected-shapes)
        edition (mf/deref refs/selected-edition)
        edition? (= edition id)
        selected? (and (contains? selected id)
                       (= (count selected) 1))]
    (letfn [(on-mouse-down [event]
              (handle-mouse-down event shape selected))
            (on-double-click [event]
              (dom/stop-propagation event)
              (st/emit! (udw/start-edition-mode id)))]
      [:g.shape {:class (when selected? "selected")
                 :on-double-click on-double-click
                 :on-mouse-down on-mouse-down}
       (if edition?
         [:& text-shape-edit {:shape shape}]
         [:& text-shape {:shape shape}])])))

;; --- Text Styles Helpers

(defn- make-style
  [{:keys [fill-color
           fill-opacity
           font-family
           font-weight
           font-style
           font-size
           text-align
           line-height
           letter-spacing
           user-select
           width
           height]
    :or {fill-color "#000000"
         fill-opacity 1
         font-family "sourcesanspro"
         font-weight "normal"
         font-style "normal"
         font-size 18
         line-height 1.4
         letter-spacing 1
         text-align "left"
         user-select false}
    :as shape}]
  (let [color (-> fill-color
                  (color/hex->rgba fill-opacity)
                  (color/rgb->str))]
    (rumext.util/map->obj
     (merge
      {:fontSize (str font-size "px")
       :color color
       :width width
       :height height
       :whiteSpace "pre-wrap"
       :textAlign text-align
       :fontFamily font-family
       :fontWeight font-weight
       :fontStyle font-style
       :margin "0px"
       :padding "0px"
       :border "0px"
       :resize "none"}
      (when user-select {:userSelect "auto"})
      (when line-height {:lineHeight line-height})
      (when letter-spacing {:letterSpacing letter-spacing})))))

;; --- Text Shape Edit

(mf/defc text-shape-edit
  [{:keys [shape] :as props}]
  (let [ref (mf/use-ref)
        {:keys [id x y width height content]} shape]
    (mf/use-effect
     #(fn []
        (let [content (-> (mf/ref-val ref)
                          (dom/get-value))]
          (st/emit! (udw/update-shape id {:content content})))))
    [:foreignObject {:x x :y y :width width :height height}
     [:textarea {:style (make-style shape)
                 :default-value content
                 :ref ref}]]))

;; --- Text Shape Wrapper

(mf/defc text-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id rotation modifier-mtx]} shape
        shape (cond
                (gmt/matrix? modifier-mtx) (geom/transform shape modifier-mtx)
                :else shape)

        {:keys [x y width height content]} shape]
    [:foreignObject {:x x
                     :y y
                     :id (str id)
                     :width width
                     :height height}
     [:div {:style (make-style shape)} content]]))
