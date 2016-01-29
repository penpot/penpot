(ns uxbox.ui.workspace.canvas.draw
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

(defonce +drawing-shape+ (atom nil))
(defonce +drawing-position+ (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- draw-area-render
  [own]
  (let [shape (rum/react +drawing-shape+)
        [x y] (rum/react +drawing-position+)]
    (when shape
      (-> (sh/-resize shape [x y])
          (sh/-render identity)))))

(def ^:static draw-area
  (mx/component
   {:render draw-area-render
    :name "draw-area"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(define-once :drawing-subscriptions
  (letfn [(init-shape [shape]
            (let [[x y :as mpos] @wb/mouse-position
                  stop @wb/scroll-top
                  y (+ stop y)
                  shape (sh/-initialize shape {:x1 x :y1 y :x2 x :y2 y})]
              (reset! +drawing-shape+ shape)
              (reset! +drawing-position+ [x y])

              (as-> wb/interactions-b $
                (rx/filter #(not= % :shape/movement) $)
                (rx/take 1 $)
                (rx/take-until $ wb/mouse-s)
                (rx/subscribe $ on-value nil on-complete))))

          (on-value [[x y :as pos]]
            (let [stop @wb/scroll-top]
              (reset! +drawing-position+
                      [x (+ y stop)])))

          (on-complete []
            (let [shape @+drawing-shape+
                  shpos @+drawing-position+
                  shape (sh/-resize shape shpos)]
              (rs/emit! (dw/add-shape shape)
                        (dw/select-for-drawing nil))
              (reset! +drawing-position+ nil)
              (reset! +drawing-shape+ nil)))

          (init-icon [shape]
            (let [[x y] @wb/mouse-position
                  stop @wb/scroll-top
                  y (+ stop y)
                  props {:x1 x :y1 y :x2 (+ x 100) :y2 (+ y 100)}
                  shape (sh/-initialize shape props)]
              (rs/emit! (dw/add-shape shape)
                        (dw/select-for-drawing nil))))
          (init []
            (when-let [shape (:drawing @wb/workspace-l)]
              (case (:type shape)
                :builtin/icon (init-icon shape)
                :builtin/rect (init-shape shape)
                :builtin/circle (init-shape shape)
                :builtin/line (init-shape shape))))]

    (as-> wb/interactions-b $
      (rx/dedupe $)
      (rx/filter #(= :draw/shape %) $)
      (rx/on-value $ init))))
