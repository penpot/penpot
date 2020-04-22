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
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.hooks :refer [use-rxsub]]
   [uxbox.util.dom :as dom]))

;; --- Constants & Helpers

(def rule-padding 20)
(def step-padding 20)
(def step-size 10)
(def scroll-padding 50)

(def +ticks+ (range 0 c/viewport-width step-size))

(defn big-ticks-mod [zoom] (/ 100 zoom))
(defn mid-ticks-mod [zoom] (/ 50 zoom))



(defn- make-vertical-tick
  [zoom acc value]
  (let [big-ticks-mod (big-ticks-mod zoom)
        mid-ticks-mod (mid-ticks-mod zoom)
        pos (+ (* value zoom)
               rule-padding
               scroll-padding)]
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
               scroll-padding)]
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
               scroll-padding)]
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
               scroll-padding)]
    (when (< (mod value big-ticks-mod) step-size)
      [:text {:y (- pos 3)
              :x 5
              :key (str pos)
              :fill "#9da2a6"
              :transform (str/format "rotate(90 0 %s)" pos)
              :style {:font-size "12px"}}
       value])))

;; --- Horizontal Rule Ticks (Component)

(mf/defc horizontal-rule-ticks
  {:wrap [mf/memo]}
  [{:keys [zoom]}]
  (let [path (reduce (partial make-vertical-tick zoom) [] +ticks+)]
    [:g
     [:path {:d (str/join " " path)}]
     (for [tick +ticks+]
       [:& horizontal-text-label {:zoom zoom :value tick :key tick}])]))

;; --- Vertical Rule Ticks (Component)

(mf/defc vertical-rule-ticks
  {:wrap [mf/memo]}
  [{:keys [zoom]}]
  (let [path (reduce (partial make-horizontal-tick zoom) [] +ticks+)]
    [:g
     [:path {:d (str/join " " path)}]
     (for [tick +ticks+]
       [:& vertical-text-label {:zoom zoom :value tick :key tick}])]))

;; --- Horizontal Rule (Component)

(mf/defc horizontal-rule
  {:wrap [mf/memo]}
  [props]
  (let [scroll (use-rxsub  ms/viewport-scroll)
        zoom (mf/deref refs/selected-zoom)
        translate-x (- (- scroll-padding) (:x scroll))]
    [:svg.horizontal-rule
     {:width c/viewport-width
      :height 20}
     [:rect {:height 20
             :width c/viewport-width}]
     [:g {:transform (str "translate(" translate-x ", 0)")}
      [:& horizontal-rule-ticks {:zoom zoom}]]]))

;; --- Vertical Rule (Component)

(mf/defc vertical-rule
  {:wrap [mf/memo]}
  [props]
  (let [scroll (use-rxsub ms/viewport-scroll)
        zoom (or (mf/deref refs/selected-zoom) 1)
        scroll-y (:y scroll)
        translate-y (+ (- scroll-padding)
                       (- (:y scroll)))
        ]
    [:svg.vertical-rule {:width 20
                         ;; :x 0 :y 0
                         :height c/viewport-height}

     [:g {:transform (str  "translate(0, " (+ translate-y 20) ")")}
      [:& vertical-rule-ticks {:zoom zoom}]]
     [:rect {:x 0
             :y 0
             :height 20
             :width 20}]]))
