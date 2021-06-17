;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.drawing.common
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-drawing dissoc :tool :object))))

(def handle-finish-drawing
  (ptk/reify ::handle-finish-drawing
    ptk/WatchEvent
    (watch [_ state _]
      (let [shape (get-in state [:workspace-drawing :object])]
        (rx/concat
         (when (:initialized? shape)
           (let [page-id (:current-page-id state)
                 shape-click-width (case (:type shape)
                                     :text 3
                                     20)
                 shape-click-height (case (:type shape)
                                      :text 16
                                      20)
                 shape (if (:click-draw? shape)
                         (-> shape
                             (assoc-in [:modifiers :resize-vector]
                                       (gpt/point shape-click-width shape-click-height))
                             (assoc-in [:modifiers :resize-origin]
                                       (gpt/point (:x shape) (:y shape))))
                         shape)

                 shape (cond-> shape
                         (= (:type shape) :text) (assoc :grow-type
                                                        (if (:click-draw? shape) :auto-width :fixed)))

                 shape (-> shape
                           (gsh/transform-shape)
                           (dissoc :initialized? :click-draw?))]
             ;; Add & select the created shape to the workspace
             (rx/concat
              (if (= :text (:type shape))
                (rx/of (dwu/start-undo-transaction))
                (rx/empty))

              (rx/of (dwc/add-shape shape))

              (if (= :frame (:type shape))
                (->> (uw/ask! {:cmd :selection/query
                               :page-id page-id
                               :rect (:selrect shape)})
                     (rx/map #(dwc/move-shapes-into-frame (:id shape) %)))
                (rx/empty)))))

         ;; Delay so the mouse event can read the drawing state
         (->> (rx/of clear-drawing)
              (rx/delay 0)))))))
