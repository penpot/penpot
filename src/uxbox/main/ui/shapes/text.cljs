;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.common.rstore :as rs]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.core :as ui]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]
            [uxbox.util.color :as color]
            [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

;; --- Events

(defn handle-mouse-down
  [event local {:keys [id group] :as shape} selected]
  (if (and (not (:blocked shape))
           (or @common/drawing-state-l
               (:edition @local)
               (and group (:locked (geom/resolve-parent shape)))))
    nil
    (common/on-mouse-down event shape selected)))

;; --- Text Component

(declare text-shape)
(declare text-shape-edit)

(defn- text-component-render
  [own {:keys [id x1 y1 content group] :as shape}]
  (let [selected (rum/react common/selected-shapes-l)
        selected? (and (contains? selected id)
                       (= (count selected) 1))
        local (:rum/local own)]
    (letfn [(on-mouse-down [event]
              (handle-mouse-down event local shape selected))
            (on-mouse-up [event]
              (common/on-mouse-up event shape))
            (on-done [_]
              (swap! local assoc :edition false))
            (on-double-click [event]
              (swap! local assoc :edition true)
              (ui/acquire-action! "ui.text.edit"))]
      (html
       [:g.shape {:class (when selected? "selected")
                  :ref "main"
                  :on-double-click on-double-click
                  :on-mouse-down on-mouse-down
                  :on-mouse-up on-mouse-up}
        (if (:edition @local false)
          (text-shape-edit shape on-done)
          (text-shape shape))]))))

(def text-component
  (mx/component
   {:render text-component-render
    :name "text-componet"
    :mixins [mx/static rum/reactive (mx/local)]}))

;; --- Text Styles Helpers

(def ^:const +style-attrs+ [:font-size])
(def ^:const +select-rect-attrs+
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
  (let [[shape] (:rum/props own)
        dom (mx/get-ref-dom own "container")]
    (set! (.-textContent dom) (:content shape ""))
    (.focus dom)
    own))

(defn- text-shape-edit-render
  [own {:keys [id x1 y1 content] :as shape} on-done]
  (let [size (geom/size shape)
        style (make-style shape)
        rfm (geom/transformation-matrix shape)
        props {:x x1 :y y1
               :transform (str rfm)}
        props (merge props size)]
    (letfn [(on-blur [ev]
              (ui/release-action! "ui.text.edit")
              (on-done))
            (on-input [ev]
              (let [content (dom/event->inner-text ev)
                    sid (:id (first (:rum/props own)))]
                (rs/emit! (uds/update-text sid {:content content}))))]
      (html
       [:g
        [:rect (merge props +select-rect-attrs+)]
        [:foreignObject props
         [:p {:ref "container"
              :on-blur on-blur
              :on-input on-input
              :contentEditable true
              :style style}]]]))))

(def text-shape-edit
  (mx/component
   {:render text-shape-edit-render
    :did-mount text-shape-edit-did-mount
    :name "text-shape-edit"
    :mixins [mx/static]}))

;; --- Text Shape

(defn- text-shape-render
  [own {:keys [id x1 y1 content] :as shape}]
  (let [key (str id)
        rfm (geom/transformation-matrix shape)
        size (geom/size shape)
        props {:x x1 :y y1
               :transform (str rfm)}
        attrs (merge props size)
        style (make-style shape)]
    (html
     [:foreignObject attrs
      [:p {:style style} content]])))

(def text-shape
  (mx/component
   {:render text-shape-render
    :name "text-shape"
    :mixins [mx/static]}))
