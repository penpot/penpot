;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.shapes.text
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.core :as ui]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.common :as common]
            [uxbox.ui.shapes.attrs :as attrs]
            [uxbox.util.geom :as geom]
            [uxbox.util.color :as color]
            [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

;; --- Events

;; FIXME: try to reuse the common.

(defn on-mouse-down
  [event own {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)
        local (:rum/local own)
        drawing? @common/drawing-state-l]
    (when-not (:blocked shape)
      (cond
        (or drawing?
            (:edition @local)
            (and group (:locked (geom/resolve-parent shape))))
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (ui/acquire-action! "ui.shape.move")
          (rs/emit! (uds/select-shape id)))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (rs/emit! (uds/select-shape id))
            (rs/emit! (uds/deselect-all)
                      (uds/select-shape id))))

        :else
        (do
          (dom/stop-propagation event)
          (ui/acquire-action! "ui.shape.move"))))))

(defn on-mouse-up
  [event {:keys [id group] :as shape}]
  (cond
    (and group (:locked (geom/resolve-parent shape)))
    nil

    :else
    (do
      (dom/stop-propagation event)
      (ui/release-action! "ui.shape"))))

;; --- Text Component

(defn- text-component-did-mount
  [own]
  (letfn [(on-double-click [ev]
            (let [container (mx/get-ref-dom own "container")
                  local (:rum/local own)]
              (swap! local assoc :edition true)
              (ui/acquire-action! "ui.text.edit")
              (set! (.-contentEditable container) true)
              (.setAttribute container "contenteditable" "true")
              (.focus container)))
          (on-blur [ev]
            (let [container (mx/get-ref-dom own "container")
                  local (:rum/local own)]
              (ui/release-action! "ui.text.edit")
              (swap! local assoc :edition false)
              (set! (.-contentEditable container) false)
              (.removeAttribute container "contenteditable")))
          (on-input [ev]
            (let [content (dom/event->inner-text ev)
                  sid (:id (first (:rum/props own)))]
              (rs/emit! (uds/update-text sid {:content content}))))]
    (let [main-dom (mx/get-ref-dom own "main")
          cntr-dom (mx/get-ref-dom own "container")
          key1 (events/listen main-dom EventType.DBLCLICK on-double-click)
          key2 (events/listen cntr-dom EventType.BLUR on-blur)
          key3 (events/listen cntr-dom EventType.INPUT on-input)]
      (assoc own ::key1 key1 ::key2 key2 ::key3 key3))))

(defn- text-component-will-unmount
  [own]
  (let [key1 (::key1 own)
        key2 (::key2 own)
        key3 (::key3 own)]
    (events/unlistenByKey key1)
    (events/unlistenByKey key2)
    (events/unlistenByKey key3)
    (dissoc own ::key1 ::key2 ::key3)))

(defn- text-component-transfer-state
  [old-own own]
  (let [data (select-keys old-own [::key1 ::key2 ::key3])]
    (merge own data)))

(declare text-shape)
(declare text-shape-render)

(defn- text-component-render
  [own shape]
  (let [{:keys [id x1 y1 content group]} shape
        selected (rum/react common/selected-shapes-l)
        selected? (and (contains? selected id) (= (count selected) 1))
        on-mouse-down #(on-mouse-down % own shape selected)
        on-mouse-up #(on-mouse-up % shape)
        local (:rum/local own)
        shape (assoc shape :editing? (:edition @local false))]
    (html
     [:g.shape {:class (when selected? "selected")
                :ref "main"
                :on-mouse-down on-mouse-down
                :on-mouse-up on-mouse-up}
      (text-shape-render own shape)])))

(def text-component
  (mx/component
   {:render text-component-render
    :name "text-componet"
    :did-mount text-component-did-mount
    :will-unmount text-component-will-unmount
    :transfer-state text-component-transfer-state
    :mixins [mx/static rum/reactive (mx/local)]}))

;; --- Test Shape

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
      :textAlign align
      :fontFamily family
      :fontWeight weight
      :fontStyle style}
     (when line-height {:lineHeight line-height})
     (when letter-spacing {:letterSpacing letter-spacing}))))

(defn- text-shape-render
  [own {:keys [id x1 y1 x2 y2 content drawing? editing?] :as shape}]
  (let [key (str id)
        rfm (geom/transformation-matrix shape)
        size (geom/size shape)
        props {:x x1 :y y1
               :transform (str rfm)}
        attrs (merge props size)
        style (make-style shape)]
    (html
     [:g
      (if (or drawing? editing?)
        [:g
         [:rect (merge attrs +select-rect-attrs+)]])
      [:foreignObject attrs
       [:p {:ref "container" :style style} content]]])))

;; (def text-shape
;;   (mx/component
;;    {:render text-shape-render
;;     :name "text-shape"
;;     :mixins [mx/static]}))
