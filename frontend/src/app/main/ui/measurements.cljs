;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.measurements
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.constants :as mconst]
   [app.main.ui.formats :as fmt]
   [rumext.v2 :as mf]))

;; ------------------------------------------------
;; CONSTANTS
;; ------------------------------------------------

(def ^:private ^:const size-display-color "var(--app-white)")
(def ^:private ^:const size-display-opacity 0.7)
(def ^:private ^:const size-display-text-color "var(--app-black)")
(def ^:private ^:const size-display-width-min 50)
(def ^:private ^:const size-display-width-max 75)
(def ^:private ^:const size-display-height 16)

(def ^:private ^:const distance-border-radius 2)
(def ^:private ^:const distance-pill-width 50)
(def ^:private ^:const distance-pill-height 16)
(def ^:private ^:const distance-line-stroke 1)

(def ^:private ^:const selection-badge-bg-color "var(--color-accent-tertiary)")
(def ^:private ^:const selection-badge-bg-color-component "var(--color-accent-secondary)")
(def ^:private ^:const selection-badge-height 16)
(def ^:private ^:const selection-badge-padding-x 6)
(def ^:private ^:const selection-badge-vertical-gap 8)
(def ^:private ^:const selection-badge-border-radius 2)
(def ^:private ^:const selection-badge-char-width 6.5)

(def ^:private ^:const select-guide-width 1)
(def ^:private ^:const select-guide-dasharray 5)

(def ^:private ^:const hover-color "var(--color-accent-quaternary)")

;; ------------------------------------------------
;; HELPERS
;; ------------------------------------------------

(defn bound->selrect [bounds]
  {:x (:x bounds)
   :y (:y bounds)
   :x1 (:x bounds)
   :y1 (:y bounds)
   :x2 (+ (:x bounds) (:width bounds))
   :y2 (+ (:y bounds) (:height bounds))
   :width (:width bounds)
   :height (:height bounds)})

(defn calculate-guides
  "Calculates coordinates for the selection guides"
  [bounds selrect]
  (let [{bounds-width :width bounds-height :height} bounds
        {:keys [x y width height]} selrect]
    [[(:x bounds) y (+ (:x bounds) bounds-width) y]
     [(:x bounds) (+ y height) (+ (:x bounds) bounds-width) (+ y height)]
     [x (:y bounds) x (+ (:y bounds) bounds-height)]
     [(+ x width) (:y bounds) (+ x width) (+ (:y bounds) bounds-height)]]))

(defn calculate-distance-lines
  "Given a start/end from two shapes gives the distance lines"
  [from-s from-e to-s to-e]
  (let [ss (- to-s from-s)
        se (- to-e from-s)
        es (- to-s from-e)
        ee (- to-e from-e)]
    (cond-> []
      (or (and (neg? ss) (pos? se))
          (and (pos? ss) (neg? ee))
          (and (neg? ss) (> ss se)))
      (conj [from-s (+ from-s ss)])

      (and (neg? se) (<= ss se))
      (conj [from-s (+ from-s se)])

      (and (pos? es) (<= es ee))
      (conj [from-e (+ from-e es)])

      (or (and (pos? ee) (neg? es))
          (and (neg? ee) (pos? ss))
          (and (pos? ee) (< ee es)))
      (conj [from-e (+ from-e ee)]))))

;; ------------------------------------------------
;; COMPONENTS
;; ------------------------------------------------

(mf/defc size-display* [{:keys [selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        size-label (dm/str (fmt/format-number width) " x " (fmt/format-number height))

        rect-height (/ size-display-height zoom)
        rect-width (/ (if (<= (count size-label) 9)
                        size-display-width-min
                        size-display-width-max)
                      zoom)
        text-padding (/ 4 zoom)]
    [:g.size-display
     [:rect {:x (+ x (/ width 2) (- (/ rect-width 2)))
             :y (- (+ y height) rect-height)
             :width rect-width
             :height rect-height
             :style {:fill size-display-color
                     :fill-opacity size-display-opacity}}]

     [:text {:x (+ (+ x (/ width 2) (- (/ rect-width 2))) (/ rect-width 2))
             :y (- (+ y height (+ text-padding (/ rect-height 2))) rect-height)
             :width rect-width
             :height rect-height
             :text-anchor "middle"
             :style {:fill size-display-text-color
                     :font-size (/ mconst/font-size zoom)}}
      size-label]]))

(mf/defc distance-display-pill* [{:keys [x y zoom distance bounds]}]
  (let [distance-pill-width (/ distance-pill-width zoom)
        distance-pill-height (/ distance-pill-height zoom)
        font-size (/ mconst/font-size zoom)
        text-padding (/ 3 zoom)
        distance-border-radius (/ distance-border-radius zoom)

        {bounds-width :width bounds-height :height} bounds

        rect-x (- x (/ distance-pill-width 2))
        rect-y (- y (/ distance-pill-height 2))

        text-x x
        text-y (+ y text-padding)

        offset-x (cond (< rect-x (:x bounds)) (- (:x bounds) rect-x)
                       (> (+ rect-x distance-pill-width) (+ (:x bounds) bounds-width))
                       (- (+ (:x bounds) bounds-width) (+ rect-x distance-pill-width))
                       :else 0)

        offset-y (cond (< rect-y (:y bounds)) (- (:y bounds) rect-y)
                       (> (+ rect-y distance-pill-height) (+ (:y bounds) bounds-height))
                       (- (+ (:y bounds) bounds-height) (+ rect-y distance-pill-height (/ distance-pill-height 2)))
                       :else 0)]
    [:g.distance-pill
     [:rect {:x (+ rect-x offset-x)
             :y (+ rect-y offset-y)
             :rx distance-border-radius
             :ry distance-border-radius
             :width distance-pill-width
             :height distance-pill-height
             :style {:fill mconst/distance-color}}]

     [:text {:x (+ text-x offset-x)
             :y (+ text-y offset-y)
             :rx distance-border-radius
             :ry distance-border-radius
             :text-anchor "middle"
             :width distance-pill-width
             :height distance-pill-height
             :style {:fill mconst/distance-text-color
                     :font-size font-size}}
      (fmt/format-pixels distance)]]))

(mf/defc selection-rect* [{:keys [selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        selection-rect-width (/ mconst/selection-rect-width zoom)]
    [:g.selection-rect
     [:rect {:x x
             :y y
             :width width
             :height height
             :style {:fill "none"
                     :stroke hover-color
                     :stroke-width selection-rect-width}}]]))

(defn- get-edge-for-badge
  "For each rotation range, select the 'most horizontal' edge at the bottom,
   as seen from the user's perspective."
  [rotation]
  (let [rot (mod rotation 360)]
    (cond
      (or (< rot 45) (>= rot 315)) :bottom
      (< rot 135)                  :right
      (< rot 225)                  :top
      :else                        :left)))

(defn- get-edge-points
  "Get the points that define the selected side of a rectangle"
  [points edge]
  (let [[p0 p1 p2 p3] points]
    (case edge
      :bottom [p2 p3]
      :right  [p1 p2]
      :top    [p0 p1]
      :left   [p3 p0])))

(mf/defc selection-size-badge*
  [{:keys [zoom shapes]}]
  (let [badge-height     (/ selection-badge-height zoom)
        badge-padding-x  (/ selection-badge-padding-x zoom)
        badge-gap        (/ selection-badge-vertical-gap zoom)
        badge-radius     (/ selection-badge-border-radius zoom)
        badge-char-width (/ selection-badge-char-width zoom)

        single-shape     (and (= (count shapes) 1) (first shapes))

        component-color? (if single-shape
                           (ctk/instance-head? single-shape)
                           (every? ctk/instance-head? shapes))
        badge-bg-color   (if component-color?
                           selection-badge-bg-color-component
                           selection-badge-bg-color)

        rotation         (when single-shape (dm/get-prop single-shape :rotation))
        has-rotation?    (and rotation (not (mth/almost-zero? rotation)))

        ;; Always compute the selrect from :points via shapes->rect.
        ;; This gives the correct bounding box for all shape types,
        ;; including component instances and shapes with transforms.
        selrect      (gsh/shapes->rect shapes)

        ;; For single shapes we show the original dimensions,
        ;; for multiple shapes show the bounding box
        shape-width  (if single-shape
                       (dm/get-prop single-shape :width)
                       (:width selrect))
        shape-height (if single-shape
                       (dm/get-prop single-shape :height)
                       (:height selrect))

        text (dm/str (fmt/format-number shape-width) " x " (fmt/format-number shape-height))

        text-width   (* (count text) badge-char-width)
        badge-width  (+ text-width (* 2 badge-padding-x))]

    (if has-rotation?
      (let [edge    (get-edge-for-badge rotation)
            points  (dm/get-prop single-shape :points)

            [ep1 ep2]  (get-edge-points points edge)

            mid-point  (gpt/lerp ep1 ep2 0.5)
            normal     (gpt/normal-right (gpt/subtract ep2 ep1))

            rot-offset (case edge
                         :bottom 0
                         :right  270
                         :top    180
                         :left   90)
            badge-rot  (+ rotation rot-offset)
            offset     (+ badge-gap (/ badge-height 2))

            badge-x    (- (/ badge-width 2))
            badge-y    (- (/ badge-height 2))
            badge-cx   (+ (:x mid-point) (* (:x normal) offset))
            badge-cy   (+ (:y mid-point) (* (:y normal) offset))]

        [:g.selection-size-badge {:pointer-events "none"
                                  :transform (dm/str "translate(" badge-cx "," badge-cy ") rotate(" badge-rot ")")}
         [:rect {:x badge-x
                 :y badge-y
                 :width badge-width
                 :height badge-height
                 :rx badge-radius
                 :ry badge-radius
                 :style {:fill badge-bg-color}}]
         [:text {:class (stl/css :badge-text)
                 :x 0
                 :y 0
                 :text-anchor "middle"
                 :dominant-baseline "middle"}
          text]])

      (let [badge-x    (- (/ badge-width 2))
            badge-y    (- (/ badge-height 2))
            badge-cx   (+ (:x selrect) (/ (:width selrect) 2))
            badge-cy   (+ (:y selrect) (:height selrect) badge-gap (/ badge-height 2))]

        [:g.selection-size-badge {:pointer-events "none"
                                  :transform (dm/str "translate(" badge-cx "," badge-cy ")")}
         [:rect {:x badge-x
                 :y badge-y
                 :width badge-width
                 :height badge-height
                 :rx badge-radius
                 :ry badge-radius
                 :style {:fill badge-bg-color}}]
         [:text {:class (stl/css :badge-text)
                 :x 0
                 :y 0
                 :text-anchor "middle"
                 :dominant-baseline "middle"}
          text]]))))

(mf/defc distance-display* [{:keys [from to zoom bounds]}]
  (let [fixed-x (if (gsh/fully-contained? from to)
                  (+ (:x to) (/ (:width to) 2))
                  (+ (:x from) (/ (:width from) 2)))
        fixed-y (if (gsh/fully-contained? from to)
                  (+ (:y to) (/ (:height to) 2))
                  (+ (:y from) (/ (:height from) 2)))

        v-lines (->> (calculate-distance-lines (:y1 from) (:y2 from) (:y1 to) (:y2 to))
                     (map (fn [[start end]] [fixed-x start fixed-x end])))

        h-lines (->> (calculate-distance-lines (:x1 from) (:x2 from) (:x1 to) (:x2 to))
                     (map (fn [[start end]] [start fixed-y end fixed-y])))

        lines   (d/concat-vec v-lines h-lines)

        distance-line-stroke (/ distance-line-stroke zoom)]

    (for [[x1 y1 x2 y2] lines]
      (let [center-x (+ x1 (/ (- x2 x1) 2))
            center-y (+ y1 (/ (- y2 y1) 2))
            distance (gpt/distance (gpt/point x1 y1) (gpt/point x2 y2))]
        (when-not (mth/almost-zero? distance)
          [:g.distance-line {:key (dm/str "line-" x1 "-" y1 "-" x2 "-" y2)}
           [:line
            {:x1 x1
             :y1 y1
             :x2 x2
             :y2 y2
             :style {:stroke mconst/distance-color
                     :stroke-width distance-line-stroke}}]

           [:> distance-display-pill*
            {:x center-x
             :y center-y
             :zoom zoom
             :distance distance
             :bounds bounds}]])))))

(mf/defc selection-guides* [{:keys [bounds selrect zoom]}]
  [:g.selection-guides
   (for [[idx [x1 y1 x2 y2]] (d/enumerate (calculate-guides bounds selrect))]
     [:line {:key (dm/str "guide-" idx)
             :x1 x1
             :y1 y1
             :x2 x2
             :y2 y2
             :style {:stroke mconst/select-color
                     :stroke-width (/ select-guide-width zoom)
                     :stroke-dasharray (/ select-guide-dasharray zoom)}}])])

(mf/defc measurement*
  [{:keys [bounds frame selected-shapes hover-shape zoom]}]
  (let [selected-ids          (into #{} (map :id) selected-shapes)
        selected-selrect      (gsh/shapes->rect selected-shapes)
        hover-selrect         (-> hover-shape :points grc/points->rect)
        bounds-selrect        (bound->selrect bounds)
        hover-selected-shape? (not (contains? selected-ids (:id hover-shape)))]

    (when (seq selected-shapes)
      [:g.measurement-feedback {:pointer-events "none"}
       [:> selection-guides* {:selrect selected-selrect
                              :bounds bounds
                              :zoom zoom}]
       [:> size-display* {:selrect selected-selrect :zoom zoom}]

       (if (or (not hover-shape) (not hover-selected-shape?))
         (when (and frame (not= uuid/zero (:id frame)))
           (let [frame-bb (-> (:points frame) (grc/points->rect))]
             [:g.hover-shapes
              [:> selection-rect* {:type :hover :selrect frame-bb :zoom zoom}]
              [:> distance-display* {:from frame-bb
                                     :to selected-selrect
                                     :zoom zoom
                                     :bounds bounds-selrect}]]))

         [:g.hover-shapes
          [:> selection-rect* {:type :hover :selrect hover-selrect :zoom zoom}]
          [:> size-display* {:selrect hover-selrect :zoom zoom}]
          [:> distance-display* {:from hover-selrect :to selected-selrect :zoom zoom :bounds bounds-selrect}]])])))

