;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.rules
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as s]
   [uxbox.util.dom :as dom]))

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

(mf/defc horizontal-text-label
  [{:keys [zoom value] :as props}]
  (let [big-ticks-mod (big-ticks-mod zoom)
        pos (+ (* value zoom)
               rule-padding
               (* c/canvas-start-x zoom)
               c/canvas-scroll-padding)]
    (when (< (mod value big-ticks-mod) step-size)
      [:text {:x (+ pos 2)
              :y 13
              :key (str pos)
              :fill "#9da2a6"
              :style {:font-size "12px"}}
       value])))

;; --- Horizontal Text Label

(mf/defc vertical-text-label
  [{:keys [zoom value] :as props}]
  (let [big-ticks-mod (big-ticks-mod zoom)
        pos (+ (* value zoom)
               (* c/canvas-start-x zoom)
               c/canvas-scroll-padding)]
    (when (< (mod value big-ticks-mod) step-size)
      [:text {:y (- pos 3)
              :x 5
              :key (str pos)
              :fill "#9da2a6"
              :transform (str/format "rotate(90 0 %s)" pos)
              :style {:font-size "12px"}}
       value])))

;; --- Horizontal Rule Ticks (Component)

(mf/def horizontal-rule-ticks
  :mixins #{mf/memo}
  :render
  (fn [own zoom]
    (let [zoom (or zoom 1)
          path (reduce (partial make-vertical-tick zoom) [] +ticks+)]
      [:g
       [:path {:d (str/join " " path)}]
       (for [tick +ticks+]
         [:& horizontal-text-label {:zoom zoom :value tick :key tick}])])))

;; --- Vertical Rule Ticks (Component)

(mf/def vertical-rule-ticks
  :mixins #{mf/memo}
  :render
  (fn [own zoom]
    (let [zoom (or zoom 1)
          path (reduce (partial make-horizontal-tick zoom) [] +ticks+)]
      [:g
       [:path {:d (str/join " " path)}]
       (for [tick +ticks+]
         [:& vertical-text-label {:zoom zoom :value tick :key tick}])])))

;; --- Horizontal Rule (Component)

(mf/def horizontal-rule
  :mixins #{mf/memo mf/reactive}
  :render
  (fn [own props]
    (let [scroll (mf/react refs/workspace-scroll)
          zoom (mf/react refs/selected-zoom)
          scroll-x (:x scroll)
          translate-x (- (- c/canvas-scroll-padding) (:x scroll))]
      [:svg.horizontal-rule
       {:width c/viewport-width
        :height 20}
       [:rect {:height 20
               :width c/viewport-width}]
       [:g {:transform (str "translate(" translate-x ", 0)")}
        (horizontal-rule-ticks zoom)]])))

;; --- Vertical Rule (Component)

(mf/def vertical-rule
  :mixins #{mf/memo mf/reactive}
  :render
  (fn [own props]
    (let [scroll (mf/react refs/workspace-scroll)
          zoom (mf/react refs/selected-zoom)
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
               :width 20}]])))
