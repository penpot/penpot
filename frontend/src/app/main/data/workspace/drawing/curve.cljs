;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.drawing.curve
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gslf]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.geom.shapes.path :as gsp]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [app.util.path.simplify-curve :as ups]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def simplify-tolerance 0.3)

(defn- insert-point
  [point]
  (ptk/reify ::insert-point
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-drawing :object]
                 (fn [object]
                   (let [segments (-> (:segments object)
                                      (conj point))
                         content  (gsp/segments->content segments)
                         selrect  (gsh/content->selrect content)
                         points   (grc/rect->points selrect)]
                     (-> object
                         (assoc :segments segments)
                         (assoc :content content)
                         (assoc :selrect selrect)
                         (assoc :points points))))))))

(defn- setup-frame
  []
  (ptk/reify ::setup-frame
    ptk/UpdateEvent
    (update [_ state]
      (let [objects      (dsh/lookup-page-objects state)
            content      (dm/get-in state [:workspace-drawing :object :content] [])
            start        (dm/get-in content [0 :params] nil)
            position     (when start (gpt/point start))
            frame-id     (->> (ctst/top-nested-frame objects position)
                              (ctn/get-first-not-copy-parent objects) ;; We don't want to change the structure of component copies
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

(defn finish-drawing
  []
  (ptk/reify ::finish-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-drawing :object]
                 (fn [{:keys [segments] :as shape}]
                   (let [segments (ups/simplify segments simplify-tolerance)
                         content  (gsp/segments->content segments)
                         selrect  (gsh/content->selrect content)
                         points   (grc/rect->points selrect)]
                     (-> shape
                         (dissoc :segments)
                         (assoc :content content)
                         (assoc :selrect selrect)
                         (assoc :points points)
                         (cond-> (or (empty? points)
                                     (nil? selrect)
                                     (<= (count content) 1))
                           (assoc :initialized? false)))))))))


(defn handle-drawing []
  (ptk/reify ::handle-drawing
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (mse/drag-stopper stream)
            mouse   (rx/sample 10 ms/mouse-position)
            shape   (cts/setup-shape {:type :path
                                      :initialized? true
                                      :frame-id uuid/zero
                                      :parent-id uuid/zero
                                      :segments []})]
        (rx/concat
         (rx/of #(update % :workspace-drawing assoc :object shape))
         (->> mouse
              (rx/map insert-point)
              (rx/take-until stopper))
         (rx/of
          (setup-frame)
          (finish-drawing)
          (common/handle-finish-drawing)))))))

