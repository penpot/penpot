(ns uxbox.ui.workspace.rules
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.state :as s]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.workspace.base :as wd]
            [uxbox.ui.util :as util]))


(def viewport-height  3000)
(def viewport-width 3000)

(def document-start-x 50)
(def document-start-y 50)

(defn h-rule-render
  [zoom]
  (let [left (rum/react wd/left-scroll)
        width viewport-width
        start-width document-start-x
        padding 20
        zoom 1
        big-ticks-mod (/ 100 zoom)
        mid-ticks-mod (/ 50 zoom)
        step-size 10
        ticks (concat (range (- padding start-width) 0 step-size)
                      (range 0 (- width start-width padding) step-size))
        lines (fn [position value padding]
                (cond
                  (< (mod value big-ticks-mod) step-size)
                  (html
                   [:g
                    [:line {:x1 position :x2 position :y1 5 :y2 padding :stroke "#7f7f7f"}]
                    [:text {:x (+ position 2) :y 13 :fill "#7f7f7f" :style {:font-size "12px"}} value]])
                  (< (mod value mid-ticks-mod) step-size)
                  (html
                   [:line {:key position :x1 position :x2 position :y1 10 :y2 padding :stroke "#7f7f7f"}])
                  :else
                  (html
                   [:line {:key position :x1 position :x2 position :y1 15 :y2 padding :stroke "#7f7f7f"}])))]
    (html
     [:svg.horizontal-rule
      {:width 3000
       :height 3000
       :style {:left (str (- (- left 50)) "px")}}
      [:g
       [:rect {:x padding :y 0 :width width :height padding :fill "#bab7b7"}]
       [:rect {:x 0 :y 0 :width padding :height padding :fill "#bab7b7"}]]
      [:g
       (for [tick ticks]
         (let [position (* (+ tick start-width) zoom)]
           (rum/with-key (lines position tick padding) (str tick))))]])))

(def h-rule
  (util/component
   {:render h-rule-render
    :name "h-rule"
    :mixins [rum/reactive]}))


(defn v-rule-render
  [zoom]
  (let [height viewport-height
        start-height document-start-y
        top (rum/react wd/top-scroll)
        zoom 1
        padding 20
        big-ticks-mod (/ 100 zoom)
        mid-ticks-mod (/ 50 zoom)
        step-size 10
        ticks (concat (range (- padding start-height) 0 step-size)
                      (range 0 (- height start-height padding) step-size))
        lines (fn [position value padding]
                (cond
                  (< (mod value big-ticks-mod) step-size)
                  (html
                   [:g {:key position}
                    [:line {:y1 position :y2 position :x1 5 :x2 padding :stroke "#7f7f7f"}]
                    [:text {:y position :x 5 :transform (str/format "rotate(90 0 %s)" position) :fill "#7f7f7f" :style {:font-size "12px"}} value]])

                  (< (mod value mid-ticks-mod) step-size)
                  (html
                   [:line {:key position :y1 position :y2 position :x1 10 :x2 padding :stroke "#7f7f7f"}])

                  :else
                  (html
                   [:line {:key position :y1 position :y2 position :x1 15 :x2 padding :stroke "#7f7f7f"}])))]
    (html
     [:svg.vertical-rule
      {:width 3000
       :height 3000
       :style {:top (str (- top) "px")}}
      [:g
       [:rect {:x 0 :y padding :height height :width padding :fill "#bab7b7"}]
       (for [tick ticks]
         (let [position (* (+ tick start-height) zoom)]
           (rum/with-key (lines position tick padding) (str tick))))]])))

(def v-rule
  (util/component
   {:render v-rule-render
    :name "v-rule"
    :mixins [rum/reactive]}))
