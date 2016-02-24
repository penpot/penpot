(ns uxbox.ui.workspace.rules
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.state :as s]
            [uxbox.util.dom :as dom]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants & Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const zoom 1)
(def ^:const step-padding 20)
(def ^:const step-size 10)
(def ^:const start-width wb/canvas-start-x)
(def ^:const start-height wb/canvas-start-y)
(def ^:const scroll-left 0)
(def ^:const scroll-top 0)

(defn big-ticks-mod [zoom] (/ 100 zoom))
(defn mid-ticks-mod [zoom] (/ 50 zoom))

(def ^:const +ticks+
  (concat (range (- (/ wb/viewport-width 1)) 0 step-size)
          (range 0 (/ wb/viewport-width 1) step-size)))

(def ^:const +rule-padding+ 20)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Horizontal Rule
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn vertical-tick-render
  [own value]
  (let [big-ticks-mod (big-ticks-mod 1)
        mid-ticks-mod (mid-ticks-mod 1)
        big-step? (< (mod value big-ticks-mod) step-size)
        mid-step? (< (mod value mid-ticks-mod) step-size)
        pos (+ value +rule-padding+
               wb/canvas-start-x
               wb/canvas-scroll-padding)
        pos (* pos zoom)]
    (cond
      big-step?
      (html
       [:g {:key value}
        [:line {:x1 pos
                :x2 pos
                :y1 5
                :y2 step-padding
                :stroke "#9da2a6"}]
        [:text {:x (+ pos 2)
                :y 13
                :fill "#9da2a6"
                :style {:font-size "12px"}}
         value]])

      mid-step?
      (html
       [:line {:key pos
               :x1 pos
               :x2 pos
               :y1 10
               :y2 step-padding
               :stroke "#9da2a6"}])

      :else
      (html
       [:line {:key pos
               :x1 pos
               :x2 pos
               :y1 15
               :y2 step-padding
               :stroke "#9da2a6"}]))))

(def ^:const vertical-tick
  (mx/component
   {:render vertical-tick-render
    :name "vertical-tick-render"
    :mixins [mx/static]}))

(defn vertical-ticks-render
  [own]
  (html
   [:g
    (for [value +ticks+]
      (-> (vertical-tick value)
          (rum/with-key value)))]))

(def ^:const vertical-ticks
  (mx/component
   {:render vertical-ticks-render
    :name "vertical-ticks-render"
    :mixins [mx/static]}))

(defn horizontal-rule-render
  [own sidebar?]
  (let [scroll (rum/react wb/scroll-a)
        scroll-x (:x scroll)
        translate-x (- (- wb/canvas-scroll-padding) (:x scroll))]
    (html
     [:svg.horizontal-rule
      {:width wb/viewport-width
       :height 20}
      [:g {:transform (str "translate(" translate-x ", 0)")}
       (vertical-ticks)]])))

(def horizontal-rule
  (mx/component
   {:render horizontal-rule-render
    :name "horizontal-rule"
    :mixins [mx/static rum/reactive]}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Horizontal Rule
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn horizontal-tick-render
  [own value]
  (let [big-ticks-mod (big-ticks-mod 1)
        mid-ticks-mod (mid-ticks-mod 1)
        big-step? (< (mod value big-ticks-mod) step-size)
        mid-step? (< (mod value mid-ticks-mod) step-size)
        pos (+ value
               wb/canvas-start-x
               wb/canvas-scroll-padding)
        pos (* pos zoom)]
  (cond
    big-step?
    (html
     [:g {:key pos}
      [:line {:y1 pos
              :y2 pos
              :x1 5
              :x2 step-padding
              :stroke "#9da2a6"}]
      [:text {:y pos
              :x 5
              :transform (str/format "rotate(90 0 %s)" pos)
              :fill "#9da2a6"
              :style {:font-size "12px"}}
       value]])

    mid-step?
    (html
     [:line {:key pos
             :y1 pos
             :y2 pos
             :x1 10
             :x2 step-padding
             :stroke "#9da2a6"}])

    :else
    (html
     [:line {:key pos
             :y1 pos
             :y2 pos
             :x1 15
             :x2 step-padding
             :stroke "#9da2a6"}]))))

(def ^:const horizontal-tick
  (mx/component
   {:render horizontal-tick-render
    :name "horizontal-tick-render"
    :mixins [mx/static]}))

(defn- horizontal-ticks-render
  [own]
  (html
   [:g
    (for [value +ticks+]
      (-> (horizontal-tick value)
          (rum/with-key value)))]))

(def ^:const horizontal-ticks
  (mx/component
   {:render horizontal-ticks-render
    :name "horizontal-ticks-render"
    :mixins [mx/static]}))

(defn vertical-rule-render
  [own sidebar?]
  (let [scroll (rum/react wb/scroll-a)
        scroll-y (:y scroll)
        translate-y (- (- wb/canvas-scroll-padding) (:y scroll))]
    (html
     [:svg.vertical-rule
      {:width 20
       :height wb/viewport-height}

      [:g {:transform (str  "translate(0, " translate-y ")")}
       (horizontal-ticks)]
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
