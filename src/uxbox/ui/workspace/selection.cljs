;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.selection
  "Multiple selection handlers component."
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.ui.core :as uuc]
            [uxbox.util.geom :as geom]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def ^:const selected-shapes-l
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (mapv #(get-in state [:shapes-by-id %]) selected)))]
    (-> (l/getter getter)
        (l/focus-atom  st/state))))

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
        [:rect.top
         {:fill "#333"
          :on-mouse-up #(on-mouse-up 5 %)
          :on-mouse-down #(on-mouse-down 5 %)
          :x (- (+ x (/ width 2)) 7)
          :width 14
          :height 3
          :y (- y 5)}]
        [:rect.right
         {:fill "#333"
          :on-mouse-up #(on-mouse-up 6 %)
          :on-mouse-down #(on-mouse-down 6 %)
          :y (- (+ y (/ height 2)) 7)
          :width 3
          :height 14
          :x (+ x width 2)}]
        [:rect.bottom
         {:fill "#333"
          :on-mouse-up #(on-mouse-up 7 %)
          :on-mouse-down #(on-mouse-down 7 %)
          :x (- (+ x (/ width 2)) 7)
          :width 14
          :height 3
          :y (+ y height 2)}]
        [:rect.left
         {:fill "#333"
          :on-mouse-up #(on-mouse-up 8 %)
          :on-mouse-down #(on-mouse-down 8 %)
          :y (- (+ y (/ height 2)) 7)
          :width 3
          :height 14
          :x (- x 5)}]
        [:circle.top-left
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 1 %)
                 :on-mouse-down #(on-mouse-down 1 %)
                 :cx x
                 :cy y})]
        [:circle.top-right
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 2 %)
                 :on-mouse-down #(on-mouse-down 2 %)
                 :cx (+ x width)
                 :cy y})]
        [:circle.bottom-left
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 3 %)
                 :on-mouse-down #(on-mouse-down 3 %)
                 :cx x
                 :cy (+ y height)})]
        [:circle.bottom-right
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 4 %)
                 :on-mouse-down #(on-mouse-down 4 %)
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
    :mixins [rum/reactive mx/static]}))
