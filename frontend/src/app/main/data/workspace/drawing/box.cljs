;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.drawing.box
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gslf]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.math :as mth]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [app.util.array :as array]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

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

(defn resize-shape [{:keys [x y width height] :as shape} initial point lock? mod?]
  (if (and (some? x) (some? y) (some? width) (some? height))
    (let [draw-rect  (grc/make-rect initial (cond-> point lock? (adjust-ratio initial)))
          shape-rect (grc/make-rect x y width height)

          scalev     (gpt/point (/ (:width draw-rect)
                                   (:width shape-rect))
                                (/ (:height draw-rect)
                                   (:height shape-rect)))

          movev      (gpt/to-vec (gpt/point shape-rect)
                                 (gpt/point draw-rect))]

      (-> shape
          (assoc :click-draw? false)
          (vary-meta merge {:mod? mod?})
          (gsh/transform-shape (-> (ctm/empty)
                                   (ctm/resize scalev (gpt/point x y))
                                   (ctm/move movev)))))
    shape))

(defn update-drawing [state initial point lock? mod?]
  (update-in state [:workspace-drawing :object] resize-shape initial point lock? mod?))

(defn move-drawing
  [{:keys [x y]}]
  (fn [state]
    (update-in state [:workspace-drawing :object] gsh/absolute-move (gpt/point x y))))

(defn handle-drawing
  [type]
  (ptk/reify ::handle-drawing
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper      (mse/drag-stopper stream)
            layout       (get state :workspace-layout)
            zoom         (dm/get-in state [:workspace-local :zoom] 1)

            snap-pixel?  (contains? layout :snap-pixel-grid)
            initial      (cond-> @ms/mouse-position snap-pixel? (gpt/round-step 1))

            page-id      (:current-page-id state)
            objects      (dsh/lookup-page-objects state page-id)
            focus        (:workspace-focus-selected state)

            fid          (->> (ctst/top-nested-frame objects initial)
                              (ctn/get-first-valid-parent objects) ;; We don't want to change the structure of component copies
                              :id)

            flex-layout? (ctl/flex-layout? objects fid)
            grid-layout? (ctl/grid-layout? objects fid)

            drop-index   (when flex-layout? (gslf/get-drop-index fid objects initial))
            drop-cell    (when grid-layout? (gslg/get-drop-cell fid objects initial))

            shape        (-> (cts/setup-shape {:type type
                                               :x (:x initial)
                                               :y (:y initial)
                                               :frame-id fid
                                               :parent-id fid
                                               :initialized? true
                                               :click-draw? true
                                               :hide-in-viewer (and (= type :frame) (not= fid uuid/zero))})
                             (cond-> (some? drop-index)
                               (with-meta {:index drop-index}))
                             (cond-> (some? drop-cell)
                               (with-meta {:cell drop-cell})))]

        (rx/concat
         ;; Add shape to drawing state
         (rx/of #(update % :workspace-drawing assoc :object shape))
         ;; Initial SNAP
         (->> (rx/concat
               (->> (snap/closest-snap-point page-id [shape] objects layout zoom focus initial)
                    (rx/map move-drawing))

               (->> ms/mouse-position
                    (rx/filter #(> (gpt/distance % initial) (/ 2 zoom)))
                    ;; Take until before the snap calculation otherwise we could cancel the snap in the worker
                    ;; and its a problem for fast moving drawing
                    (rx/take-until stopper)
                    (rx/with-latest-from ms/mouse-position-shift ms/mouse-position-mod)
                    (rx/switch-map
                     (fn [[point :as current]]
                       (->> (snap/closest-snap-point page-id [shape] objects layout zoom focus point)
                            (rx/map (partial array/conj current)))))
                    (rx/map
                     (fn [[_ shift? mod? point]]
                       #(update-drawing % initial (cond-> point snap-pixel? (gpt/round-step 1)) shift? mod?))))))

         (->> (rx/of (common/handle-finish-drawing))
              (rx/delay 100)))))))
