;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.drawing.line
  "Drawing handler for the Line (L) and Arrow (Shift+L) tools.

   Unlike the Path (P) tool, which builds a multi-node path via
   successive clicks, Line/Arrow uses a single press-and-drag
   gesture (matching Figma/Sketch/XD). The result is always a
   two-node path from the drag start to the drag end. The Arrow
   variant additionally pre-configures an arrow stroke cap at
   the end so designers do not have to set it manually."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.flex-layout :as gslf]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.math :as mth]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private snap-step-deg 15)

(defn- snap-to-angle
  "Snaps `point` relative to `origin` to the nearest multiple of
  `snap-step-deg` degrees on a circle of the same radius. Used when
  Shift is held while drawing a line."
  [origin point]
  (let [dx    (- (:x point) (:x origin))
        dy    (- (:y point) (:y origin))
        r     (mth/sqrt (+ (* dx dx) (* dy dy)))
        angle (mth/atan2 dy dx)
        step  (/ (* mth/PI snap-step-deg) 180)
        snapped (* step (mth/round (/ angle step)))]
    (gpt/point (+ (:x origin) (* r (mth/cos snapped)))
               (+ (:y origin) (* r (mth/sin snapped))))))

(defn- update-endpoint
  "Rebuilds the two-node line shape with `start` as the first node
  and `end-point` as the second. Called on every drag frame."
  [start end-point]
  (fn [state]
    (update-in state [:workspace-drawing :object]
               (fn [object]
                 (let [points  [start end-point]
                       content (path/points->content points)
                       selrect (path/calc-selrect content)
                       points' (grc/rect->points selrect)]
                   (-> object
                       (assoc :content content)
                       (assoc :selrect selrect)
                       (assoc :points points')))))))

(defn- setup-frame
  []
  (ptk/reify ::setup-frame
    ptk/UpdateEvent
    (update [_ state]
      (let [objects      (dsh/lookup-page-objects state)
            content      (dm/get-in state [:workspace-drawing :object :content])
            position     (path/get-handler-point content 0 nil)
            frame-id     (->> (ctst/top-nested-frame objects position)
                              (ctn/get-first-valid-parent objects)
                              :id)
            flex-layout? (ctl/flex-layout? objects frame-id)
            grid-layout? (ctl/grid-layout? objects frame-id)
            drop-index   (when flex-layout? (gslf/get-drop-index frame-id objects position))
            drop-cell    (when grid-layout? (gslg/get-drop-cell frame-id objects position))]
        (update-in state [:workspace-drawing :object]
                   (fn [object]
                     (-> object
                         (assoc :frame-id frame-id)
                         (assoc :parent-id frame-id)
                         (cond-> (some? drop-index)
                           (with-meta {:index drop-index}))
                         (cond-> (some? drop-cell)
                           (with-meta {:cell drop-cell})))))))))

(defn- finalize
  "After mouse-up, mark the shape initialized only if it spans more
  than a single point. A zero-length drag is a noop (e.g. the user
  clicked without dragging)."
  []
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-drawing :object]
                 (fn [{:keys [content selrect] :as shape}]
                   (cond-> shape
                     (or (empty? content)
                         (nil? selrect)
                         (<= (count content) 1))
                     (assoc :initialized? false)))))))

(defn- arrow-strokes
  "Default stroke for the Arrow tool: a visible 1px solid line with
  a triangular arrow cap on the end node. The Line tool uses the
  same defaults minus the cap."
  [arrow?]
  (cond-> [{:stroke-color "#000000"
            :stroke-opacity 1
            :stroke-style :solid
            :stroke-alignment :center
            :stroke-width 1}]
    arrow? (update 0 assoc :stroke-cap-end :triangle-arrow)))

(defn handle-drawing
  "Runs the click-and-drag interaction for :line or :arrow. Produces
  a two-node path shape with stroke (and an arrow cap when arrow?)."
  [tool]
  (let [arrow? (= tool :arrow)]
    (ptk/reify ::handle-drawing
      ptk/WatchEvent
      (watch [_ _ stream]
        (let [stopper (mse/drag-stopper stream)
              start   @ms/mouse-position
              shape   (cts/setup-shape {:type :path
                                        :initialized? true
                                        :frame-id uuid/zero
                                        :parent-id uuid/zero
                                        :strokes (arrow-strokes arrow?)
                                        :fills []
                                        :content (path/points->content [start start])})]
          (rx/concat
           (rx/of #(update % :workspace-drawing assoc :object shape))

           (->> ms/mouse-position
                (rx/with-latest-from ms/mouse-position-shift)
                (rx/map (fn [[point shift?]]
                          (if shift?
                            (snap-to-angle start point)
                            point)))
                (rx/map (partial update-endpoint start))
                (rx/take-until stopper))

           (rx/of
            (setup-frame)
            (finalize)
            (common/handle-finish-drawing))))))))
