;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.curve
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.shapes :as gsh]
   [app.main.streams :as ms]
   [app.util.geom.path :as path]
   [app.main.data.workspace.drawing.common :as common]))

(def simplify-tolerance 0.3)

(def handle-drawing-curve
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
            (ms/mouse-event? event) (= type :up))

          (initialize-drawing [state]
            (assoc-in state [:workspace-drawing :object :initialized?] true))

          (insert-point-segment [state point]
            (update-in state [:workspace-drawing :object :segments] (fnil conj []) point))

          (finish-drawing-curve [state]
            (update-in
             state [:workspace-drawing :object]
             (fn [shape]
               (-> shape
                   (update :segments #(path/simplify % simplify-tolerance))
                   (gsh/update-path-selrect)))))]

    (ptk/reify ::handle-drawing-curve
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [flags]} (:workspace-local state)
              stoper (rx/filter stoper-event? stream)
              mouse  (rx/sample 10 ms/mouse-position)]
          (rx/concat
           (rx/of initialize-drawing)
           (->> mouse
                (rx/map (fn [pt] #(insert-point-segment % pt)))
                (rx/take-until stoper))
           (rx/of finish-drawing-curve
                  common/handle-finish-drawing)))))))
