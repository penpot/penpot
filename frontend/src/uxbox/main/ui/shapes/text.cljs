;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [goog.events :as events]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.geom :as geom]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.util.color :as color]
            [uxbox.util.data :refer [classnames]]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.matrix :as gmt]
            [rumext.core :as mx :include-macros true])
  (:import goog.events.EventType))

;; --- Events

(defn handle-mouse-down
  [event {:keys [id group] :as shape} selected]
  (if (and (not (:blocked shape))
           (or @refs/selected-drawing-tool
               @refs/selected-edition
               (and group (:locked (geom/resolve-parent shape)))))
    (dom/stop-propagation event)
    (common/on-mouse-down event shape selected)))

;; --- Text Component

(declare text-shape-html)
(declare text-shape-wrapper)
(declare text-shape-edit)

(mx/defcs text-component
  {:mixins [mx/static mx/reactive]}
  [own {:keys [id x1 y1 content group] :as shape}]
  (let [modifiers (mx/react (refs/selected-modifiers id))
        selected (mx/react refs/selected-shapes)
        edition? (= (mx/react refs/selected-edition) id)
        selected? (and (contains? selected id)
                       (= (count selected) 1))
        shape (assoc shape :modifiers modifiers)]
    (letfn [(on-mouse-down [event]
              (handle-mouse-down event shape selected))
            (on-double-click [event]
              ;; TODO: handle grouping event propagation
              ;; TODO: handle actions locking properly
              (dom/stop-propagation event)
              (st/emit! (uds/start-edition-mode id)))]
      [:g.shape {:class (when selected? "selected")
                 :ref "main"
                 :on-double-click on-double-click
                 :on-mouse-down on-mouse-down}
       (if edition?
         (text-shape-edit shape)
         (text-shape-wrapper shape))])))

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
           user-select]
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
    (merge
     {:fontSize (str font-size "px")
      :color color
      :whiteSpace "pre-wrap"
      :textAlign text-align
      :fontFamily font-family
      :fontWeight font-weight
      :fontStyle font-style}
     (when user-select {:userSelect "auto"})
     (when line-height {:lineHeight line-height})
     (when letter-spacing {:letterSpacing letter-spacing}))))

;; --- Text Shape Edit

(defn- text-shape-edit-did-mount
  [own]
  (let [[shape] (:rum/args own)
        dom (mx/ref-node own "container")]
    (set! (.-textContent dom) (:content shape ""))
    (.focus dom)
    own))

(mx/defc text-shape-edit
  {:did-mount text-shape-edit-did-mount
   :mixins [mx/static]}
  [{:keys [id x1 y1 content] :as shape}]
  (let [{:keys [width height]} (geom/size shape)
        style (make-style shape)
        props {:x x1 :y y1 :width width :height height}]
    (letfn [(on-input [ev]
              (let [content (dom/event->inner-text ev)]
                (st/emit! (uds/update-text id content))))]
      [:foreignObject props
       [:div {:style (normalize-props style)
              :ref "container"
              :on-input on-input
              :contentEditable true}]])))

;; --- Text Shape Wrapper

;; NOTE: this is a hack for the browser rendering.
;;
;; Without forcing rerender, when the shape is displaced
;; and only x and y attrs are updated in dom, the whole content
;; of the foreignObject becomes sometimes partially or
;; completelly invisible. The complete dom rerender fixes that
;; problem.

(defn text-shape-wrapper-did-mount
  [own]
  (let [[shape] (:rum/args own)
        dom (mx/ref-node own "fobject")
        html (mx/render-static-html (text-shape-html shape))]
    (set! (.-innerHTML dom) html))
    own)

(defn text-shape-wrapper-did-remount
  [old own]
  (let [[old-shape] (:rum/args old)
        [shape] (:rum/args own)]
    (when (not= shape old-shape)
      (let [dom (mx/ref-node own "fobject")
            html (mx/render-static-html (text-shape-html shape))]
        (set! (.-innerHTML dom) html)))
    own))

(mx/defc text-shape-wrapper
  {:did-mount text-shape-wrapper-did-mount
   :did-remount text-shape-wrapper-did-remount}
  [{:keys [id modifiers] :as shape}]
  (let [{:keys [displacement resize]} modifiers
        xfmt (cond-> (gmt/matrix)
               displacement (gmt/multiply displacement)
               resize (gmt/multiply resize))

        {:keys [x1 y1 width height] :as shape} (-> (geom/transform shape xfmt)
                                                   (geom/size))
        moving? (boolean displacement)]
    [:foreignObject {:x x1
                     :y y1
                     :class (classnames :move-cursor moving?)
                     :id (str id)
               :ref "fobject"
                     :width width
                     :height height}]))

;; --- Text Shape Html

(mx/defc text-shape-html
  [{:keys [content] :as shape}]
  (let [style (make-style shape)]
    [:div {:style style} content]))

;; --- Text Shape Html

(mx/defc text-shape
  {:mixins [mx/static]
   :did-mount text-shape-wrapper-did-mount
   :did-remount text-shape-wrapper-did-remount}
  [{:keys [id content modifiers] :as shape}]
  (let [{:keys [displacement resize]} modifiers
        xfmt (cond-> (gmt/matrix)
               displacement (gmt/multiply displacement)
               resize (gmt/multiply resize))

        {:keys [x1 y1 width height] :as shape} (-> (geom/transform shape xfmt)
                                                   (geom/size))
        moving? (boolean displacement)
        style (make-style shape)]
    [:foreignObject {:x x1
                     :y y1
                     :class (classnames :move-cursor moving?)
                     :id (str id)
                     :ref "fobject"
                     :width width
                     :height height}
     [:div {:style style}
      [:p content]]]))
