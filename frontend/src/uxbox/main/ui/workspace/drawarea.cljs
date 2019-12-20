;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.drawarea
  "Draw interaction and component."
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes :as shapes]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.math :as mth]
   [uxbox.util.dom :as dom]
   [uxbox.util.data :refer [seek]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.path :as path]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.uuid :as uuid]))

;; --- Events

(declare handle-drawing)
(declare handle-drawing-generic)
(declare handle-drawing-path)
(declare handle-drawing-curve)
(declare handle-finish-drawing)
(declare conditional-align)

(def ^:private minimal-shapes
  [{:type :rect
    :name "Rect"
    :stroke-color "#000000"}
   {:type :image}
   {:type :circle
    :name "Circle"}
   {:type :path
    :name "Path"
    :stroke-style :solid
    :stroke-color "#000000"
    :stroke-width 2
    :fill-color "#000000"
    :fill-opacity 0
    :segments []}
   {:type :canvas
    :name "Canvas"
    :stroke-color "#000000"}
   {:type :curve
    :name "Path"
    :stroke-style :solid
    :stroke-color "#000000"
    :stroke-width 2
    :fill-color "#000000"
    :fill-opacity 0
    :segments []}
   {:type :text
    :name "Text"
    :content "Type your text here"}])

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

(defn- make-minimal-shape
  [type]
  (let [tool (seek #(= type (:type %)) minimal-shapes)]
    (assert tool "unexpected drawing tool")
    (assoc tool :id (uuid/random))))

(defn handle-drawing
  [type]
  (ptk/reify ::handle-drawing
    ptk/UpdateEvent
    (update [_ state]
      (let [data (make-minimal-shape type)]
        (update-in state [:workspace-local :drawing] merge data)))

    ptk/WatchEvent
    (watch [_ state stream]
      (case type
        :path (rx/of handle-drawing-path)
        :curve (rx/of handle-drawing-curve)
        (rx/of handle-drawing-generic)))))

(def handle-drawing-generic
  (letfn [(initialize-drawing [state point]
            (let [shape (get-in state [:workspace-local :drawing])
                  shape (geom/setup shape {:x1 (:x point)
                                           :y1 (:y point)
                                           :x2 (+ (:x point) 2)
                                           :y2 (+ (:y point) 2)})]
              (assoc-in state [:workspace-local :drawing] (assoc shape ::initialized? true))))

          (resize-shape [shape point lock?]
            (let [shape (-> (geom/shape->rect-shape shape)
                            (geom/size))
                  result (geom/resize-shape :bottom-right shape point lock?)
                  scale (geom/calculate-scale-ratio shape result)
                  mtx (geom/generate-resize-matrix :bottom-right shape scale)]
              (assoc shape :modifier-mtx mtx)))

          (update-drawing [state point lock?]
            (update-in state [:workspace-local :drawing] resize-shape point lock?))]

    (ptk/reify ::handle-drawing-generic
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [zoom flags]} (:workspace-local state)
              align? (refs/alignment-activated? flags)

              stoper? #(or (uws/mouse-up? %) (= % :interrupt))
              stoper (rx/filter stoper? stream)

              mouse (->> uws/mouse-position
                         (rx/mapcat #(conditional-align % align?))
                         (rx/map #(gpt/divide % zoom)))]
          (rx/concat
           (->> mouse
                (rx/take 1)
                (rx/map (fn [pt] #(initialize-drawing % pt))))
           (->> mouse
                (rx/with-latest vector uws/mouse-position-ctrl)
                (rx/map (fn [[pt ctrl?]] #(update-drawing % pt ctrl?)))
                (rx/take-until stoper))
           (rx/of handle-finish-drawing)))))))

(def handle-drawing-path
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
             (or (= event :interrupt)
                 (and (uws/mouse-event? event)
                      (or (and (= type :double-click) shift)
                          (= type :context-menu)))
                 (and (uws/keyboard-event? event)
                      (= type :down)
                      (= 13 (:key event)))))

          (initialize-drawing [state point]
            (-> state
                (assoc-in [:workspace-local :drawing :segments] [point point])
                (assoc-in [:workspace-local :drawing ::initialized?] true)))

          (insert-point-segment [state point]
            (update-in state [:workspace-local :drawing :segments] (fnil conj []) point))

          (update-point-segment [state index point]
            (let [segments (count (get-in state [:workspace-local :drawing :segments]))
                  exists? (< -1 index segments)]
              (cond-> state
                exists? (assoc-in [:workspace-local :drawing :segments index] point))))

          (remove-dangling-segmnet [state]
            (update-in state [:workspace-local :drawing :segments] #(vec (butlast %))))]

    (ptk/reify ::handle-drawing-path
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [zoom flags]} (:workspace-local state)

              align? (refs/alignment-activated? flags)
              last-point (volatile! (gpt/divide @uws/mouse-position zoom))

              stoper (->> (rx/filter stoper-event? stream)
                          (rx/share))

              mouse (->> (rx/sample 10 uws/mouse-position)
                         (rx/mapcat #(conditional-align % align?))
                         (rx/map #(gpt/divide % zoom)))

              points (->> stream
                          (rx/filter uws/mouse-click?)
                          (rx/filter #(false? (:shift %)))
                          (rx/with-latest vector mouse)
                          (rx/map second))

              counter (rx/merge (rx/scan #(inc %) 1 points) (rx/of 1))
              stream' (->> mouse
                          (rx/with-latest vector uws/mouse-position-ctrl)
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
                (rx/map (fn [pt]#(insert-point-segment % pt))))

           (rx/concat
            (->> stream'
                 (rx/map (fn [[point ctrl? index :as xxx]]
                           (let [point (if ctrl?
                                         (as-> point $
                                           (gpt/subtract $ @last-point)
                                           (align-position (gpt/angle $) $)
                                           (gpt/add $ @last-point))
                                         point)]
                             #(update-point-segment % index point))))
                 (rx/take-until stoper))
            (rx/of remove-dangling-segmnet
                   handle-finish-drawing))))))))

(def handle-drawing-curve
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
             (or (= event :interrupt)
                 (and (uws/mouse-event? event) (= type :up))))

          (initialize-drawing [state]
            (assoc-in state [:workspace-local :drawing ::initialized?] true))

          (insert-point-segment [state point]
            (update-in state [:workspace-local :drawing :segments] (fnil conj []) point))

          (simplify-drawing-path [state tolerance]
              (update-in state [:workspace-local :drawing :segments] path/simplify tolerance))]

    (ptk/reify ::handle-drawing-curve
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [zoom flags]} (:workspace-local state)
              align? (refs/alignment-activated? flags)
              stoper (rx/filter stoper-event? stream)
              mouse  (->> (rx/sample 10 uws/mouse-position)
                          (rx/mapcat #(conditional-align % align?))
                          (rx/map #(gpt/divide % zoom)))]
          (rx/concat
           (rx/of initialize-drawing)
           (->> mouse
                (rx/map (fn [pt] #(insert-point-segment % pt)))
                (rx/take-until stoper))
           (rx/of #(simplify-drawing-path % 0.3)
                  handle-finish-drawing)))))))

(def handle-finish-drawing
  (ptk/reify ::handle-finish-drawing
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape (get-in state [:workspace-local :drawing])]
        (rx/concat
         (rx/of dw/clear-drawing)
         (when (::initialized? shape)
           (let [modifier-mtx (:modifier-mtx shape)
                 shape (if (gmt/matrix? modifier-mtx)
                         (geom/transform shape modifier-mtx)
                         shape)
                 shape (dissoc shape ::initialized? :modifier-mtx)]
             ;; Add & select the created shape to the workspace
             (rx/of dw/deselect-all
                    (dw/add-shape shape)))))))))

(def close-drawing-path
  (ptk/reify ::close-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :drawing :close?] true))))

;; --- Components

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [zoom shape] :as props}]
  (when (:id shape)
    (case (:type shape)
      (:path :curve) [:& path-draw-area {:shape shape}]
      [:& generic-draw-area {:shape shape
                             :zoom zoom}])))

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x1 y1 width height]} (geom/selection-rect shape)]
    [:g
     [:& shapes/shape-wrapper {:shape shape}]
     [:rect.main {:x x1 :y y1
                  :width width
                  :height height
                  :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#333"
                          :fill "transparent"
                          :stroke-opacity "1"}}]]))

(mf/defc path-draw-area
  [{:keys [shape] :as props}]
  (letfn [(on-click [event]
            (dom/stop-propagation event)
            (st/emit! (dw/set-tooltip nil)
                      close-drawing-path
                      :interrupt))
          (on-mouse-enter [event]
            (st/emit! (dw/set-tooltip "Click to close the path")))
          (on-mouse-leave [event]
            (st/emit! (dw/set-tooltip nil)))]
    (when-let [{:keys [x y] :as segment} (first (:segments shape))]
      [:g
       [:& shapes/shape-wrapper {:shape shape}]
       (when (not= :curve (:type shape))
         [:circle.close-bezier
          {:cx x
           :cy y
           :r 5
           :on-click on-click
           :on-mouse-enter on-mouse-enter
           :on-mouse-leave on-mouse-leave}])])))

(defn- conditional-align [point align?]
  (if align?
    (uwrk/align-point point)
    (rx/of point)))
