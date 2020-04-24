;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require
   [cuerdas.core :as str]
   [goog.events :as events]
   [goog.object :as gobj]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as dw]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.color :as color]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]))

;; --- Events

(defn handle-mouse-down
  [event {:keys [id group] :as shape}]
  (if (and (not (:blocked shape))
           (or @refs/selected-drawing-tool
               @refs/selected-edition))
    (dom/stop-propagation event)
    (common/on-mouse-down event shape)))

;; --- Text Wrapper

(declare text-shape-html)
(declare text-shape-edit)
(declare text-shape)

(mf/defc text-wrapper
  [{:keys [shape frame] :as props}]
  (let [{:keys [id x1 y1 content group]} shape
        selected (mf/deref refs/selected-shapes)
        edition (mf/deref refs/selected-edition)
        edition? (= edition id)
        selected? (and (contains? selected id)
                       (= (count selected) 1))

        on-mouse-down #(handle-mouse-down % shape)
        on-context-menu #(common/on-context-menu % shape)

        on-double-click
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)
          (when selected?
            (st/emit! (dw/start-edition-mode (:id shape)))))]

    [:g.shape {:on-double-click on-double-click
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     (if edition?
       [:& text-shape-edit {:shape (geom/transform-shape frame shape)}]
       [:& text-shape {:shape (geom/transform-shape frame shape)}])]))

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
       :resize "none"
       :background "transparent"}
      (when user-select {:userSelect "auto"})
      (when line-height {:lineHeight line-height})
      (when letter-spacing {:letterSpacing letter-spacing})))))

;; --- Text Shape Edit

(mf/defc text-shape-edit
  [{:keys [shape] :as props}]
  (let [ref (mf/use-ref)
        {:keys [id x y width height content]} shape

        on-unmount
        (fn []
          (let [content (-> (mf/ref-val ref)
                            (dom/get-value))]
          (st/emit! (dw/update-shape id {:content content}))))


        on-blur
        (fn [event]
          (st/emit! dw/clear-edition-mode
                    dw/deselect-all))]
    (mf/use-effect
     #(let [dom (mf/ref-val ref)
            val (dom/get-value dom)]
        (.focus dom)
        (gobj/set dom "selectionStart" (count val))
        (gobj/set dom "selectionEnd" (count val))
        (fn []
          (let [content (-> (mf/ref-val ref)
                            (dom/get-value))]
            (st/emit! (dw/update-shape id {:content content}))))))



    [:foreignObject {:x x :y y :width width :height height}
     [:textarea {:style (make-style shape)
                 :on-blur on-blur
                 :default-value content
                 :ref ref}]]))

;; --- Text Shape Wrapper

(mf/defc text-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id x y width height rotation content]} shape

        transform (when (and rotation (pos? rotation))
                    (str/format "rotate(%s %s %s)"
                                rotation
                                (+ x (/ width 2))
                                (+ y (/ height 2))))]

    [:foreignObject {:x x
                     :y y
                     :transform transform
                     :id (str id)
                     :width width
                     :height height}
     [:div {:style (make-style shape)} content]]))
