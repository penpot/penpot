;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.drawing.box
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn truncate-zero [num default]
  (if (mth/almost-zero? num) default num))

(defn resize-shape [{:keys [x y width height] :as shape} point lock?]
  (let [;; The new shape behaves like a resize on the bottom-right corner
        initial (gpt/point (+ x width) (+ y height))
        shapev  (gpt/point width height)
        deltav  (gpt/to-vec initial point)
        scalev  (-> (gpt/divide (gpt/add shapev deltav) shapev)
                    (update :x truncate-zero 1)
                    (update :y truncate-zero 1))
        scalev  (if lock?
                  (let [v (max (:x scalev) (:y scalev))]
                    (gpt/point v v))
                  scalev)]
    (-> shape
        (assoc :click-draw? false)
        (assoc-in [:modifiers :resize-vector] scalev)
        (assoc-in [:modifiers :resize-origin] (gpt/point x y))
        (assoc-in [:modifiers :resize-rotation] 0))))

(defn update-drawing [state point lock?]
  (update-in state [:workspace-drawing :object] resize-shape point lock?))

(defn move-drawing
  [{:keys [x y]}]
  (fn [state]
    (let [x (mth/precision x 0)
          y (mth/precision y 0)]
      (update-in state [:workspace-drawing :object] gsh/absolute-move (gpt/point x y)))))

(defn handle-drawing-box []
  (ptk/reify ::handle-drawing-box
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper? #(or (ms/mouse-up? %) (= % :interrupt))
            stoper  (rx/filter stoper? stream)
            initial @ms/mouse-position

            page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            layout  (get state :workspace-layout)
            zoom    (get-in state [:workspace-local :zoom] 1)

            frames  (cph/get-frames objects)
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
         (->> (snap/closest-snap-point page-id [shape] layout zoom initial)
              (rx/map move-drawing))

         (->> ms/mouse-position
              (rx/filter #(> (gpt/distance % initial) 2))
              (rx/with-latest vector ms/mouse-position-shift)
              (rx/switch-map
               (fn [[point :as current]]
                 (->> (snap/closest-snap-point page-id [shape] layout zoom point)
                      (rx/map #(conj current %)))))
              (rx/map
               (fn [[_ shift? point]]
                 #(update-drawing % point shift?)))

              (rx/take-until stoper))
         (rx/of common/handle-finish-drawing))))))
