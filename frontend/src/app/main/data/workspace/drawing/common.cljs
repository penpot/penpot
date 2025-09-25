;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.drawing.common
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.worker :as mw]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn clear-drawing
  []
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-drawing))))

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

                   (and ^boolean click-draw? (not ^boolean text?))
                   (-> (assoc :width width)
                       (assoc :height height)
                       ;; NOTE: we need to recalculate the selrect and
                       ;; points, so we assign `nil` to it
                       (assoc :selrect nil)
                       (assoc :points nil)
                       (cts/setup-shape)
                       (gsh/transform-shape (ctm/move-modifiers (- (/ width 2)) (- (/ height 2)))))

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
         (->> (rx/of (clear-drawing))
              (rx/delay 0)))))))

