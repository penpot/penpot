;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing
  "Drawing interactions."
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [app.util.geom.path :as path]))

(declare handle-drawing)
(declare handle-drawing-generic)
(declare handle-drawing-path)
(declare handle-drawing-curve)
(declare handle-finish-drawing)
(declare conditional-align)

(defn start-drawing
  [type]
  {:pre [(keyword? type)]}
  (let [id (gensym "drawing")]
    (ptk/reify ::start-drawing
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace-local :drawing-lock] #(if (nil? %) id %)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [lock (get-in state [:workspace-local :drawing-lock])]
          (if (= lock id)
            (rx/merge
             (->> (rx/filter #(= % handle-finish-drawing) stream)
                  (rx/take 1)
                  (rx/map (fn [_] #(update % :workspace-local dissoc :drawing-lock))))
             (rx/of (handle-drawing type)))
            (rx/empty)))))))

(defn handle-drawing
  [type]
  (ptk/reify ::handle-drawing
    ptk/UpdateEvent
    (update [_ state]
      (let [data (cp/make-minimal-shape type)]
        (update-in state [:workspace-local :drawing] merge data)))

    ptk/WatchEvent
    (watch [_ state stream]
      (case type
        :path (rx/of handle-drawing-path)
        :curve (rx/of handle-drawing-curve)
        (rx/of handle-drawing-generic)))))

(def handle-drawing-generic
  (letfn [(resize-shape [{:keys [x y width height] :as shape} point lock? point-snap]
            (let [;; The new shape behaves like a resize on the bottom-right corner
                  initial (gpt/point (+ x width) (+ y height))
                  shapev  (gpt/point width height)
                  deltav  (gpt/to-vec initial point-snap)
                  scalev  (gpt/divide (gpt/add shapev deltav) shapev)
                  scalev  (if lock?
                            (let [v (max (:x scalev) (:y scalev))]
                              (gpt/point v v))
                            scalev)]
              (-> shape
                  (assoc-in [:modifiers :resize-vector] scalev)
                  (assoc-in [:modifiers :resize-origin] (gpt/point x y))
                  (assoc-in [:modifiers :resize-rotation] 0))))

          (update-drawing [state point lock? point-snap]
            (update-in state [:workspace-local :drawing] resize-shape point lock? point-snap))]

    (ptk/reify ::handle-drawing-generic
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [flags]} (:workspace-local state)

              stoper? #(or (ms/mouse-up? %) (= % :interrupt))
              stoper  (rx/filter stoper? stream)
              initial @ms/mouse-position

              page-id (get state :current-page-id)
              objects (get-in state [:workspace-data page-id :objects])
              layout  (get state :workspace-layout)

              frames  (cph/select-frames objects)
              fid     (or (->> frames
                               (filter #(geom/has-point? % initial))
                               first
                               :id)
                          uuid/zero)

              shape (-> state
                        (get-in [:workspace-local :drawing])
                        (geom/setup {:x (:x initial) :y (:y initial) :width 1 :height 1})
                        (assoc :frame-id fid)
                        (assoc ::initialized? true))]
          (rx/concat
           ;; Add shape to drawing state
           (rx/of #(assoc-in state [:workspace-local :drawing] shape))

           ;; Initial SNAP
           (->> (snap/closest-snap-point page-id [shape] layout initial)
                (rx/map (fn [{:keys [x y]}]
                          #(update-in % [:workspace-local :drawing] assoc :x x :y y))))

           (->> ms/mouse-position
                (rx/with-latest vector ms/mouse-position-ctrl)
                (rx/switch-map
                 (fn [[point :as current]]
                   (->> (snap/closest-snap-point page-id [shape] layout point)
                        (rx/map #(conj current %)))))
                (rx/map
                 (fn [[pt ctrl? point-snap]]
                   #(update-drawing % pt ctrl? point-snap)))

                (rx/take-until stoper))
           (rx/of handle-finish-drawing)))))))

(def handle-drawing-path
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
            (or (= event :path/end-path-drawing)
                (= event :interrupt)
                (and (ms/mouse-event? event)
                     (or (= type :double-click)
                         (= type :context-menu)))
                (and (ms/keyboard-event? event)
                     (= type :down)
                     (= 13 (:key event)))))

          (initialize-drawing [state point]
            (-> state
                (assoc-in [:workspace-local :drawing :segments] [point point])
                (assoc-in [:workspace-local :drawing ::initialized?] true)))

          (insert-point-segment [state point]
            (-> state
                (update-in [:workspace-local :drawing :segments] (fnil conj []) point)))

          (update-point-segment [state index point]
            (let [segments (count (get-in state [:workspace-local :drawing :segments]))
                  exists? (< -1 index segments)]
              (cond-> state
                exists? (assoc-in [:workspace-local :drawing :segments index] point))))

          (finish-drawing-path [state]
            (update-in
             state [:workspace-local :drawing]
             (fn [shape] (-> shape
                           (update :segments #(vec (butlast %)))
                           (geom/update-path-selrect)))))]

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
                   handle-finish-drawing))))))))

(def simplify-tolerance 0.3)

(def handle-drawing-curve
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
            (ms/mouse-event? event) (= type :up))

          (initialize-drawing [state]
            (assoc-in state [:workspace-local :drawing ::initialized?] true))

          (insert-point-segment [state point]
            (update-in state [:workspace-local :drawing :segments] (fnil conj []) point))

          (finish-drawing-curve [state]
            (update-in
             state [:workspace-local :drawing]
             (fn [shape]
               (-> shape
                   (update :segments #(path/simplify % simplify-tolerance))
                   (geom/update-path-selrect)))))]

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
                  handle-finish-drawing)))))))

(def handle-finish-drawing
  (ptk/reify ::handle-finish-drawing
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape (get-in state [:workspace-local :drawing])]
        (rx/concat
         (rx/of dw/clear-drawing)
         (when (::initialized? shape)
           (let [shape-min-width (case (:type shape)
                                   :text 20
                                   5)
                 shape-min-height (case (:type shape)
                                    :text 16
                                    5)
                 shape (-> shape
                           geom/transform-shape
                           (dissoc ::initialized?))                 ]
             ;; Add & select the created shape to the workspace
             (rx/of dw/deselect-all
                    (dw/add-shape shape)))))))))

(def close-drawing-path
  (ptk/reify ::close-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :drawing :close?] true))))

