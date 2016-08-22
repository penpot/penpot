;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.color :as color]
            [uxbox.util.dom :as dom]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.ui.workspace.rlocks :as rlocks]
            [uxbox.main.geom :as geom])
  (:import goog.events.EventType))

;; --- Events

(defn handle-mouse-down
  [event local {:keys [id group] :as shape} selected]
  (if (and (not (:blocked shape))
           (or @common/drawing-state-ref
               (:edition @local)
               (and group (:locked (geom/resolve-parent shape)))))
    nil
    (common/on-mouse-down event shape selected)))

;; --- Text Component

(declare text-shape)
(declare text-shape-edit)

(mx/defcs text-component
  {:mixins [mx/static mx/reactive (mx/local)]}
  [own {:keys [id x1 y1 content group] :as shape}]
  (let [selected (mx/react common/selected-ref)
        selected? (and (contains? selected id)
                       (= (count selected) 1))
        local (:rum/local own)]
    (letfn [(on-mouse-down [event]
              (handle-mouse-down event local shape selected))
            (on-done [_]
              (swap! local assoc :edition false))
            (on-double-click [event]
              (swap! local assoc :edition true)
              (rlocks/acquire! :ui/text-edit))]
      [:g.shape {:class (when selected? "selected")
                 :ref "main"
                 :on-double-click on-double-click
                 :on-mouse-down on-mouse-down}
       (if (:edition @local false)
         (text-shape-edit shape on-done)
         (text-shape shape))])))

;; --- Text Styles Helpers

(def +style-attrs+ [:font-size])
(def +select-rect-attrs+
  {:stroke-dasharray "5,5"
   :style {:stroke "#333" :fill "transparent"
           :stroke-opacity "0.4"}})

(defn- make-style
  [{:keys [font fill opacity]
    :or {fill "#000000" opacity 1}}]
  (let [{:keys [family weight style size align
                line-height letter-spacing]
         :or {family "sourcesanspro"
              weight "normal"
              style "normal"
              line-height 1.4
              letter-spacing 1
              align "left"
              size 16}} font
        color (-> fill
                  (color/hex->rgba opacity)
                  (color/rgb->str))]
    (merge
     {:fontSize (str size "px")
      :color color
      :whiteSpace "pre"
      :textAlign align
      :fontFamily family
      :fontWeight weight
      :fontStyle style}
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
  [{:keys [id x1 y1 content] :as shape} on-done]
  (let [size (geom/size shape)
        style (make-style shape)
        rfm (geom/transformation-matrix shape)
        props {:x x1 :y y1
               :transform (str rfm)}
        props (merge props size)]
    (letfn [(on-blur [ev]
              (rlocks/release! :ui/text-edit)
              (on-done))
            (on-input [ev]
              (let [content (dom/event->inner-text ev)]
                (rs/emit! (uds/update-text id {:content content}))))]
      [:g
       [:rect (merge props +select-rect-attrs+)]
       [:foreignObject props
        [:p {:ref "container"
             :on-blur on-blur
             :on-input on-input
             :contentEditable true
             :style style}]]])))

;; --- Text Shape

(mx/defc text-shape
  {:mixins [mx/static]}
  [{:keys [id x1 y1 content] :as shape}]
  (let [key (str "shape-" id)
        rfm (geom/transformation-matrix shape)
        size (geom/size shape)
        props {:x x1 :y y1
               :transform (str rfm)}
        attrs (merge props size)
        style (make-style shape)]
    [:foreignObject attrs
     [:p {:style style} content]]))
