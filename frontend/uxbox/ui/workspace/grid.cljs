(ns uxbox.ui.workspace.grid
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]
            [uxbox.ui.workspace.base :as wb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static grid-color "#cccccc")

(defn grid-render
  [own zoom]
  (letfn [(vertical-line [position value padding]
            (let [ticks-mod (/ 100 zoom)
                  step-size (/ 10 zoom)]
              (if (< (mod value ticks-mod) step-size)
                (html [:line {:key position
                              :y1 padding
                              :y2 wb/viewport-width
                              :x1 position
                              :x2 position
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.75}])
                (html [:line {:key position
                              :y1 padding
                              :y2 wb/viewport-width
                              :x1 position
                              :x2 position
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.25}]))))
          (horizontal-line [position value padding]
            (let [ticks-mod (/ 100 zoom)
                  step-size (/ 10 zoom)]
              (if (< (mod value ticks-mod) step-size)
                (html [:line {:key position
                              :y1 position
                              :y2 position
                              :x1 padding
                              :x2 wb/viewport-height
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.75}])
                (html [:line {:key position
                              :y1 position
                              :y2 position
                              :x1 padding
                              :x2 wb/viewport-height
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.25}]))))]
    (let [padding (* 20 zoom)
          ticks-mod (/ 100 zoom)
          step-size (/ 10 zoom)
          workspace (rum/react wb/workspace-state)
          enabled? (:grid-enabled workspace false)
          vertical-ticks (range (- padding wb/document-start-y)
                                (- wb/viewport-height wb/document-start-y padding)
                                step-size)
          horizontal-ticks (range (- padding wb/document-start-x)
                                  (- wb/viewport-width wb/document-start-x padding)
                                  step-size)]
      (html
       [:g.grid
        {:style {:display (if enabled? "block" "none")}}
        (for [tick vertical-ticks]
          (let [position (+ tick wb/document-start-x)
                line (vertical-line position tick padding)]
            (rum/with-key line (str "tick-" tick))))
        (for [tick horizontal-ticks]
          (let [position (+ tick wb/document-start-y)
                line (horizontal-line position tick padding)]
            (rum/with-key line (str "tick-" tick))))]))))

(def grid
  (util/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static rum/reactive]}))

