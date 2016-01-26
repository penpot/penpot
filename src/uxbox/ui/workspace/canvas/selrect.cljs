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
            [uxbox.ui.dom :as dom]))

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
        start-x (min (first start) (first current))
        start-y (min (second start) (second current))
        current-x (max (first start) (first current))
        current-y (max (second start) (second current))
        width (- current-x start-x)
        height (- current-y start-y)]
    {:x start-x
     :y start-y
     :width (- current-x start-x)
     :height (- current-y start-y)}))

(define-once :selrect-subscriptions
  (let [events #{:selrect/draw :nothing}
        ss (as-> wb/interactions-b $
             (rx/filter #(contains? events (:type %)) $)
             (rx/dedupe $)
             (rx/merge (rx/of {:type :nothing}) $)
             (rx/map (fn [event]
                       (case (:type event)
                         :selrect/draw true
                         :nothing false)) $)
             (rx/buffer 2 1 $)
             (rx/share $))]
    (as-> ss $
      (rx/filter #(= (vec %) [false true]) $)
      (rx/with-latest-from vector wb/mouse-s $)
      (rx/on-value $ (fn [[_ [x y :as pos]]]
                       (let [scroll (or @wb/scroll-top 0)
                             pos [x (+ y scroll)]]
                         (swap! selrect-pos assoc
                                :start pos
                                :current pos)))))
    (as-> ss $
      (rx/filter #(= (vec %) [true false]) $)
      (rx/on-value $ (fn []
                       (let [selrect (selrect->rect @selrect-pos)]
                         (rs/emit! (dw/select-shapes selrect))
                         (reset! selrect-pos nil)))))
    (as-> (rx/with-latest-from vector wb/interactions-b wb/mouse-s) $
      (rx/filter #(= (:type (second %)) :selrect/draw) $)
      (rx/map first $)
      (rx/on-value $ (fn [[x y :as pos]]
                       (let [scroll (or @wb/scroll-top 0)
                             pos [x (+ y scroll)]]
                         (swap! selrect-pos assoc :current pos)))))))
