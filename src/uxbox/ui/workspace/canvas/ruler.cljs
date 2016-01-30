(ns uxbox.ui.workspace.canvas.ruler
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.workspace :as dw]
            [uxbox.util.math :as mth]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]))

;; FIXME: clear & refactor (this is a first quick & dirty impl)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- distance
  [[x1 y1] [x2 y2]]
  (let [dx (- x1 x2)
        dy (- y1 y2)]
    (-> (mth/sqrt (+ (mth/pow dx 2) (mth/pow dy 2)))
        (mth/precision 2))))

(defn- get-position
  [own event]
  (let [x (.-clientX event)
        y (.-clientY event)
        overlay (mx/get-ref-dom own "overlay")
        brect (.getBoundingClientRect overlay)]
    [(- x (.-left brect))
     (- y (.-top brect))]))

(defn- on-mouse-down
  [own local event]
  (dom/stop-propagation event)
  (let [pos (get-position own event)]
    (swap! local assoc
           :active true
           :pos1 pos
           :pos2 pos)))

(defn- on-mouse-up
  [own local event]
  (dom/stop-propagation event)
  (swap! local assoc
         :active false
         :pos1 nil
         :pos2 nil))

(defn- on-mouse-move
  [own local event]
  (when (:active @local)
    (let [pos (get-position own event)]
      (swap! local assoc :pos2 pos))))

(defn overlay-render
  [own]
  (let [local (:rum/local own)
        [x1 y1 :as p1] (:pos1 @local)
        [x2 y2 :as p2] (:pos2 @local)
        distance (distance p1 p2)]
    (html
     [:svg {:on-mouse-down #(on-mouse-down own local %)
            :on-mouse-up #(on-mouse-up own local %)
            :on-mouse-move #(on-mouse-move own local %)
            :ref "overlay"}
      [:rect {:style {:fill "transparent" :stroke "transparent" :cursor "cell"}
              :width wb/viewport-width
              :height wb/viewport-height}]
      (if (and (:active @local) x1 x2)
        [:g
         [:line {:x1 x1
                 :y1 y1
                 :x2 x2
                 :y2 y2
                 :style {:cursor "cell"}
                 :stroke-width "2"
                 :stroke "red"}]
         [:text {:x (+ x2 15) :y y2}
          [:tspan (str distance)]]])])))

(defn- ruler-render
  [own]
  (let [flags (rum/react wb/flags-l)]
    (when (contains? flags :workspace/ruler)
      (overlay-render own))))

(def ^:static ruler
  (mx/component
   {:render ruler-render
    :name "ruler"
    :mixins [mx/static rum/reactive (mx/local)]}))
