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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-position
  [own [x y]]
  (let [overlay (mx/get-ref-dom own "overlay")
        brect (.getBoundingClientRect overlay)]
    [(- x (.-left brect))
     (- y (.-top brect))]))

(defn- get-position
  [own event]
  (let [x (.-clientX event)
        y (.-clientY event)]
    (resolve-position own [x y])))

(defn- on-mouse-down
  [own local event]
  (dom/stop-propagation event)
  (let [pos (get-position own event)]
    (reset! local {:active true :pos1 pos :pos2 pos})))

(defn- on-mouse-up
  [own local event]
  (dom/stop-propagation event)
  (reset! local {:active false}))

(defn- overlay-render
  [own local]
  (let [[x1 y1 :as p1] (:pos1 @local)
        [x2 y2 :as p2] (:pos2 @local)
        distance (mth/distance p1 p2)]
    (html
     [:svg {:on-mouse-down #(on-mouse-down own local %)
            :on-mouse-up #(on-mouse-up own local %)
            :ref "overlay"}
      [:rect {:style {:fill "transparent" :stroke "transparent" :cursor "cell"}
              :width wb/viewport-width
              :height wb/viewport-height}]
      (if (and (:active @local) x1 x2)
          [:g
           [:line {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                   :style {:cursor "cell"}
                   :stroke-width "2"
                   :stroke "red"}]
           [:text {:x (+ x2 15) :y y2}
            [:tspan (str distance)]]])])))

(defn- overlay-will-mount
  [own local]
  (letfn [(on-value [pos]
            (swap! local assoc :pos2 pos))]
    (as-> wb/mouse-absolute-s $
      (rx/dedupe $)
      (rx/filter #(:active @local) $)
      (rx/map #(resolve-position own %) $)
      (rx/on-value $ on-value)
      (assoc own ::sub $))))

(defn- overlay-will-unmount
  [own]
  (let [subscription (::sub own)]
    (subscription)
    (dissoc own ::sub)))

(defn- overlay-transfer-state
  [old-own own]
  (let [sub (::sub old-own)]
    (assoc own ::sub sub)))

(def ^:static overlay
  (mx/component
   {:render #(overlay-render % (:rum/local %))
    :will-mount #(overlay-will-mount % (:rum/local %))
    :will-unmount overlay-will-unmount
    :transfer-state overlay-transfer-state
    :name "overlay"
    :mixins [mx/static (mx/local)]}))

(defn- ruler-render
  [own]
  (let [flags (rum/react wb/flags-l)]
    (when (contains? flags :workspace/ruler)
      (overlay))))

(def ^:static ruler
  (mx/component
   {:render ruler-render
    :name "ruler"
    :mixins [mx/static rum/reactive (mx/local)]}))
