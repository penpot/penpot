(ns uxbox.ui.workspace.rules
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.state :as s]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]))

(def ^:static zoom 1)
(def ^:static padding 20)
(def ^:static step-size 10)
(def ^:static start-width wb/document-start-x)
(def ^:static start-height wb/document-start-y)
(def ^:static big-ticks-mod (/ 100 zoom))
(def ^:static mid-ticks-mod (/ 50 zoom))
(def ^:static scroll-left 0)
(def ^:static scroll-top 0)

(defn h-line
  [position value]
  (cond
    (< (mod value big-ticks-mod) step-size)
    (html
     [:g {:key position}
      [:line {:x1 position :x2 position :y1 5 :y2 padding :stroke "#7f7f7f"}]
      [:text {:x (+ position 2) :y 13 :fill "#7f7f7f" :style {:font-size "12px"}} value]])

    (< (mod value mid-ticks-mod) step-size)
    (html
     [:line {:key position :x1 position :x2 position :y1 10 :y2 padding :stroke "#7f7f7f"}])

    :else
    (html
     [:line {:key position :x1 position :x2 position :y1 15 :y2 padding :stroke "#7f7f7f"}])))

(defn v-line
  [position value]
  (cond
    (< (mod value big-ticks-mod) step-size)
    (html
     [:g {:key position}
      [:line {:y1 position
              :y2 position
              :x1 5
              :x2 padding
              :stroke "#7f7f7f"}]
      [:text {:y position
              :x 5
              :transform (str/format "rotate(90 0 %s)" position)
              :fill "#7f7f7f"
              :style {:font-size "12px"}}
       value]])

    (< (mod value mid-ticks-mod) step-size)
    (html
     [:line {:key position
             :y1 position
             :y2 position
             :x1 10
             :x2 padding
             :stroke "#7f7f7f"}])

    :else
    (html
     [:line {:key position
             :y1 position
             :y2 position
             :x1 15
             :x2 padding
             :stroke "#7f7f7f"}])))

(defn h-rule-render
  [own]
  (let [width wb/viewport-width
        ticks (concat (range (- padding start-width) 0 step-size)
                      (range 0 (- width start-width padding) step-size))]
    (html
     [:svg.horizontal-rule
      {:width 3000
       :height 3000
       :style {:left (str (- (- scroll-left wb/document-start-x)) "px")}}
      [:g
       [:rect {:x padding :y 0 :width width :height padding :fill "#bab7b7"}]
       [:rect {:x 0 :y 0 :width padding :height padding :fill "#bab7b7"}]]
      [:g
       (for [tick ticks
             :let [pos (* (+ tick start-width) zoom)]]
         (h-line pos tick))]])))

(def h-rule
  (mx/component
   {:render h-rule-render
    :name "h-rule"
    :mixins [mx/static rum/reactive]}))

(defn v-rule-render
  [own]
  (let [height wb/viewport-height
        ticks (concat (range (- padding start-height) 0 step-size)
                      (range 0 (- height start-height padding) step-size))]
    (html
     [:svg.vertical-rule
      {:width 3000
       :height 3000
       :style {:top (str (- 0 scroll-top) "px")}}
      [:g
       [:rect {:x 0 :y padding :height height :width padding :fill "#bab7b7"}]
       (for [tick ticks
             :let [pos (* (+ tick start-height) zoom)]]
         (v-line pos tick))]])))

(def v-rule
  (mx/component
   {:render v-rule-render
    :name "v-rule"
    :mixins [mx/static rum/reactive]}))
