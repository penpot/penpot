;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.rules
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.main.store :as s]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.util.dom :as dom]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Constants & Helpers

(def step-padding 20)
(def step-size 10)

(defn big-ticks-mod [zoom] (/ 100 zoom))
(defn mid-ticks-mod [zoom] (/ 50 zoom))

(def +ticks+
  (concat (range (- (/ c/viewport-width 1)) 0 step-size)
          (range 0 (/ c/viewport-width 1) step-size)))

(def rule-padding 20)

(defn- make-vertical-tick
  [zoom acc value]
  (let [big-ticks-mod (big-ticks-mod zoom)
        mid-ticks-mod (mid-ticks-mod zoom)
        pos (+ (* value zoom)
               rule-padding
               (* c/canvas-start-x zoom)
               c/canvas-scroll-padding)]
    (cond
      (< (mod value big-ticks-mod) step-size)
      (conj acc (str/format "M %s %s L %s %s" pos 5 pos step-padding))

      (< (mod value mid-ticks-mod) step-size)
      (conj acc (str/format "M %s %s L %s %s" pos 10 pos step-padding))

      :else
      (conj acc (str/format "M %s %s L %s %s" pos 15 pos step-padding)))))

(defn- make-horizontal-tick
  [zoom acc value]
  (let [big-ticks-mod (big-ticks-mod zoom)
        mid-ticks-mod (mid-ticks-mod zoom)
        pos (+ (* value zoom)
               (* c/canvas-start-x zoom)
               c/canvas-scroll-padding)]
    (cond
      (< (mod value big-ticks-mod) step-size)
      (conj acc (str/format "M %s %s L %s %s" 5 pos step-padding pos))

      (< (mod value mid-ticks-mod) step-size)
      (conj acc (str/format "M %s %s L %s %s" 10 pos step-padding pos))

      :else
      (conj acc (str/format "M %s %s L %s %s" 15 pos step-padding pos)))))

;; --- Horizontal Text Label

(defn- horizontal-text-label
  [zoom value]
  (let [big-ticks-mod (big-ticks-mod zoom)
        pos (+ (* value zoom)
               rule-padding
               (* c/canvas-start-x zoom)
               c/canvas-scroll-padding)]
    (when (< (mod value big-ticks-mod) step-size)
      (html
       [:text {:x (+ pos 2)
               :y 13
               :key (str pos)
               :fill "#9da2a6"
               :style {:font-size "12px"}}
        value]))))

;; --- Horizontal Text Label

(defn- vertical-text-label
  [zoom value]
  (let [big-ticks-mod (big-ticks-mod zoom)
        pos (+ (* value zoom)
               (* c/canvas-start-x zoom)
               ;; c/canvas-start-x
               c/canvas-scroll-padding)]
    (when (< (mod value big-ticks-mod) step-size)
      (html
       [:text {:y (- pos 3)
               :x 5
               :key (str pos)
               :fill "#9da2a6"
               :transform (str/format "rotate(90 0 %s)" pos)
               :style {:font-size "12px"}}
        value]))))

;; --- Horizontal Rule Ticks (Component)

(mx/defc horizontal-rule-ticks
  {:mixins [mx/static]}
  [zoom]
  (let [zoom (or zoom 1)
        path (reduce (partial make-vertical-tick zoom) [] +ticks+)
        labels (->> (map (partial horizontal-text-label zoom) +ticks+)
                    (filterv identity))]
    [:g
     [:path {:d (str/join " " path) :stroke "#9da2a6"}]
     labels]))

;; --- Vertical Rule Ticks (Component)

(mx/defc vertical-rule-ticks
  {:mixins [mx/static]}
  [zoom]
  (let [zoom (or zoom 1)
        path (reduce (partial make-horizontal-tick zoom) [] +ticks+)
        labels (->> (map (partial vertical-text-label zoom) +ticks+)
                    (filterv identity))]
    [:g
     [:path {:d (str/join " " path) :stroke "#9da2a6"}]
     labels]))

;; --- Horizontal Rule (Component)

(mx/defc horizontal-rule
  {:mixins [mx/static mx/reactive]}
  []
  (let [scroll (mx/react refs/workspace-scroll)
        zoom (mx/react refs/selected-zoom)
        scroll-x (:x scroll)
        translate-x (- (- c/canvas-scroll-padding) (:x scroll))]
    [:svg.horizontal-rule
     {:width c/viewport-width
      :height 20}
     [:rect {:height 20
             :width c/viewport-width
             :fill "rgb(233, 234, 235)"}]
     [:g {:transform (str "translate(" translate-x ", 0)")}
      (horizontal-rule-ticks zoom)]]))

;; --- Vertical Rule (Component)

(mx/defc vertical-rule
  {:mixins [mx/static mx/reactive]}
  []
  (let [scroll (mx/react refs/workspace-scroll)
        zoom (mx/react refs/selected-zoom)
        scroll-y (:y scroll)
        translate-y (- (- c/canvas-scroll-padding) (:y scroll))]
    [:svg.vertical-rule
     {:width 20
      :height c/viewport-height}

     [:g {:transform (str  "translate(0, " translate-y ")")}
      (vertical-rule-ticks zoom)]
     [:rect {:x 0
             :y 0
             :height 20
             :width 20
             :fill "rgb(233, 234, 235)"}]]))
