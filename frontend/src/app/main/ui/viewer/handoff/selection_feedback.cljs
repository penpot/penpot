;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.selection-feedback
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.common.geom.shapes :as gsh]
   [app.main.store :as st]))

(def selection-rect-color-normal "#1FDEA7")
(def selection-rect-color-component "#00E0FF")
(def selection-rect-width 1)

(defn make-selected-shapes-iref
  []
  (let [selected->shapes
        (fn [state]
          (let [selected (get-in state [:viewer-local :selected])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (mapv resolve-shape selected)))]
    #(l/derived selected->shapes st/state)))

(defn make-hover-shapes-iref
  []
  (let [hover->shapes
        (fn [state]
          (let [hover (get-in state [:viewer-local :hover])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (mapv resolve-shape hover)))]
    #(l/derived hover->shapes st/state)))

(mf/defc selection-rect [{:keys [shape]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    [:rect {:x x
            :y y
            :width width
            :height height
            :fill "transparent"
            :stroke selection-rect-color-normal
            :stroke-width selection-rect-width
            :pointer-events "none"}]))

(mf/defc selection-feedback [{:keys [frame]}]
  (let [hover-shapes-ref (mf/use-memo (make-hover-shapes-iref))
        hover-shapes (->> (mf/deref hover-shapes-ref)
                          (map #(gsh/translate-to-frame % frame)))
        
        selected-shapes-ref (mf/use-memo (make-selected-shapes-iref))
        selected-shapes (->> (mf/deref selected-shapes-ref)
                             (map #(gsh/translate-to-frame % frame)))]

    [:*
     (for [shape hover-shapes]
       [:& selection-rect {:shape shape}])

     (for [shape selected-shapes]
       [:& selection-rect {:shape shape}])]))
