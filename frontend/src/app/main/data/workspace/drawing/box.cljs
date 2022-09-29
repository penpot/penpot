;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.drawing.box
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
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
                    (update :x truncate-zero 0.01)
                    (update :y truncate-zero 0.01))
        scalev  (if lock?
                  (let [v (max (:x scalev) (:y scalev))]
                    (gpt/point v v))
                  scalev)]
    (-> shape
        (assoc :click-draw? false)
        (gsh/transform-shape (ctm/resize scalev (gpt/point x y))))))

(defn update-drawing [state point lock?]
  (update-in state [:workspace-drawing :object] resize-shape point lock?))

(defn move-drawing
  [{:keys [x y]}]
  (fn [state]
    (update-in state [:workspace-drawing :object] gsh/absolute-move (gpt/point x y))))

(defn handle-drawing-box []
  (ptk/reify ::handle-drawing-box
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper? #(or (ms/mouse-up? %) (= % :interrupt))
            stoper  (rx/filter stoper? stream)
            layout  (get state :workspace-layout)
            snap-pixel? (contains? layout :snap-pixel-grid)

            initial (cond-> @ms/mouse-position
                      snap-pixel? gpt/round)

            page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            focus   (:workspace-focus-selected state)
            zoom    (get-in state [:workspace-local :zoom] 1)
            fid     (ctst/top-nested-frame objects initial)

            shape   (get-in state [:workspace-drawing :object])
            shape   (-> shape
                        (cts/setup-shape {:x (:x initial)
                                         :y (:y initial)
                                         :width 0.01
                                         :height 0.01})
                        (cond-> (and (cph/frame-shape? shape)
                                     (not= fid uuid/zero))
                          (assoc :fills [] :hide-in-viewer true))

                        (assoc :frame-id fid)
                        (assoc :initialized? true)
                        (assoc :click-draw? true))]
        (rx/concat
         ;; Add shape to drawing state
         (rx/of #(assoc-in state [:workspace-drawing :object] shape))

         ;; Initial SNAP
         (->> (snap/closest-snap-point page-id [shape] objects layout zoom focus initial)
              (rx/map move-drawing))

         (->> ms/mouse-position
              (rx/filter #(> (gpt/distance % initial) (/ 2 zoom)))
              (rx/with-latest vector ms/mouse-position-shift)
              (rx/switch-map
               (fn [[point :as current]]
                 (->> (snap/closest-snap-point page-id [shape] objects layout zoom focus point)
                      (rx/map #(conj current %)))))
              (rx/map
               (fn [[_ shift? point]]
                 #(update-drawing % (cond-> point snap-pixel? gpt/round) shift?)))

              (rx/take-until stoper))
         (rx/of (common/handle-finish-drawing)))))))
