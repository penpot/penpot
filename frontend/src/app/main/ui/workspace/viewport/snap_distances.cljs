;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.snap-distances
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.snap :as ams]
   [app.main.ui.formats :as fmt]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private line-color "var(--color-snap)")
(def ^:private segment-gap 2)
(def ^:private segment-gap-side 5)

(defn selected->cross-selrec [frame selrect coord]
  (let [areas (gsh/get-areas (:selrect frame) selrect)]
    (if (= :x coord)
      [(gsh/pad-selrec (:left areas))
       (gsh/pad-selrec (:right areas))]
      [(gsh/pad-selrec (:top areas))
       (gsh/pad-selrec (:bottom areas))])))

(defn half-point
  "Calculates the middle point of the overlap between two selrects in the opposite axis"
  [coord sr1 sr2]
  (let [c1 (mth/max (get sr1 (if (= :x coord) :y1 :x1))
                    (get sr2 (if (= :x coord) :y1 :x1)))
        c2 (mth/min (get sr1 (if (= :x coord) :y2 :x2))
                    (get sr2 (if (= :x coord) :y2 :x2)))]

    (+ c1 (/ (- c2 c1) 2))))

(def pill-text-width-letter 6)
(def pill-text-width-margin 6)
(def pill-text-font-size 12)
(def pill-text-height 20)
(def pill-text-border-radius 4)
(def pill-text-padding 4)

(mf/defc shape-distance-segment
  "Displays a segment between two selrects with the distance between them"
  [{:keys [sr1 sr2 coord zoom]}]
  (let [from-c (mth/min (get sr1 (if (= :x coord) :x2 :y2))
                        (get sr2 (if (= :x coord) :x2 :y2)))
        to-c   (mth/max (get sr1 (if (= :x coord) :x1 :y1))
                        (get sr2 (if (= :x coord) :x1 :y1)))

        distance (- to-c from-c)
        distance-str (fmt/format-number distance)
        half-point (half-point coord sr1 sr2)
        width (-> distance-str
                  count
                  (* (/ pill-text-width-letter zoom))
                  (+ (/ pill-text-width-margin zoom))
                  (+ (* (/ pill-text-width-margin zoom) 2)))]

    [:g.distance-segment
     (let [point [(+ from-c (/ distance 2))
                  (if (= coord :x)
                    (- half-point (/ 10 zoom))
                    (+ half-point (/ 5 zoom)))]
           [x y] (if (= :x coord) point (reverse point))]

       [:*
        [:rect {:x (if (= coord :x) (- x (/ width 2)) x)
                :y (- (- y (/ (/ pill-text-height zoom) 2)) (if (= coord :x) (/ 2 zoom) 0))
                :width width
                :height (/ pill-text-height zoom)
                :rx (/ pill-text-border-radius zoom)
                :fill line-color}]

        [:text {:x (if (= coord :x) x (+ x (/ width 2)))
                :y (- (+ y (/ (/ pill-text-height zoom) 2) (- (/ 6 zoom))) (if (= coord :x) (/ 2 zoom) 0))
                :font-size (/ pill-text-font-size zoom)
                :fill "var(--color-white)"
                :text-anchor "middle"}
         (fmt/format-number distance)]])

     (let [p1 [(+ from-c (/ segment-gap zoom)) (+ half-point (/ segment-gap-side zoom))]
           p2 [(+ from-c (/ segment-gap zoom)) (- half-point (/ segment-gap-side zoom))]
           [x1 y1] (if (= :x coord) p1 (reverse p1))
           [x2 y2] (if (= :x coord) p2 (reverse p2))]
       [:line {:x1 x1 :y1 y1
               :x2 x2 :y2 y2
               :style {:stroke line-color :stroke-width (str (/ 1 zoom))}}])
     (let [p1 [(- to-c (/ segment-gap zoom)) (+ half-point (/ segment-gap-side zoom))]
           p2 [(- to-c (/ segment-gap zoom)) (- half-point (/ segment-gap-side zoom))]
           [x1 y1] (if (= :x coord) p1 (reverse p1))
           [x2 y2] (if (= :x coord) p2 (reverse p2))]
       [:line {:x1 x1 :y1 y1
               :x2 x2 :y2 y2
               :style {:stroke line-color :stroke-width (str (/ 1 zoom))}}])
     (let [p1 [(+ from-c (/ segment-gap zoom)) half-point]
           p2 [(- to-c (/ segment-gap zoom)) half-point]
           [x1 y1] (if (= :x coord) p1 (reverse p1))
           [x2 y2] (if (= :x coord) p2 (reverse p2))]
       [:line {:x1 x1 :y1 y1
               :x2 x2 :y2 y2
               :style {:stroke line-color :stroke-width (str (/ 1 zoom))}}])]))

(defn add-distance [coord sh1 sh2]
  (let [sr1 (:selrect sh1)
        sr2 (:selrect sh2)
        c1 (if (= coord :x) :x1 :y1)
        c2 (if (= coord :x) :x2 :y2)
        dist (- (c1 sr2) (c2 sr1))]
    [dist [sh1 sh2]]))

(defn overlap? [coord sh1 sh2]
  (let [sr1 (:selrect sh1)
        sr2 (:selrect sh2)
        c1 (if (= coord :x) :y1 :x1)
        c2 (if (= coord :x) :y2 :x2)
        s1c1 (c1 sr1)
        s1c2 (c2 sr1)
        s2c1 (c1 sr2)
        s2c2 (c2 sr2)]
    (or (and (>= s2c1 s1c1) (<= s2c1 s1c2))
        (and (>= s2c2 s1c1) (<= s2c2 s1c2))
        (and (>= s1c1 s2c1) (<= s1c1 s2c2))
        (and (>= s1c2 s2c1) (<= s1c2 s2c2)))))

(defn calculate-segments [coord selrect lt-shapes gt-shapes]
  (let [distance-to-selrect
        (fn [shape]
          (let [sr (:selrect shape)]
            (-> (if (<= (coord sr) (coord selrect))
                  (gsh/distance-selrect sr selrect)
                  (gsh/distance-selrect selrect sr))
                coord)))

        get-shapes-match
        (fn [pred? shapes]
          (->> shapes
               (sort-by (comp coord :selrect))
               (d/map-perm #(add-distance coord %1 %2)
                           #(overlap? coord %1 %2))
               (filterv (comp pred? first))))

        ;; Checks if the value is in a set of numbers with an error margin
        check-in-set
        (fn [value number-set]
          (->> number-set
               (some #(<= (mth/abs (- value %)) 1.5))))

        ;; Left/Top shapes and right/bottom shapes (depends on `coord` parameter)

        ;; Gets the distance to the current selection
        distances-xf (comp (map distance-to-selrect) (filter pos?))
        lt-distances (into #{} distances-xf lt-shapes)
        gt-distances (into #{} distances-xf gt-shapes)
        distances (set/union lt-distances gt-distances)

        ;; We'll show the distances that match a distance from the selrect
        show-candidate? #(check-in-set % distances)

        ;; Checks the distances between elements for distances that match the set of distances
        distance-coincidences (d/concat-vec
                               (get-shapes-match show-candidate? lt-shapes)
                               (get-shapes-match show-candidate? gt-shapes))

        ;; Stores the distance candidates to be shown
        distance-candidates (d/concat-set
                             (map first distance-coincidences)
                             (filter #(check-in-set % lt-distances) gt-distances)
                             (filter #(check-in-set % gt-distances) lt-distances))

        ;; Of these candidates we keep only the smaller to be displayed
        min-distance (apply min distance-candidates)

        ;; Show the distances that either match one of the distances from the selrect
        ;; or are from the selrect and go to a shape on the left and to the right
        show-distance? #(check-in-set % #{min-distance})

        ;; These are the segments whose distance will be displayed

        ;; First segments from segments different that the selection
        other-shapes-segments (->> distance-coincidences
                                   (filter #(show-distance? (first %)))
                                   (map second) ;; Retrieves list of [shape,shape] tuples
                                   (map #(mapv :selrect %))) ;; Changes [shape,shape] to [selrec,selrec]


        ;; Segments from the selection to the other shapes
        selection-segments (->> (concat lt-shapes gt-shapes)
                                (filter #(show-distance? (distance-to-selrect %)))
                                (map #(vector selrect (:selrect %))))

        segments-to-display (d/concat-set other-shapes-segments selection-segments)]

    segments-to-display))

(mf/defc shape-distance
  {::mf/wrap-props false}
  [props]
  (let [frame      (unchecked-get props "frame")
        selrect    (unchecked-get props "selrect")
        page-id    (unchecked-get props "page-id")
        zoom       (unchecked-get props "zoom")
        coord      (unchecked-get props "coord")
        selected   (unchecked-get props "selected")

        subject    (mf/use-memo #(rx/subject))
        to-measure (mf/use-state [])

        query-worker
        (fn [[selrect selected frame]]
          (let [lt-side (if (= coord :x) :left :top)
                gt-side (if (= coord :x) :right :bottom)

                vbox  (deref refs/vbox)
                areas (gsh/get-areas
                       (or (grc/clip-rect (dm/get-prop frame :selrect) vbox) vbox)
                       selrect)

                query-side (fn [side]
                             (let [rect (get areas side)]
                               (if (and (> (:width rect) 0) (> (:height rect) 0))
                                 (ams/select-shapes-area page-id (:id frame) selected @refs/workspace-page-objects rect)
                                 (rx/of nil))))]
            (rx/combine-latest (query-side lt-side)
                               (query-side gt-side))))

        [lt-shapes gt-shapes] @to-measure

        segments-to-display (mf/use-memo
                             (mf/deps @to-measure)
                             #(calculate-segments coord selrect lt-shapes gt-shapes))]

    (mf/use-effect
     (fn []
       (let [sub (->> subject
                      (rx/throttle 100)
                      (rx/switch-map query-worker)
                      (rx/subs #(reset! to-measure %)))]
         ;; On unmount dispose
         #(rx/dispose! sub))))

    (mf/use-effect
     (mf/deps selrect)
     #(rx/push! subject [selrect selected frame]))

    (for [[sr1 sr2] segments-to-display]
      [:& shape-distance-segment {:key (str/join "-" [(:x sr1) (:y sr1) (:x sr2) (:y sr2)])
                                  :sr1 sr1
                                  :sr2 sr2
                                  :coord coord
                                  :zoom zoom}])))

(mf/defc snap-distances
  {::mf/wrap-props false}
  [props]
  (let [page-id         (unchecked-get props "page-id")
        zoom            (unchecked-get props "zoom")
        selected        (unchecked-get props "selected")
        selected-shapes (unchecked-get props "selected-shapes")
        frame-id        (-> selected-shapes first :frame-id)
        frame           (mf/deref (refs/object-by-id frame-id))
        selrect         (gsh/shapes->rect selected-shapes)]

    (when-not (ctl/any-layout? frame)
      [:g.distance
       [:& shape-distance
        {:selrect selrect
         :page-id page-id
         :frame frame
         :zoom zoom
         :coord :x
         :selected selected}]
       [:& shape-distance
        {:selrect selrect
         :page-id page-id
         :frame frame
         :zoom zoom
         :coord :y
         :selected selected}]])))
