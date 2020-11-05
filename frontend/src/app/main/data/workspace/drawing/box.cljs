;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.box
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.uuid :as uuid]
   [app.common.pages-helpers :as cph]
   [app.main.data.workspace.common :as dwc]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [app.main.data.workspace.drawing.common :as common]))

(defn resize-shape [{:keys [x y width height] :as shape} point lock? point-snap]
  (let [;; The new shape behaves like a resize on the bottom-right corner
        initial (gpt/point (+ x width) (+ y height))
        shapev  (gpt/point width height)
        deltav  (gpt/to-vec initial point-snap)
        scalev  (gpt/divide (gpt/add shapev deltav) shapev)
        scalev  (if lock?
                  (let [v (max (:x scalev) (:y scalev))]
                    (gpt/point v v))
                  scalev)]
    (-> shape
        (assoc :click-draw? false)
        (assoc-in [:modifiers :resize-vector] scalev)
        (assoc-in [:modifiers :resize-origin] (gpt/point x y))
        (assoc-in [:modifiers :resize-rotation] 0))))

(defn update-drawing [state point lock? point-snap]
  (update-in state [:workspace-drawing :object] resize-shape point lock? point-snap))

(defn handle-drawing-box []
  (ptk/reify ::handle-drawing-box
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [flags]} (:workspace-local state)

            stoper? #(or (ms/mouse-up? %) (= % :interrupt))
            stoper  (rx/filter stoper? stream)
            initial @ms/mouse-position


            page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            layout  (get state :workspace-layout)

            frames  (cph/select-frames objects)
            fid     (or (->> frames
                             (filter #(gsh/has-point? % initial))
                             first
                             :id)
                        uuid/zero)

            shape (-> state
                      (get-in [:workspace-drawing :object])
                      (gsh/setup {:x (:x initial) :y (:y initial) :width 1 :height 1})
                      (assoc :frame-id fid)
                      (assoc :initialized? true)
                      (assoc :click-draw? true))]
        (rx/concat
         ;; Add shape to drawing state
         (rx/of #(assoc-in state [:workspace-drawing :object] shape))

         ;; Initial SNAP
         (->> (snap/closest-snap-point page-id [shape] layout initial)
              (rx/map (fn [{:keys [x y]}]
                        #(update-in % [:workspace-drawing :object] assoc :x x :y y))))

         (->> ms/mouse-position
              (rx/filter #(> (gpt/distance % initial) 2))
              (rx/with-latest vector ms/mouse-position-ctrl)
              (rx/switch-map
               (fn [[point :as current]]
                 (->> (snap/closest-snap-point page-id [shape] layout point)
                      (rx/map #(conj current %)))))
              (rx/map
               (fn [[pt ctrl? point-snap]]
                 #(update-drawing % pt ctrl? point-snap)))

              (rx/take-until stoper))
         (rx/of common/handle-finish-drawing))))))
