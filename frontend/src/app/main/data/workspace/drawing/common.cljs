;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.drawing.common
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn clear-drawing
  []
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-drawing dissoc :tool :object))))

(defn handle-finish-drawing
  []
  (ptk/reify ::handle-finish-drawing
    ptk/WatchEvent
    (watch [_ state _]
      (let [tool (get-in state [:workspace-drawing :tool])
            shape (get-in state [:workspace-drawing :object])
            objects (wsh/lookup-page-objects state)]
        (rx/concat
         (when (:initialized? shape)
           (let [page-id (:current-page-id state)

                 click-draw? (:click-draw? shape)
                 text? (= :text (:type shape))

                 min-side (min 100
                               (mth/floor (get-in state [:workspace-local :vbox :width]))
                               (mth/floor (get-in state [:workspace-local :vbox :height])))

                 shape
                 (cond-> shape
                   (and click-draw? (not text?))
                   (-> (assoc :width min-side :height min-side)
                       (assoc-in [:modifiers :displacement]
                                 (gmt/translate-matrix (- (/ min-side 2)) (- (/ min-side 2)))))

                   (and click-draw? text?)
                   (assoc :height 17 :width 4 :grow-type :auto-width)

                   click-draw?
                   (cp/setup-rect-selrect)

                   :always
                   (-> (gsh/transform-shape)
                       (dissoc :initialized? :click-draw?)))]
             ;; Add & select the created shape to the workspace
             (rx/concat
              (if (= :text (:type shape))
                (rx/of (dwu/start-undo-transaction))
                (rx/empty))

              (rx/of (dwsh/add-shape shape {:no-select? (= tool :curve)}))

              (if (= :frame (:type shape))
                (->> (uw/ask! {:cmd :selection/query
                               :page-id page-id
                               :rect (:selrect shape)
                               :include-frames? true
                               :full-frame? true})
                     (rx/map #(cph/clean-loops objects %))
                     (rx/map #(dwsh/move-shapes-into-frame (:id shape) %)))
                (rx/empty)))))

         ;; Delay so the mouse event can read the drawing state
         (->> (rx/of (clear-drawing))
              (rx/delay 0)))))))

