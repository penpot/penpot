;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.selection-feedback
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.point :as gpt]
   [app.main.store :as st]))

;; ------------------------------------------------
;; CONSTANTS
;; ------------------------------------------------

(def select-color "#1FDEA7")
(def hover-color "#DB00FF")
(def hover-color-text "#FFF")
(def font-size 10)

(def selection-rect-width 1)

(def select-guide-width 1)
(def select-guide-dasharray 5)
(def hover-guide-width 1)

(def size-display-color "#FFF")
(def size-display-opacity 0.7)
(def size-display-text-color "#000")
(def size-display-width-min 50)
(def size-display-width-max 75)
(def size-display-height 16)

(def distance-color "#DB00FF")
(def distance-text-color "#FFF")
(def distance-border-radius 2)
(def distance-pill-width 40)
(def distance-pill-height 16)
(def distance-line-stroke 1)

;; ------------------------------------------------
;; LENSES
;; ------------------------------------------------

(defn make-selected-shapes-iref
  "Creates a lens to the current selected shapes"
  []
  (let [selected->shapes
        (fn [state]
          (let [selected (get-in state [:viewer-local :selected])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (mapv resolve-shape selected)))]
    #(l/derived selected->shapes st/state)))

(defn make-hover-shapes-iref
  "Creates a lens to the shapes the user is making hover"
  []
  (let [hover->shapes
        (fn [state]
          (let [hover (get-in state [:viewer-local :hover])
                objects (get-in state [:viewer-data :page :objects])]
            (get objects hover)))]
    #(l/derived hover->shapes st/state)))

(def selected-zoom
  (l/derived (l/in [:viewer-local :zoom]) st/state))

;; ------------------------------------------------
;; HELPERS
;; ------------------------------------------------

(defn frame->selrect [frame]
  {:x1 0
   :y1 0
   :x2 (:width frame)
   :y2 (:height frame)
   :width (:width frame)
   :height (:height frame)})

(defn calculate-guides
  "Calculates coordinates for the selection guides"
  [frame selrect]
  (let [{frame-width :width frame-height :height} frame
        {:keys [x y width height]} selrect]
    [[0 y frame-width y]
     [0 (+ y height) frame-width (+ y height)]
     [x 0 x frame-height]
     [(+ x width) 0 (+ x width) frame-height]]))

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
      (conj [ from-s (+ from-s ss) ])

      (or (and (neg? se) (<= ss se)))
      (conj [ from-s (+ from-s se) ])

      (or (and (pos? es) (<= es ee)))
      (conj [ from-e (+ from-e es) ])

      (or (and (pos? ee) (neg? es))
          (and (neg? ee) (pos? ss))
          (and (pos? ee) (< ee es)))
      (conj [ from-e (+ from-e ee) ]))))

;; ------------------------------------------------
;; COMPONENTS
;; ------------------------------------------------

(mf/defc selection-guides [{:keys [frame selrect zoom]}]
  [:g.selection-guides
   (for [[x1 y1 x2 y2] (calculate-guides frame selrect)]
     [:line {:x1 x1
             :y1 y1
             :x2 x2
             :y2 y2
             :style {:stroke select-color
                     :stroke-width (/ select-guide-width zoom)
                     :stroke-dasharray (/ select-guide-dasharray zoom)}}])])

(mf/defc selection-rect [{:keys [type frame selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        stroke-color (case type
                       :selection select-color
                       :hover hover-color)]
    [:g.selection-rect
     [:rect {:x x
             :y y
             :width width
             :height height
             :style {:fill "transparent"
                     :stroke stroke-color
                     :stroke-width (/ selection-rect-width zoom)}}]]))

(mf/defc size-display [{:keys [type selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        size-label (str/fmt "%s x %s" (mth/round width) (mth/round height))

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
                     :font-size (/ font-size zoom)}}
      size-label]]))

(mf/defc distance-display-pill [{:keys [x y zoom distance frame]}]
  (let [distance-pill-width (/ distance-pill-width zoom)
        distance-pill-height (/ distance-pill-height zoom)
        distance-line-stroke (/ distance-line-stroke zoom)
        font-size (/ font-size zoom)
        text-padding (/ 3 zoom)
        distance-border-radius (/ distance-border-radius zoom)

        {frame-width :width frame-height :height} frame

        rect-x (- x (/ distance-pill-width 2))
        rect-y (- y (/ distance-pill-height 2))

        text-x x
        text-y (+ y text-padding)

        offset-x (cond (< rect-x 0) (- rect-x)
                       (> (+ rect-x distance-pill-width) frame-width) (- frame-width (+ rect-x distance-pill-width))
                       :else 0)

        offset-y (cond (< rect-y 0) (- rect-y)
                       (> (+ rect-y distance-pill-height) frame-height) (- frame-height (+ rect-y distance-pill-height))
                       :else 0)

        ]
    [:g.distance-pill
     [:rect {:x (+ rect-x offset-x)
             :y (+ rect-y offset-y)
             :rx distance-border-radius
             :ry distance-border-radius
             :width distance-pill-width
             :height distance-pill-height
             :style {:fill distance-color}}]

     [:text {:x (+ text-x offset-x)
             :y (+ text-y offset-y)
             :rx distance-border-radius
             :ry distance-border-radius
             :text-anchor "middle"
             :width distance-pill-width
             :height distance-pill-height
             :style {:fill distance-text-color
                     :font-size font-size}}
      distance]])
  )

(mf/defc distance-display [{:keys [type from to zoom frame]}]
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

        lines (d/concat [] v-lines h-lines)]

    (for [[x1 y1 x2 y2] lines]
      (let [center-x (+ x1 (/ (- x2 x1) 2))
            center-y (+ y1 (/ (- y2 y1) 2))
            distance (gpt/distance (gpt/point x1 y1) (gpt/point x2 y2))]
        [:g.distance-line {:key (str "line-%s-%s-%s-%s" x1 y1 x2 y2)}
         [:line
          {:x1 x1
           :y1 y1
           :x2 x2
           :y2 y2
           :style {:stroke distance-color
                   :stroke-width distance-line-stroke}}]

         [:& distance-display-pill
          {:x center-x
           :y center-y
           :zoom zoom
           :distance (str (mth/round distance) "px")
           :frame frame}]]))))

(mf/defc selection-feedback [{:keys [frame]}]
  (let [zoom (mf/deref selected-zoom)
        hover-shapes-ref (mf/use-memo (make-hover-shapes-iref))
        hover-shape (mf/deref hover-shapes-ref)

        selected-shapes-ref (mf/use-memo (make-selected-shapes-iref))
        selected-shapes (->> (mf/deref selected-shapes-ref)
                             (map #(gsh/translate-to-frame % frame)))

        selrect (gsh/selection-rect selected-shapes)]

    (when (seq selected-shapes)
      [:g.measurement-feedback {:pointer-events "none"}
       [:g.selected-shapes
        [:& selection-guides {:selrect selrect :frame frame :zoom zoom}]
        [:& selection-rect {:type :selection :selrect selrect :zoom zoom}]
        [:& size-display {:selrect selrect :zoom zoom}]]

       (if (and (not-empty selected-shapes) (not hover-shape))
         [:g.hover-shapes
          [:& distance-display {:from (frame->selrect frame) :to selrect :zoom zoom :frame frame}]]

         (let [hover-selrect (-> hover-shape (gsh/translate-to-frame frame) :selrect)]
           [:g.hover-shapes
            [:& selection-rect {:type :hover :selrect hover-selrect :zoom zoom}]
            [:& size-display {:selrect hover-selrect :zoom zoom}]
            [:& distance-display {:from hover-selrect :to selrect :zoom zoom :frame frame}]]))])))

