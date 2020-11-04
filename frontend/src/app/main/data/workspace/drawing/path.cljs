;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.path
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.streams :as ms]
   [app.util.geom.path :as path]
   [app.main.data.workspace.drawing.common :as common]))

(def handle-drawing-path
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
            (or (= event ::end-path-drawing)
                (= event :interrupt)
                (and (ms/mouse-event? event)
                     (or (= type :double-click)
                         (= type :context-menu)))
                (and (ms/keyboard-event? event)
                     (= type :down)
                     (= 13 (:key event)))))

          (initialize-drawing [state point]
            (-> state
                (assoc-in [:workspace-drawing :object :segments] [point point])
                (assoc-in [:workspace-drawing :object :initialized?] true)))

          (insert-point-segment [state point]
            (-> state
                (update-in [:workspace-drawing :object :segments] (fnil conj []) point)))

          (update-point-segment [state index point]
            (let [segments (count (get-in state [:workspace-drawing :object :segments]))
                  exists? (< -1 index segments)]
              (cond-> state
                exists? (assoc-in [:workspace-drawing :object :segments index] point))))

          (finish-drawing-path [state]
            (update-in
             state [:workspace-drawing :object]
             (fn [shape] (-> shape
                           (update :segments #(vec (butlast %)))
                           (gsh/update-path-selrect)))))]

    (ptk/reify ::handle-drawing-path
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [flags]} (:workspace-local state)

              last-point (volatile! @ms/mouse-position)

              stoper (->> (rx/filter stoper-event? stream)
                          (rx/share))

              mouse (rx/sample 10 ms/mouse-position)

              points (->> stream
                          (rx/filter ms/mouse-click?)
                          (rx/filter #(false? (:shift %)))
                          (rx/with-latest vector mouse)
                          (rx/map second))

              counter (rx/merge (rx/scan #(inc %) 1 points) (rx/of 1))

              stream' (->> mouse
                          (rx/with-latest vector ms/mouse-position-ctrl)
                          (rx/with-latest vector counter)
                          (rx/map flatten))

              imm-transform #(vector (- % 7) (+ % 7) %)
              immanted-zones (vec (concat
                                   (map imm-transform (range 0 181 15))
                                   (map (comp imm-transform -) (range 0 181 15))))

              align-position (fn [angle pos]
                               (reduce (fn [pos [a1 a2 v]]
                                         (if (< a1 angle a2)
                                           (reduced (gpt/update-angle pos v))
                                           pos))
                                       pos
                                       immanted-zones))]

          (rx/merge
           (rx/of #(initialize-drawing % @last-point))

           (->> points
                (rx/take-until stoper)
                (rx/map (fn [pt] #(insert-point-segment % pt))))

           (rx/concat
            (->> stream'
                 (rx/take-until stoper)
                 (rx/map (fn [[point ctrl? index :as xxx]]
                           (let [point (if ctrl?
                                         (as-> point $
                                           (gpt/subtract $ @last-point)
                                           (align-position (gpt/angle $) $)
                                           (gpt/add $ @last-point))
                                         point)]
                             #(update-point-segment % index point)))))
            (rx/of finish-drawing-path
                   common/handle-finish-drawing))))))))

(defn close-drawing-path []
  (ptk/reify ::close-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-drawing :object :close?] true))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of ::end-path-drawing))))
