;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.common
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.streams :as ms]))

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-drawing dissoc :tool :object))))

(def handle-finish-drawing
  (ptk/reify ::handle-finish-drawing
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape (get-in state [:workspace-drawing :object])]
        (rx/concat
         (rx/of clear-drawing)
         (when (:initialized? shape)
           (let [shape-click-width (case (:type shape)
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
                (rx/of dwc/start-undo-transaction)
                (rx/empty))

              (rx/of (dws/deselect-all)
                     (dwc/add-shape shape))))))))))
