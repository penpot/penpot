;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.rules
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.main.constants :as c]
            [uxbox.main.state :as s]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.common.ui.mixins :as mx]))

;; --- Constants & Helpers

(def ^:const step-padding 20)
(def ^:const step-size 10)

(defn big-ticks-mod [zoom] (/ 100 zoom))
(defn mid-ticks-mod [zoom] (/ 50 zoom))

(def ^:const +ticks+
  (concat (range (- (/ c/viewport-width 1)) 0 step-size)
          (range 0 (/ c/viewport-width 1) step-size)))

(def ^:const rule-padding 20)

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

(defn- horizontal-rule-ticks-render
  [own zoom]
  (let [zoom (or zoom 1)
        path (reduce (partial make-vertical-tick zoom) [] +ticks+)
        labels (->> (map (partial horizontal-text-label zoom) +ticks+)
                    (filterv identity))]
    (html
     [:g
      [:path {:d (str/join " " path) :stroke "#9da2a6"}]
      labels])))

(def ^:const ^:private horizontal-rule-ticks
  (mx/component
   {:render horizontal-rule-ticks-render
    :name "horizontal-rule-ticks"
    :mixins [mx/static]}))

;; --- Vertical Rule Ticks (Component)

(defn- vertical-rule-ticks-render
  [own zoom]
  (let [zoom (or zoom 1)
        path (reduce (partial make-horizontal-tick zoom) [] +ticks+)
        labels (->> (map (partial vertical-text-label zoom) +ticks+)
                    (filterv identity))]
    (html
     [:g
      [:path {:d (str/join " " path) :stroke "#9da2a6"}]
      labels])))

(def ^:const ^:private vertical-rule-ticks
  (mx/component
   {:render vertical-rule-ticks-render
    :name "vertical-rule-ticks"
    :mixins [mx/static]}))

;; --- Horizontal Rule (Component)

(defn horizontal-rule-render
  [own zoom]
  (let [scroll (rum/react wb/scroll-a)
        scroll-x (:x scroll)
        translate-x (- (- c/canvas-scroll-padding) (:x scroll))]
    (html
     [:svg.horizontal-rule
      {:width c/viewport-width
       :height 20}
      [:g {:transform (str "translate(" translate-x ", 0)")}
       (horizontal-rule-ticks zoom)]])))

(def horizontal-rule
  (mx/component
   {:render horizontal-rule-render
    :name "horizontal-rule"
    :mixins [mx/static rum/reactive]}))

;; --- Vertical Rule (Component)

(defn vertical-rule-render
  [own zoom]
  (let [scroll (rum/react wb/scroll-a)
        scroll-y (:y scroll)
        translate-y (- (- c/canvas-scroll-padding) (:y scroll))]
    (html
     [:svg.vertical-rule
      {:width 20
       :height c/viewport-height}

      [:g {:transform (str  "translate(0, " translate-y ")")}
       (vertical-rule-ticks zoom)]
      [:rect {:x 0
              :y 0
              :height 20
              :width 20
              :fill "rgb(233, 234, 235)"}]])))

(def vertical-rule
  (mx/component
   {:render vertical-rule-render
    :name "vertical-rule"
    :mixins [mx/static rum/reactive]}))
