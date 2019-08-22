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
   [uxbox.main.data.shapes :as ds]
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

(defn start-drawing
  [type]
  {:pre [(keyword? type)]}
  (let [id (gensym "drawing")]
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace :drawing-lock] #(if (nil? %) id %)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              lock (get-in state [:workspace :drawing-lock])]
          (if (= lock id)
            (rx/merge
             (->> (rx/filter #(= % handle-finish-drawing) stream)
                  (rx/take 1)
                  (rx/map (fn [_] #(update % :workspace dissoc :drawing-lock))))
             (rx/of (handle-drawing type)))
            (rx/empty)))))))

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

(defn- make-minimal-shape
  [type]
  (let [tool (seek #(= type (:type %)) minimal-shapes)]
    (assert tool "unexpected drawing tool")
    (assoc tool :id (uuid/random))))

(defn handle-drawing
  [type]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])
            data (make-minimal-shape type)]
        (update-in state [:workspace pid :drawing] merge data)))

    ptk/WatchEvent
    (watch [_ state stream]
      (case type
        :path (rx/of handle-drawing-path)
        :curve (rx/of handle-drawing-curve)
        (rx/of handle-drawing-generic)))))

(def handle-drawing-generic
  (letfn [(initialize-drawing [state point]
            (let [pid (get-in state [:workspace :current])
                  shape (get-in state [:workspace pid :drawing])
                  shape (geom/setup shape {:x1 (:x point)
                                           :y1 (:y point)
                                           :x2 (+ (:x point) 2)
                                           :y2 (+ (:y point) 2)})]
              (assoc-in state [:workspace pid :drawing] (assoc shape ::initialized? true))))

          (resize-shape [shape point lock?]
            (let [shape (-> (geom/shape->rect-shape shape)
                            (geom/size))
                  result (geom/resize-shape :bottom-right shape point lock?)
                  scale (geom/calculate-scale-ratio shape result)
                  mtx (geom/generate-resize-matrix :bottom-right shape scale)]
              (assoc shape :modifier-mtx mtx)))

          (update-drawing [state point lock?]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing] resize-shape point lock?)))]

    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              zoom (get-in state [:workspace pid :zoom])
              flags (get-in state [:workspace pid :flags])
              align? (refs/alignment-activated? flags)

              stoper (->> (rx/filter #(or (uws/mouse-up? %) (= % :interrupt)) stream)
                          (rx/take 1))

              mouse (->> uws/mouse-position
                         (rx/mapcat #(conditional-align % align?))
                         (rx/with-latest vector uws/mouse-position-ctrl))]
          (rx/concat
           (->> uws/mouse-position
                (rx/take 1)
                (rx/mapcat #(conditional-align % align?))
                (rx/map (fn [pt] #(initialize-drawing % pt))))
           (->> mouse
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
            (let [pid (get-in state [:workspace :current])]
              (-> state
                  (assoc-in [:workspace pid :drawing :segments] [point point])
                  (assoc-in [:workspace pid :drawing ::initialized?] true))))

          (insert-point-segment [state point]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] (fnil conj []) point)))

          (update-point-segment [state index point]
            (let [pid (get-in state [:workspace :current])
                  segments (count (get-in state [:workspace pid :drawing :segments]))
                  exists? (< -1 index segments)]
              (cond-> state
                exists? (assoc-in [:workspace pid :drawing :segments index] point))))

          (remove-dangling-segmnet [state]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] #(vec (butlast %)))))]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              zoom (get-in state [:workspace pid :zoom])
              flags (get-in state [:workspace pid :flags])
              align? (refs/alignment-activated? flags)

              last-point (volatile! @uws/mouse-position)

              stoper (->> (rx/filter stoper-event? stream)
                          (rx/share))

              mouse (->> (rx/sample 10 uws/mouse-position)
                         (rx/mapcat #(conditional-align % align?)))

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
            (let [pid (get-in state [:workspace :current])]
              (assoc-in state [:workspace pid :drawing ::initialized?] true)))

          (insert-point-segment [state point]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] (fnil conj []) point)))

          (simplify-drawing-path [state tolerance]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] path/simplify tolerance)))]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              zoom (get-in state [:workspace pid :zoom])
              flags (get-in state [:workspace pid :flags])
              align? (refs/alignment-activated? flags)

              stoper (->> (rx/filter stoper-event? stream)
                          (rx/take 1))

              mouse (->> (rx/sample 10 uws/mouse-position)
                         (rx/mapcat #(conditional-align % align?)))]
          (rx/concat
           (rx/of initialize-drawing)
           (->> mouse
                (rx/map (fn [pt] #(insert-point-segment % pt)))
                (rx/take-until stoper))
           (rx/of #(simplify-drawing-path % 0.3)
                  handle-finish-drawing)))))))

(def handle-finish-drawing
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            shape (get-in state [:workspace pid :drawing])]
        (rx/concat
         (rx/of dw/clear-drawing)
         (when (::initialized? shape)
           (let [modifier-mtx (:modifier-mtx shape)
                 shape (if (gmt/matrix? modifier-mtx)
                         (geom/transform shape modifier-mtx)
                         shape)
                 shape (dissoc shape ::initialized? :modifier-mtx)]
             ;; Add & select the cred shape to the workspace
             (rx/of (dw/add-shape shape)
                    (dw/select-first-shape)))))))))

(def close-drawing-path
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (assoc-in state [:workspace pid :drawing :close?] true)))))

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
     (shapes/render-shape shape)
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
       (shapes/render-shape shape)
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
