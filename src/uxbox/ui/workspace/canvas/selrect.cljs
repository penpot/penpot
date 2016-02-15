(ns uxbox.ui.workspace.canvas.selrect
  "Components for indicate the user selection and selected shapes group."
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]))

(defonce selrect-pos (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare selrect->rect)

(defn selrect-render
  [own]
  (when-let [data (rum/react selrect-pos)]
    (let [{:keys [x y width height]} (selrect->rect data)]
      (html
       [:rect.selection-rect
        {:x x
         :y y
         :width width
         :height height}]))))

(def ^:static selrect
  (mx/component
   {:render selrect-render
    :name "selrect"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn selrect->rect
  [data]
  (let [start (:start data)
        current (:current data )
        start-x (min (:x start) (:x current))
        start-y (min (:y start) (:y current))
        current-x (max (:x start) (:x current))
        current-y (max (:y start) (:y current))
        width (- current-x start-x)
        height (- current-y start-y)]
    {:x start-x
     :y start-y
     :width (- current-x start-x)
     :height (- current-y start-y)}))

(define-once :selrect-subscriptions
  (letfn [(on-value [pos]
            (let [pos (gpt/add pos @wb/scroll-a)]
              (swap! selrect-pos assoc :current pos)))

          (on-complete []
            (let [selrect (selrect->rect @selrect-pos)]
              (rs/emit! (dw/select-shapes selrect))
              (reset! selrect-pos nil)))

          (init []
            (let [stoper (->> wb/interactions-b
                              (rx/filter #(not= % :draw/selrect))
                              (rx/take 1))
                  pos (gpt/add @wb/mouse-a @wb/scroll-a)]
              (reset! selrect-pos {:start pos :current pos})
              (as-> wb/mouse-s $
                (rx/take-until stoper $)
                (rx/subscribe $ on-value nil on-complete))))]

    (as-> wb/interactions-b $
      (rx/dedupe $)
      (rx/filter #(= :draw/selrect %) $)
      (rx/on-value $ init))))

