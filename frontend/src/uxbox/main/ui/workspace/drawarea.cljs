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
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.workspace.shapes :as shapes]
   [uxbox.common.math :as mth]
   [uxbox.util.dom :as dom]
   [uxbox.util.data :refer [seek]]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.common.geom.point :as gpt]
   [uxbox.util.geom.path :as path]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.main.snap :as snap]
   [uxbox.common.uuid :as uuid]))

;; --- Events

(declare handle-drawing)
(declare handle-drawing-generic)
(declare handle-drawing-path)
(declare handle-drawing-curve)
(declare handle-finish-drawing)
(declare conditional-align)

(def ^:private default-color "#b1b2b5") ;; $color-gray-20

(def ^:private minimal-shapes
  [{:type :rect
    :name "Rect"
    :fill-color default-color
    :stroke-alignment :center}
   {:type :image}
   {:type :icon}
   {:type :circle
    :name "Circle"
    :fill-color default-color}
   {:type :path
    :name "Path"
    :stroke-style :solid
    :stroke-color "#000000"
    :stroke-width 2
    :stroke-alignment :center
    :fill-color "#000000"
    :fill-opacity 0
    :segments []}
   {:type :frame
    :stroke-style :none
    :stroke-alignment :center
    :name "Artboard"}
   {:type :curve
    :name "Path"
    :stroke-style :solid
    :stroke-color "#000000"
    :stroke-width 2
    :stroke-alignment :center
    :fill-color "#000000"
    :fill-opacity 0
    :segments []}
   {:type :text
    :name "Text"
    :content nil}])

(defn- make-minimal-shape
  [type]
  (let [tool (seek #(= type (:type %)) minimal-shapes)]
    (assert tool "unexpected drawing tool")
    (assoc tool
           :id (uuid/next)
           :x 0
           :y 0
           :width 1
           :height 1
           :selrect {:x 0
                     :x1 0
                     :x2 0
                     :y 0
                     :y1 0
                     :y2 0
                     :width 1
                     :height 1}
           :points []
           :segments [])))


(defn- calculate-centered-box
  [state aspect-ratio]
  (if (>= aspect-ratio 1)
    (let [vbox (get-in state [:workspace-local :vbox])
          width (/ (:width vbox) 2)
          height (/ width aspect-ratio)

          x (+ (:x vbox) (/ width 2))
          y (+ (:y vbox) (/ (- (:height vbox) height) 2))]

      [width height x y])

    (let [vbox (get-in state [:workspace-local :vbox])
          height (/ (:height vbox) 2)
          width (* height aspect-ratio)

          y (+ (:y vbox) (/ height 2))
          x (+ (:x vbox) (/ (- (:width vbox) width) 2))]

      [width height x y])))

(defn direct-add-shape
  [type data aspect-ratio]
  (ptk/reify ::direct-add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [[width height x y] (calculate-centered-box state aspect-ratio)
            shape (-> (make-minimal-shape type)
                      (merge data)
                      (geom/resize width height)
                      (geom/absolute-move (gpt/point x y)))]

        (rx/of (dw/add-shape shape))))))

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
      (let [data (make-minimal-shape type)]
        (update-in state [:workspace-local :drawing] merge data)))

    ptk/WatchEvent
    (watch [_ state stream]
      (case type
        :path (rx/of handle-drawing-path)
        :curve (rx/of handle-drawing-curve)
        (rx/of handle-drawing-generic)))))

(def handle-drawing-generic
  (letfn [(resize-shape [{:keys [x y] :as shape} point lock? point-snap]
            (let [initial (gpt/point x y)
                  shape' (geom/shape->rect-shape shape)
                  shapev (gpt/point (:width shape') (:height shape'))
                  deltav (gpt/to-vec initial point-snap)
                  scalev (gpt/divide (gpt/add shapev deltav) shapev)
                  scalev (if lock?
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
              stoper (rx/filter stoper? stream)
              initial @ms/mouse-position

              page-id (get state :current-page-id)
              objects (get-in state [:workspace-data page-id :objects])
              layout (get state :workspace-layout)

              frames (->> objects
                          vals
                          (filter (comp #{:frame} :type))
                          (remove #(= (:id %) uuid/zero) ))

              frame-id (or (->> frames
                                (filter #(geom/has-point? % initial))
                                first
                                :id)
                           uuid/zero)

              shape (-> state
                        (get-in [:workspace-local :drawing])
                        (geom/setup {:x (:x initial) :y (:y initial) :width 1 :height 1})
                        (assoc :frame-id frame-id)
                        (assoc ::initialized? true))]
          (rx/concat
           (rx/of #(assoc-in state [:workspace-local :drawing] shape))

           (->> (snap/closest-snap-point page-id [shape] layout initial)
                (rx/map (fn [{:keys [x y]}]
                          #(-> %
                               (assoc-in [:workspace-local :drawing :x] x)
                               (assoc-in [:workspace-local :drawing :y] y)))))

           (->> ms/mouse-position
                (rx/with-latest vector ms/mouse-position-ctrl)
                (rx/switch-map (fn [[point :as current]]
                                 (->> (snap/closest-snap-point page-id [shape] layout point)
                                      (rx/map #(conj current %)))))
                (rx/map (fn [[pt ctrl? point-snap]] #(update-drawing % pt ctrl? point-snap)))
                (rx/take-until stoper))
           (rx/of handle-finish-drawing)))))))

(def handle-drawing-path
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
            (or (= event ::end-path-drawing)
                (and (ms/mouse-event? event)
                     (or (and (= type :double-click) shift)
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
                 (rx/map (fn [[point ctrl? index :as xxx]]
                           (let [point (if ctrl?
                                         (as-> point $
                                           (gpt/subtract $ @last-point)
                                           (align-position (gpt/angle $) $)
                                           (gpt/add $ @last-point))
                                         point)]
                             #(update-point-segment % index point))))
                 (rx/take-until stoper))
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

;; --- Components

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [shape zoom] :as props}]
  (when (:id shape)
    (case (:type shape)
      (:path :curve) [:& path-draw-area {:shape shape}]
      [:& generic-draw-area {:shape shape :zoom zoom}])))

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    (when (and x y)
      [:g
       [:& shapes/shape-wrapper {:shape shape}]
       [:rect.main {:x x :y y
                    :width width
                    :height height
                    :style {:stroke "#1FDEA7"
                            :fill "transparent"
                            :stroke-width (/ 1 zoom)}}]])))

(mf/defc path-draw-area
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)

        on-click
        (fn [event]
          (dom/stop-propagation event)
          (st/emit! (dw/assign-cursor-tooltip nil)
                    close-drawing-path
                    ::end-path-drawing))

        on-mouse-enter
        (fn [event]
          (let [msg (t locale "workspace.viewport.click-to-close-path")]
            (st/emit! (dw/assign-cursor-tooltip msg))))

        on-mouse-leave
        (fn [event]
          (st/emit! (dw/assign-cursor-tooltip nil)))]
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
