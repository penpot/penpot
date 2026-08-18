;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.drawing.common
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.worker :as mw]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- click-draw-box
  [shape width height]
  (-> shape
      (assoc :width width)
      (assoc :height height)
      (assoc :selrect nil)
      (assoc :points nil)
      (cts/setup-shape)
      (gsh/transform-shape (ctm/move-modifiers (- (/ width 2)) (- (/ height 2))))))

(defn- click-draw-path
  [shape]
  (let [start-point (path/get-handler-point (:content shape) 0 nil)
        center-x    (:x start-point)
        center-y    (:y start-point)
        start       (gpt/point (- center-x 50) center-y)
        end         (gpt/point (+ center-x 50) center-y)
        content     (path/points->content [start end])
        selrect     (path/calc-selrect content)]
    (-> shape
        (assoc :content content)
        (assoc :selrect selrect)
        (assoc :points (grc/rect->points selrect))
        (assoc :grow-type :fixed))))

(defn clear-drawing
  ([] (clear-drawing nil))
  ([{:keys [preserve-tool?]}]
   (ptk/reify ::clear-drawing
     ptk/UpdateEvent
     (update [_ state]
       (if preserve-tool?
         (update state :workspace-drawing dissoc :object :lock)
         (dissoc state :workspace-drawing))))))

(defn handle-finish-drawing
  []
  (ptk/reify ::handle-finish-drawing
    ptk/WatchEvent
    (watch [_ state _]
      (let [drawing-state
            (get state :workspace-drawing)

            shape
            (get drawing-state :object)

            tool
            (get drawing-state :tool)

            objects
            (dsh/lookup-page-objects state)

            page-id
            (:current-page-id state)]

        (rx/concat
         (when (:initialized? shape)
           (let [click-draw? (:click-draw? shape)
                 text?       (cfh/text-shape? shape)

                 width       (get drawing-state :width 100)
                 height      (get drawing-state :height 100)

                 shape
                 (cond-> shape
                   (not click-draw?)
                   (assoc :grow-type :fixed)

                   (and ^boolean click-draw? (not ^boolean text?) (not= :path (:type shape)))
                   (click-draw-box width height)

                   (and ^boolean click-draw? (= :path (:type shape)))
                   (click-draw-path)

                   (and click-draw? text?)
                   (-> (assoc :height 17 :width 4 :grow-type :auto-width)
                       (cts/setup-shape))

                   :always
                   (dissoc :initialized? :click-draw?))]

             ;; Add & select the created shape to the workspace
             (rx/concat
              (if (cfh/frame-shape? shape)
                (rx/of (dwu/start-undo-transaction (:id shape)))
                (rx/empty))

              (rx/of (dwsh/add-shape shape {:no-select? (= tool :curve)}))
              (if (cfh/frame-shape? shape)
                (rx/concat
                 (->> (mw/ask! {:cmd :index/query-selection
                                :page-id page-id
                                :rect (:selrect shape)
                                :include-frames? true
                                :full-frame? true
                                :using-selrect? true})
                      (rx/map #(cfh/clean-loops objects %))
                      (rx/map #(dwsh/move-shapes-into-frame (:id shape) %)))
                 (rx/of (dwu/commit-undo-transaction (:id shape))))
                (rx/empty)))))

         ;; Delay so the mouse event can read the drawing state
         (->> (rx/of (clear-drawing {:preserve-tool? (= tool :curve)}))
              (rx/delay 0)))))))
