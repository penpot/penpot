;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.selection
  "Multiple selection handlers component."
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.core :as uuc]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]))

;; --- Constants

(def ^:const +circle-props+
  {:r 6
   :style {:fillOpacity "1"
           :strokeWidth "1px"
           :vectorEffect "non-scaling-stroke"}
   :fill "#31e6e0"
   :stroke "#28c4d4"})

;; --- Lenses

(def ^:const selected-shapes-l
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (mapv #(get-in state [:shapes-by-id %]) selected)))]
    (-> (l/lens getter)
        (l/derive  st/state))))

;; --- Selection Handlers (Component)

(defn- multiple-selection-handlers-render
  [shapes]
  (let [{:keys [width height x y]} (geom/outer-rect-coll shapes)]
    (html
     [:g.controls
      [:rect.main {:x x :y y :width width :height height :stroke-dasharray "5,5"
                   :style {:stroke "#333" :fill "transparent" :stroke-opacity "1"}}]])))

(defn- single-selection-handlers-render
  [shape]
  (letfn [
          (on-mouse-down [vid event]
            (dom/stop-propagation event)
            (uuc/acquire-action! "ui.shape.resize"
                                 {:vid vid :shape (:id shape)}))

          (on-mouse-up [vid event]
            (dom/stop-propagation event)
            (uuc/release-action! "ui.shape.resize"))]
    (let [{:keys [x y width height]} (geom/outer-rect shape)]
      (html
       [:g.controls
        [:rect.main {:x x :y y :width width :height height :stroke-dasharray "5,5"
                     :style {:stroke "#333" :fill "transparent" :stroke-opacity "1"}}]
        [:circle.top
         (merge +circle-props+
         {:on-mouse-up #(on-mouse-up :top %)
          :on-mouse-down #(on-mouse-down :top %)
          :cx (+ x (/ width 2))
          :cy (- y 2)})]
        [:circle.right
         (merge +circle-props+
           {:on-mouse-up #(on-mouse-up :right %)
            :on-mouse-down #(on-mouse-down :right %)
            :cy (+ y (/ height 2))
            :cx (+ x width 1)})]
        [:circle.bottom
         (merge +circle-props+
           {:on-mouse-up #(on-mouse-up :bottom %)
            :on-mouse-down #(on-mouse-down :bottom %)
            :cx (+ x (/ width 2))
            :cy (+ y height 2)})]
        [:circle.left
         (merge +circle-props+
           {:on-mouse-up #(on-mouse-up :left %)
            :on-mouse-down #(on-mouse-down :left %)
            :cy (+ y (/ height 2))
            :cx (- x 3)})]
        [:circle.top-left
         (merge +circle-props+
                {:on-mouse-up #(on-mouse-up :top-left %)
                 :on-mouse-down #(on-mouse-down :top-left %)
                 :cx x
                 :cy y})]
        [:circle.top-right
         (merge +circle-props+
                {:on-mouse-up #(on-mouse-up :top-right %)
                 :on-mouse-down #(on-mouse-down :top-right %)
                 :cx (+ x width)
                 :cy y})]
        [:circle.bottom-left
         (merge +circle-props+
                {:on-mouse-up #(on-mouse-up :bottom-left %)
                 :on-mouse-down #(on-mouse-down :bottom-left %)
                 :cx x
                 :cy (+ y height)})]
        [:circle.bottom-right
         (merge +circle-props+
                {:on-mouse-up #(on-mouse-up :bottom-right %)
                 :on-mouse-down #(on-mouse-down :bottom-right %)
                 :cx (+ x width)
                 :cy (+ y height)})]]))))

(defn selection-handlers-render
  [own]
  (let [shapes (rum/react selected-shapes-l)
        shapes-num (count shapes)]
    (cond
      (> shapes-num 1) (multiple-selection-handlers-render shapes)
      (= shapes-num 1) (single-selection-handlers-render (first shapes)))))

(def selection-handlers
  (mx/component
   {:render selection-handlers-render
    :name "selection-handlers"
    :mixins [mx/reactive mx/static]}))
