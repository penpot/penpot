;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.drawing.box
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gslf]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.constants :refer [zoom-half-pixel-precision]]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn adjust-ratio
  [point initial]
  (let [v (gpt/to-vec point initial)
        dx (mth/abs (:x v))
        dy (mth/abs (:y v))
        sx (mth/sign (:x v))
        sy (mth/sign (:y v))]

    (cond-> point
      (> dx dy)
      (assoc :y (- (:y point) (* sy (- dx dy))))

      (> dy dx)
      (assoc :x (- (:x point) (* sx (- dy dx)))))))

(defn resize-shape [{:keys [x y width height] :as shape} initial point lock?]
  (if (and (some? x) (some? y) (some? width) (some? height))
    (let [draw-rect (gsh/make-rect initial (cond-> point lock? (adjust-ratio initial)))
          shape-rect (gsh/make-rect x y width height)

          scalev (gpt/point (/ (:width draw-rect) (:width shape-rect))
                            (/ (:height draw-rect) (:height shape-rect)))

          movev (gpt/to-vec (gpt/point shape-rect) (gpt/point draw-rect))]

      (-> shape
          (assoc :click-draw? false)
          (gsh/transform-shape (-> (ctm/empty)
                                   (ctm/resize scalev (gpt/point x y))
                                   (ctm/move movev)))))
    shape))

(defn update-drawing [state initial point lock?]
  (update-in state [:workspace-drawing :object] resize-shape initial point lock?))

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
            zoom       (get-in state [:workspace-local :zoom] 1)
            snap-pixel? (contains? layout :snap-pixel-grid)

            snap-precision (if (>= zoom zoom-half-pixel-precision) 0.5 1)
            initial (cond-> @ms/mouse-position snap-pixel? (gpt/round-step snap-precision))

            page-id    (:current-page-id state)
            objects    (wsh/lookup-page-objects state page-id)
            focus      (:workspace-focus-selected state)

            fid        (ctst/top-nested-frame objects initial)

            flex-layout? (ctl/flex-layout? objects fid)
            grid-layout? (ctl/grid-layout? objects fid)

            drop-index   (when flex-layout? (gslf/get-drop-index fid objects initial))
            drop-cell    (when grid-layout? (gslg/get-drop-cell fid objects initial))

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

                        (cond-> (some? drop-index)
                          (with-meta {:index drop-index}))

                        (cond-> (some? drop-cell)
                          (with-meta {:cell drop-cell}))

                        (assoc :initialized? true)
                        (assoc :click-draw? true))]
        (rx/concat
         ;; Add shape to drawing state
         (rx/of #(assoc-in state [:workspace-drawing :object] shape))

         ;; Initial SNAP
         (->>
          (rx/concat
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
                   #(update-drawing % initial (cond-> point snap-pixel? (gpt/round-step snap-precision)) shift?)))))
          (rx/take-until stoper))

         (->> (rx/of (common/handle-finish-drawing))
              (rx/delay 100)))))))
