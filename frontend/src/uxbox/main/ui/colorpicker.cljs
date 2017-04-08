;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.colorpicker
  (:require [lentes.core :as l]
            [goog.events :as events]
            [uxbox.util.forms :as sc]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.math :as mth]
            [uxbox.util.data :as data]
            [uxbox.util.dom :as dom]
            [uxbox.util.color :as color])
  (:import goog.events.EventType))

;; --- Picker Box

(mx/defc picker-box
  []
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
           :fill "url(#gradient-black)"}]])

;; --- Slider Box

(mx/defc slider-box
  []
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
           :fill "url(#gradient-hsv)"}]])

(def default-dimensions
  {:pi-height 5
   :pi-width 5
   :si-height 10
   :p-height 200
   :p-width 200
   :s-height 200})

(def small-dimensions
  {:pi-height 5
   :pi-width 5
   :si-height 10
   :p-height 170
   :p-width 170
   :s-height 170})

;; --- Color Picker

(defn- on-picker-click
  [local dimensions on-change color event]
  (let [event (.-nativeEvent event)
        my (.-offsetY event)
        height (:p-height dimensions)
        width (:p-width dimensions)
        mx (.-offsetX event)
        my (.-offsetY event)
        [h] color
        s (/ mx width)
        v (/ (- height my) height)
        hex (color/hsv->hex [h s (* v 255)])]
    (swap! local assoc :color [h s (* v 255)])
    (on-change hex)))

(defn- on-slide-click
  [local dimensions on-change color event]
  (let [event (.-nativeEvent event)
        my (.-offsetY event)
        h  (* (/ my (:s-height dimensions)) 360)
        hsv [(+ h 15) (second color) (nth color 2)]
        hex (color/hsv->hex hsv)]
    (swap! local assoc :color hsv)
    (on-change hex)))

(mx/defcs colorpicker
  {:mixins [mx/static (mx/local)]}
  [{:keys [rum/local] :as own} & {:keys [value on-change theme]
                                  :or {value "#d4edfb" theme :default}}]
  (let [value-rgb (color/hex->rgb value)
        classes (case theme
                  :default "theme-default"
                  :small "theme-small")
        dimensions (case theme
                     :default default-dimensions
                     :small small-dimensions
                     default-dimensions)
        [h s v :as color] (or (:color @local)
                              (color/hex->hsv value))
        bg (color/hsv->hex [h 1 255])
        pit (- (* s (:p-width dimensions))
               (/ (:pi-height dimensions) 2))
        pil (- (- (:p-height dimensions) (* (/ v 255) (:p-height dimensions)))
               (/ (:pi-width dimensions) 2))

        sit (- (/ (* (- h 15) (:s-height dimensions)) 360)
               (/ (:si-height dimensions) 2))]
    (letfn [(on-mouse-down [event]
              (swap! local assoc :mousedown true))
            (on-mouse-up [event]
              (swap! local assoc :mousedown false))
            (on-mouse-move-slide [event]
              (when (:mousedown @local)
                (on-slide-click local dimensions on-change color event)))
            (on-mouse-move-picker [event]
              (when (:mousedown @local)
                (on-picker-click local dimensions on-change color event)))
            (on-hex-changed [event]
              (let [value (-> (dom/get-target event)
                              (dom/get-value))]
                (when (color/hex? value)
                  (on-change value))))
            (on-rgb-change [rgb id event]
              (let [value (-> (dom/get-target event)
                              (dom/get-value)
                              (data/parse-int 0))
                    rgb (assoc rgb id value)
                    hex (color/rgb->hex rgb)]
                (when (color/hex? hex)
                  (on-change hex))))]
      [:div.color-picker {:class classes}
       [:div.picker-area
        #_[:div.tester {:style {:width "100px" :height "100px"
                                :border "1px solid black"
                                :position "fixed" :top "50px" :left "50px"
                                :backgroundColor (color/hsv->hex color)}}]
        [:div.picker-wrapper
         [:div.picker
          {:ref "picker"
           :on-click (partial on-picker-click local dimensions on-change color)
           :on-mouse-down on-mouse-down
           :on-mouse-up on-mouse-up
           :on-mouse-move on-mouse-move-picker
           :style {:backgroundColor bg}}
          (picker-box)]
         (when-not (:mousedown @local)
           [:div.picker-indicator
            {:ref "picker-indicator"
             :style {:top (str pil "px")
                     :left (str pit "px")
                     :pointerEvents "none"}}])]
        [:div.slide-wrapper
         [:div.slide
          {:ref "slide"
           :on-mouse-down on-mouse-down
           :on-mouse-up on-mouse-up
           :on-mouse-move on-mouse-move-slide
           :on-click (partial on-slide-click local dimensions on-change color)}
          (slider-box)]
         [:div.slide-indicator
          {:ref "slide-indicator"
           :style {:top (str sit "px")
                   :pointerEvents "none"}}]]]

       [:div.inputs-area
        [:input.input-text
         {:placeholder "#"
          :type "text"
          :value value
          :on-change on-hex-changed}]
        [:div.row-flex
         [:input.input-text
          {:placeholder "R"
           :on-change (partial on-rgb-change value-rgb 0)
           :value (nth value-rgb 0)
           :type "number"}]
         [:input.input-text
          {:placeholder "G"
           :on-change (partial on-rgb-change value-rgb 1)
           :value (nth value-rgb 1)
           :type "number"}]
         [:input.input-text
          {:placeholder "B"
           :on-change (partial on-rgb-change value-rgb 2)
           :value (nth value-rgb 2)
           :type "number"}]]]])))
