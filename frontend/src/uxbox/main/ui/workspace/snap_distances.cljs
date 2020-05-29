(ns uxbox.main.ui.workspace.snap-distances
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [uxbox.common.uuid :as uuid]
   [uxbox.main.refs :as refs]
   [uxbox.main.snap :as snap]
   [uxbox.util.geom.snap-points :as sp]
   [uxbox.common.geom.point :as gpt]

   [cuerdas.core :as str]
   [uxbox.common.pages :as cp]
   [uxbox.common.data :as d]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.common.math :as mth]
   [uxbox.main.worker :as uw]
   [clojure.set :as set]))

(def ^:private line-color "#D383DA")
(def ^:private segment-gap 2)
(def ^:private segment-gap-side 5)

(defn selected->cross-selrec [frame selrect coord]
  (let [areas (gsh/selrect->areas (:selrect frame) selrect)]
    (if (= :x coord)
      [(gsh/pad-selrec (:left areas))
       (gsh/pad-selrec (:right areas))]
      [(gsh/pad-selrec (:top areas))
       (gsh/pad-selrec (:bottom areas))])))

(defn half-point
  "Calculates the middle point of the overlap between two selrects in the opposite axis"
  [coord sr1 sr2]
  (let [c1 (max (get sr1 (if (= :x coord) :y1 :x1))
                (get sr2 (if (= :x coord) :y1 :x1)))
        c2 (min (get sr1 (if (= :x coord) :y2 :x2))
                (get sr2 (if (= :x coord) :y2 :x2)))
        half-point (+ c1 (/ (- c2 c1) 2))]
    half-point))

(def pill-text-width-letter 6)
(def pill-text-width-margin 12)
(def pill-text-font-size 12)
(def pill-text-height 20)
(def pill-text-border-radius 4)

(mf/defc shape-distance-segment
  "Displays a segment between two selrects with the distance between them"
  [{:keys [sr1 sr2 coord zoom]}]
  (let [from-c (min (get sr1 (if (= :x coord) :x2 :y2))
                    (get sr2 (if (= :x coord) :x2 :y2)))
        to-c   (max (get sr1 (if (= :x coord) :x1 :y1))
                    (get sr2 (if (= :x coord) :x1 :y1)))

        distance (mth/round (- to-c from-c))
        half-point (half-point coord sr1 sr2)
        width (-> distance
                  mth/log10 ;; number of digits
                  (* (/ pill-text-width-letter zoom))
                  (+ (/ pill-text-width-margin zoom)))]

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
                :fill "white"
                :text-anchor "middle"}
         (mth/round distance)]])

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

(mf/defc shape-distance [{:keys [frame selrect page-id zoom coord selected]}]
  (let [subject (mf/use-memo #(rx/subject))
        to-measure (mf/use-state [])

        pair->distance+pair
        (fn [[sh1 sh2]]
          [(-> (gsh/distance-shapes sh1 sh2) coord mth/round) [sh1 sh2]])

        contains-selected?
        (fn [selected pairs]
          (let [has-selected?
                (fn [[_ [sh1 sh2]]]
                  (or (selected (:id sh1))
                      (selected (:id sh2))))]
            (some has-selected? pairs)))

        query-worker
        (fn [[selrect selected frame]]
          (let [lt-side (if (= coord :x) :left :top)
                gt-side (if (= coord :x) :right :bottom)
                areas (gsh/selrect->areas (or (:selrect frame) @refs/vbox) selrect)
                query-side (fn [side]
                             (->> (uw/ask! {:cmd :selection/query
                                            :page-id page-id
                                            :rect (gsh/pad-selrec (areas side))})
                                  (rx/map #(set/difference % selected))
                                  (rx/map #(->> % (map (partial get @refs/workspace-objects))))))]

            (->> (query-side lt-side)
                 (rx/combine-latest vector (query-side gt-side)))))
        
        distance-to-selrect
        (fn [shape]
          (let [sr (:selrect shape)]
            (-> (if (<= (coord sr) (coord selrect))
                  (gsh/distance-selrect sr selrect)
                  (gsh/distance-selrect selrect sr))
                coord
                mth/round)))

        get-shapes-match
        (fn [pred? shapes]
          (->> shapes 
               (sort-by coord)
               (d/map-perm vector)
               (filter (fn [[sh1 sh2]] (gsh/overlap-coord? coord sh1 sh2)))
               (map pair->distance+pair)
               (filter (comp pred? first))))

        ;; Left/Top shapes and right/bottom shapes (depends on `coord` parameter
        [lt-shapes gt-shapes] @to-measure

        ;; Gets the distance to the current selection
        lt-distances (->> lt-shapes (map distance-to-selrect) (filter pos?) (into #{}))
        gt-distances (->> gt-shapes (map distance-to-selrect) (filter pos?) (into #{}))

        ;; We'll show the distances that match a distance from the selrect
        show-candidate? (set/union lt-distances gt-distances)

        ;; Checks the distances between elements for distances that match the set of distances
        distance-coincidences (concat (get-shapes-match show-candidate? lt-shapes)
                                      (get-shapes-match show-candidate? gt-shapes))

        ;; Show the distances that either match one of the distances from the selrect
        ;; or are from the selrect and go to a shape on the left and to the right
        show-distance? (into #{} (concat
                                  (map first distance-coincidences)
                                  (set/intersection lt-distances gt-distances)))

        ;; These are the segments whose distance will be displayed

        ;; First segments from segments different that the selectio
        other-shapes-segments (->> distance-coincidences
                                   (map second) ;; Retrieves list of [shape,shape] tuples
                                   (map #(mapv :selrect %))) ;; Changes [shape,shape] to [selrec,selrec]

        ;; Segments from the selection to other
        selection-segments (->> (concat lt-shapes gt-shapes)
                                (filter #(show-distance? (distance-to-selrect %)))
                                (map #(vector selrect (:selrect %))))

        segments-to-display (concat other-shapes-segments selection-segments)]

    (mf/use-effect
     (fn []
       (let [sub (->> subject
                      (rx/switch-map query-worker)
                      (rx/subs #(reset! to-measure %)))]
         ;; On unmount dispose
         #(rx/dispose! sub))))

    (mf/use-effect (mf/deps selrect selected) #(rx/push! subject [selrect selected frame]))

    (for [[sr1 sr2] segments-to-display]
      [:& shape-distance-segment {:key (str/join "-" [(:x sr1) (:y sr1) (:x sr2) (:y sr2)])
                                  :sr1 sr1
                                  :sr2 sr2
                                  :coord coord
                                  :zoom zoom}])))

(mf/defc snap-distances [{:keys [layout]}]
  (let [page-id (mf/deref refs/workspace-page-id)
        selected (mf/deref refs/selected-shapes)
        shapes (->> (refs/objects-by-id selected)
                    (mf/deref)
                    (map gsh/transform-shape))
        selrect (gsh/selection-rect shapes)
        frame-id (-> shapes first :frame-id)
        frame (mf/deref (refs/object-by-id frame-id))
        zoom (mf/deref refs/selected-zoom)
        current-transform (mf/deref refs/current-transform)
        key (->> selected (map str) (str/join "-"))]

    (when (and (contains? layout :dynamic-alignment)
               (= current-transform :move)
               (not (empty? selected)))
        [:g.distance
         (for [coord [:x :y]]
           [:& shape-distance
            {:key (str key (name coord))
             :selrect selrect
             :page-id page-id
             :frame frame
             :zoom zoom
             :coord coord
             :selected selected}])])))
