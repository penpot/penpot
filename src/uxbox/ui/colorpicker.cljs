;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.colorpicker
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.schema :as sc]
            [uxbox.util.color :as color]
            [uxbox.util.math :as mth]
            [uxbox.ui.mixins :as mx])
  (:import goog.events.EventType))

;; --- Picker Box

(defn- picker-box-render
  [own]
  (html
   [:svg {:width "100%" :height "100%" :version "1.1"}
    [:defs
     [:linearGradient {:id "gradient-black"
                       :x1 "0%" :y1 "100%"
                       :x2 "0%" :y2 "0%"}
      [:stop {:offset "0%" :stopColor "#000000" :stopOpacity "1"}]
      [:stop {:offset "100%" :stopColor "#CC9A81" :stopOpacity "0"}]]
     [:linearGradient {:id "gradient-white"
                       :x1 "0%" :y1 "100%"
                       :x2 "100%" :y2 "100%"}
      [:stop {:offset "0%" :stopColor "#FFFFFF" :stopOpacity "1"}]
      [:stop {:offset "100%" :stopColor "#CC9A81" :stopOpacity "0"}]]]
    [:rect {:x "0" :y "0" :width "100%" :height "100%"
            :fill "url(#gradient-white)"}]
    [:rect {:x "0" :y "0" :width "100%" :height "100%"
            :fill "url(#gradient-black)"}]]))

(def picker-box
  (mx/component
   {:render picker-box-render
    :name "picker-box"
    :mixins []}))

;; --- Slider Box

(defn slider-box-render
  [own]
  (html
   [:svg {:width "100%" :height "100%" :version "1.1"}
    [:defs
     [:linearGradient {:id "gradient-hsv"
                       :x1 "0%" :y1 "100%"
                       :x2 "0%" :y2 "0%"}
      [:stop {:offset "0%" :stopColor "#FF0000" :stopOpacity "1"}]
      [:stop {:offset "13%" :stopColor "#FF00FF" :stopOpacity "1"}]
      [:stop {:offset "25%" :stopColor "#8000FF" :stopOpacity "1"}]
      [:stop {:offset "38%" :stopColor "#0040FF" :stopOpacity "1"}]
      [:stop {:offset "50%" :stopColor "#00FFFF" :stopOpacity "1"}]
      [:stop {:offset "63%" :stopColor "#00FF40" :stopOpacity "1"}]
      [:stop {:offset "75%" :stopColor "#0BED00" :stopOpacity "1"}]
      [:stop {:offset "88%" :stopColor "#FFFF00" :stopOpacity "1"}]
      [:stop {:offset "100%" :stopColor "#FF0000" :stopOpacity "1"}]]]
    [:rect {:x 0 :y 0 :width "100%" :height "100%"
            :fill "url(#gradient-hsv)"}]]))

(def slider-box
  (mx/component
   {:render slider-box-render
    :name "slider-box"
    :mixins []}))

;; --- Color Picker

(defn- on-picker-click
  [local on-change color event]
  (let [event (.-nativeEvent event)
        my (.-offsetY event)
        height (:p-height @local)
        width (:p-width @local)
        mx (.-offsetX event)
        my (.-offsetY event)
        [h] color
        s (/ mx width)
        v (/ (- height my) height)]
    (on-change (color/hsv->hex [(+ h 15) s (* v 255)]))
    (swap! local dissoc :color)))

(defn- on-slide-click
  [local event]
  (let [event (.-nativeEvent event)
        my (.-offsetY event)
        h  (* (/ my (:s-height @local)) 360)]
    (swap! local assoc :color [h 1 255])))

(defn- colorpicker-render
  [own & {:keys [value on-change] :or {value "#d4edfb"}}]
  (let [local (:rum/local own)
        [h s v :as color] (if (:color @local)
                            (:color @local)
                            (let [[h s v] (color/hex->hsv value)]
                              [(if (pos? h) (- h 15) h) s v]))
        bg (color/hsv->hex [(+ h 15) 1 255])
        sit (- (/ (* h (:s-height @local)) 360)
               (/ (:si-height @local) 2))
        pit (- (* s (:p-width @local))
               (/ (:pi-height @local) 2))
        pil (- (- (:p-height @local) (* (/ v 255) (:p-height @local)))
               (/ (:pi-width @local) 2))
        on-mouse-down #(swap! local assoc :mousedown true)
        on-mouse-up #(swap! local assoc :mousedown false)

        on-mouse-move-slide #(when (:mousedown @local)
                               (on-slide-click local %))
        on-mouse-move-picker #(when (:mousedown @local)
                                (on-picker-click local on-change color %))]
    (html
     [:div.color-picker
      [:div.picker-wrapper
       [:div.picker
        {:ref "picker"
         :on-click (partial on-picker-click local on-change color)
         :on-mouse-down on-mouse-down
         :on-mouse-up on-mouse-up
         :on-mouse-move on-mouse-move-picker
         :style {:backgroundColor bg}}
        (picker-box)]
       [:div.picker-indicator
        {:ref "picker-indicator"
         :style {:top (str pil "px")
                 :left (str pit "px")
                 :pointerEvents "none"}}]]
      [:div.slide-wrapper
       [:div.slide
        {:ref "slide"
         :on-mouse-down on-mouse-down
         :on-mouse-up on-mouse-up
         :on-mouse-move on-mouse-move-slide
         :on-click (partial on-slide-click local)}
        (slider-box)]
       [:div.slide-indicator
        {:ref "slide-indicator"
         :style {:top (str sit "px")
                 :pointerEvents "none"}}]]])))

(defn- colorpicker-did-mount
  [own]
  (let [local (:rum/local own)
        picker (mx/get-ref-dom own "picker")
        slide (mx/get-ref-dom own "slide")
        picker-ind (mx/get-ref-dom own "picker-indicator")
        slide-ind (mx/get-ref-dom own "slide-indicator")]
    (swap! local assoc
           :pi-height (.-offsetHeight picker-ind)
           :pi-width (.-offsetWidth picker-ind)
           :si-height (.-offsetHeight slide-ind)
           :p-height (.-offsetHeight picker)
           :p-width (.-offsetWidth picker)
           :s-height (.-offsetHeight slide))
    own))

(def ^:static colorpicker
  (mx/component
   {:render colorpicker-render
    :did-mount colorpicker-did-mount
    :name "colorpicker"
    :mixins [mx/static (mx/local)]}))
