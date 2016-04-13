;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.movement
  "Shape movement in workspace logic."
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.constants :as c]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.align :as align]
            [uxbox.data.shapes :as uds]
            [uxbox.util.geom.point :as gpt]))

(declare initialize)
(declare handle-movement)

;; --- Lenses

(declare translate-to-viewport)

(defn- resolve-selected
  [state]
  (let [selected (get-in state [:workspace :selected])
        xf (comp
            (map #(get-in state [:shapes-by-id %]))
            (map translate-to-viewport))]
    (into #{} xf selected)))

(def ^:const ^:private selected-shapes-l
  (-> (l/getter resolve-selected)
      (l/focus-atom st/state)))

;; (def ^:const ^:privae page-options-l
;;   (-> (l/key :options)
;;       (l/focus-atom wb/page-l)))

(def ^:const ^:private alignment-l
  (letfn [(getter [state]
            (let [{:keys [page flags]} (:workspace state)
                  {:keys [options]} (get-in state [:pages-by-id page])]
              (and (contains? flags :alignment/indexed)
                   (contains? flags :grid)
                   (:grid/align options false))))]
    (-> (l/getter getter)
        (l/focus-atom st/state))))

;; --- Public Api

(defn watch-move-actions
  []
  (as-> uuc/actions-s $
    (rx/filter #(= "ui.shape.move" (:type %)) $)
    (rx/on-value $ initialize)))

;; --- Implementation

(def coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(defn- translate-to-viewport
  [shape]
  (let [dx (- (:x2 shape) (:x1 shape))
        dy (- (:y2 shape) (:y1 shape))
        p1 (gpt/point (:x1 shape) (:y1 shape))
        p2 (gpt/add p1 coords)
        p3 (gpt/add p2 [dx dy])]
    (assoc shape
           :x1 (:x p2)
           :y1 (:y p2)
           :x2 (:x p3)
           :y2 (:y p3))))

(defn- translate-to-canvas
  [shape]
  (let [dx (- (:x2 shape) (:x1 shape))
        dy (- (:y2 shape) (:y1 shape))
        p1 (gpt/point (:x1 shape) (:y1 shape))
        p2 (gpt/subtract p1 coords)
        p3 (gpt/add p2 [dx dy])]
    (assoc shape
           :x1 (:x p2)
           :y1 (:y p2)
           :x2 (:x p3)
           :y2 (:y p3))))

(defn- initialize
  []
  (let [shapes @selected-shapes-l
        align? @alignment-l
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter empty?)
                    (rx/take 1))]
    (as-> wb/mouse-delta-s $
      (rx/take-until stoper $)
      (rx/map #(gpt/divide % @wb/zoom-l) $)
      (rx/scan (fn [acc delta]
                 (let [xf (map #(sh/move % delta))]
                   (into [] xf acc))) shapes $)
      (rx/mapcat (fn [items]
                   (if align?
                     (->> (apply rx/of items)
                          (rx/mapcat align/translate)
                          (rx/reduce conj []))
                     (rx/of items))) $)
      (rx/map (fn [items]
                (mapv translate-to-canvas items)) $)

      (rx/subscribe $ handle-movement))))

(defn- handle-movement
  [delta]
  (doseq [shape delta]
    (rs/emit! (uds/update-shape shape))))


