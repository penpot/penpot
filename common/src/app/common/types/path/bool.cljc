;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.bool
  (:require
   [app.common.data :as d]
   [app.common.flags :as flags]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.color :as clr]
   [app.common.types.fills :as types.fills]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.segment :as segment]
   [app.common.types.path.subpath :as subpath]))

(defn get-default-fills
  []
  (let [fills [{:fill-color clr/black}]]
    (if (contains? flags/*current* :frontend-binary-fills)
      (types.fills/from-plain fills)
      fills)))

(def group-style-properties
  #{:shadow :blur})

;; FIXME: revisit
(def style-properties
  (into group-style-properties
        [:fills :strokes]))

(defn add-previous
  ([path-data]
   (add-previous path-data nil))
  ([path-data first]
   (->> (d/with-prev path-data)
        (mapv (fn [[cmd prev]]
                (cond-> cmd
                  (and (nil? prev) (some? first))
                  (assoc :prev first)

                  (some? prev)
                  (assoc :prev (helpers/segment->point prev))))))))

(defn close-paths
  "Removes the :close-path commands and replace them for line-to so we can calculate
   the intersections"
  [path-data]

  (loop [segments   (seq path-data)
         result     []
         last-move  nil
         last-point nil]
    (if-let [segment (first segments)]
      (let [point
            (helpers/segment->point segment)

            segment
            (cond
              (and (= :close-path (:command segment))
                   (or (nil? last-point) ;; Ignore consecutive close-paths
                       (< (gpt/distance last-point last-move) 0.01)))
              nil

              (= :close-path (:command segment))
              (helpers/make-line-to last-move)

              :else
              segment)]

        (recur (rest segments)
               (cond-> result (some? segment) (conj segment))
               (if (= :move-to (:command segment))
                 point
                 last-move)
               point))
      result)))

(defn- split-command
  [cmd values]
  (case (:command cmd)
    :line-to  (helpers/split-line-to-ranges (:prev cmd) cmd values)
    :curve-to (helpers/split-curve-to-ranges (:prev cmd) cmd values)
    [cmd]))

(defn- split-ts
  [seg-1 seg-2]
  (let [cmd-1 (get seg-1 :command)
        cmd-2 (get seg-2 :command)]
    (cond
      (and (= :line-to cmd-1)
           (= :line-to cmd-2))
      (helpers/line-line-intersect (helpers/command->line seg-1)
                                   (helpers/command->line seg-2))

      (and (= :line-to cmd-1)
           (= :curve-to cmd-2))
      (helpers/line-curve-intersect (helpers/command->line seg-1)
                                    (helpers/command->bezier seg-2))

      (and (= :curve-to cmd-1)
           (= :line-to cmd-2))
      (let [[seg-2' seg-1']
            (helpers/line-curve-intersect (helpers/command->line seg-2)
                                          (helpers/command->bezier seg-1))]
        ;; Need to reverse because we send the arguments reversed
        [seg-1' seg-2'])

      (and (= :curve-to cmd-1)
           (= :curve-to cmd-2))
      (helpers/curve-curve-intersect (helpers/command->bezier seg-1)
                                     (helpers/command->bezier seg-2))

      :else
      [[] []])))

(defn path-data-intersect-split
  [path-data-a path-data-b sr-a sr-b]

  (let [command->selrect (memoize helpers/command->selrect)]

    (letfn [(overlap-segment-selrect? [segment selrect]
              (if (= :move-to (:command segment))
                false
                (let [r1 (command->selrect segment)]
                  (grc/overlaps-rects? r1 selrect))))

            (overlap-segments? [seg-1 seg-2]
              (if (or (= :move-to (:command seg-1))
                      (= :move-to (:command seg-2)))
                false
                (let [r1 (command->selrect seg-1)
                      r2 (command->selrect seg-2)]
                  (grc/overlaps-rects? r1 r2))))

            (split [seg-1 seg-2]
              (if (not (overlap-segments? seg-1 seg-2))
                [seg-1]
                (let [[ts-seg-1 _] (split-ts seg-1 seg-2)]
                  (-> (split-command seg-1 ts-seg-1)
                      (add-previous (:prev seg-1))))))

            (split-segment-on-path-data [segment path-data path-data-sr]
              (if (overlap-segment-selrect? segment path-data-sr)
                (->> path-data
                     (filter #(overlap-segments? segment %))
                     (reduce
                      (fn [result current]
                        (into [] (mapcat #(split % current)) result))
                      [segment]))
                [segment]))

            (split-path-data [path-data-a path-data-b sr-b]
              (into []
                    (mapcat #(split-segment-on-path-data % path-data-b sr-b))
                    path-data-a))]

      [(split-path-data path-data-a path-data-b sr-b)
       (split-path-data path-data-b path-data-a sr-a)])))

(defn is-segment?
  [cmd]
  (and (contains? cmd :prev)
       (contains? #{:line-to :curve-to} (:command cmd))))

(defn contains-segment?
  [segment path-data path-data-sr path-data-geom]

  (let [point (case (:command segment)
                :line-to  (-> (helpers/command->line segment)
                              (helpers/line-values 0.5))

                :curve-to (-> (helpers/command->bezier segment)
                              (helpers/curve-values 0.5)))]

    (and (grc/contains-point? path-data-sr point)
         (or
          (helpers/is-point-in-geom-data? point path-data-geom)
          (helpers/is-point-in-border? point path-data)))))

(defn inside-segment?
  [segment path-data-sr path-data-geom]
  (let [point (case (:command segment)
                :line-to  (-> (helpers/command->line segment)
                              (helpers/line-values 0.5))

                :curve-to (-> (helpers/command->bezier segment)
                              (helpers/curve-values 0.5)))]

    (and (grc/contains-point? path-data-sr point)
         (helpers/is-point-in-geom-data? point path-data-geom))))

(defn overlap-segment?
  "Finds if the current segment is overlapping against other
   segment meaning they have the same coordinates"
  [segment path-data]

  (let [overlap-single?
        (fn [other]
          (when (and (= (:command segment) (:command other))
                     (contains? #{:line-to :curve-to} (:command segment)))

            (case (:command segment)
              :line-to (let [[p1 q1] (helpers/command->line segment)
                             [p2 q2] (helpers/command->line other)]

                         (when (or (and (< (gpt/distance p1 p2) 0.1)
                                        (< (gpt/distance q1 q2) 0.1))
                                   (and (< (gpt/distance p1 q2) 0.1)
                                        (< (gpt/distance q1 p2) 0.1)))
                           [segment other]))

              :curve-to (let [[p1 q1 h11 h21] (helpers/command->bezier segment)
                              [p2 q2 h12 h22] (helpers/command->bezier other)]

                          (when (or (and (< (gpt/distance p1 p2) 0.1)
                                         (< (gpt/distance q1 q2) 0.1)
                                         (< (gpt/distance h11 h12) 0.1)
                                         (< (gpt/distance h21 h22) 0.1))

                                    (and (< (gpt/distance p1 q2) 0.1)
                                         (< (gpt/distance q1 p2) 0.1)
                                         (< (gpt/distance h11 h22) 0.1)
                                         (< (gpt/distance h21 h12) 0.1)))

                            [segment other])))))]

    (->> path-data
         (d/seek overlap-single?)
         (some?))))

(defn fix-move-to
  [path-data]
  ;; Remove the field `:prev` and makes the necessaries `move-to`
  ;; then clean the subpaths

  (loop [current (first path-data)
         path-data (rest path-data)
         prev nil
         result []]

    (if (nil? current)
      result

      (let [result (if (not= (:prev current) prev)
                     (conj result (helpers/make-move-to (:prev current)))
                     result)]
        (recur (first path-data)
               (rest path-data)
               (helpers/segment->point current)
               (conj result (dissoc current :prev)))))))

(defn remove-duplicated-segments
  "Remove from the path-data segments"
  [path-data]
  (letfn [;; This is a comparator for float points with a precission
          ;; used to remove already existing segments
          (comparator [[fx1 fy1 tx1 ty1 :as v1] [fx2 fy2 tx2 ty2 :as v2]]
            (if (and (mth/close? tx1 tx2)
                     (mth/close? ty1 ty2)
                     (mth/close? fx1 fx2)
                     (mth/close? fy1 fy2))
              0 ;; equal
              (compare v1 v2)))]

    (loop [current (first path-data)
           path-data (rest path-data)
           segments (sorted-set-by comparator)
           result []]

      (if (nil? current)
        result

        (let [fx (-> current :prev :x)
              fy (-> current :prev :y)
              tx (-> current :params :x)
              ty (-> current :params :y)

              result
              (cond-> result
                (and (not (contains? segments [fx fy tx ty]))
                     (not (contains? segments [tx ty fx fy])))
                (conj current))

              segments (conj segments [fx fy tx ty])]

          (recur (first path-data)
                 (rest path-data)
                 segments
                 result))))))

(defn close-path-data
  [path-data]
  (into []
        (mapcat :data)
        (->> path-data
             (subpath/close-subpaths)
             (subpath/get-subpaths))))

(defn- path-data->geom-data
  [path-data]

  (->> path-data
       (close-path-data)
       (filter #(not= (= :line-to (:command %))
                      (= :curve-to (:command %))))
       (mapv (fn [segment]
               {:command (:command segment)
                :segment segment
                :geom (if (= :line-to (:command segment))
                        (helpers/command->line segment)
                        (helpers/command->bezier segment))
                :selrect (helpers/command->selrect segment)}))))

(defn create-union [path-data-a path-data-a-split path-data-b path-data-b-split sr-a sr-b]
  ;; Pick all segments in path-data-a that are not inside path-data-b
  ;; Pick all segments in path-data-b that are not inside path-data-a
  (let [path-data-a-geom (path-data->geom-data path-data-a)
        path-data-b-geom (path-data->geom-data path-data-b)

        result
        (concat
         (->> path-data-a-split (filter #(not (contains-segment? % path-data-b sr-b path-data-b-geom))))
         (->> path-data-b-split (filter #(not (contains-segment? % path-data-a sr-a path-data-a-geom)))))

        result-geom (path-data->geom-data result)

        result-sr (segment/path-data->selrect (fix-move-to result))

        ;; Overlapping segments should be added when they are part of the border
        border-path-data
        (->> path-data-b-split
             (filter #(and (contains-segment? % path-data-a sr-a path-data-a-geom)
                           (overlap-segment? % path-data-a-split)
                           (not (inside-segment? % result-sr result-geom)))))]

    ;; Ensure that the output is always a vector
    (d/concat-vec result border-path-data)))

(defn create-difference [path-data-a path-data-a-split path-data-b path-data-b-split sr-a sr-b]
  ;; Pick all segments in path-data-a that are not inside path-data-b
  ;; Pick all segments in path-data-b that are inside path-data-a
  ;;  removing overlapping
  (let [path-data-a-geom (path-data->geom-data path-data-a)
        path-data-b-geom (path-data->geom-data path-data-b)]
    (d/concat-vec
     (->> path-data-a-split (filter #(not (contains-segment? % path-data-b sr-b path-data-b-geom))))

     ;; Reverse second path-data so we can have holes inside other shapes
     (->> path-data-b-split
          (filter #(and (contains-segment? % path-data-a sr-a path-data-a-geom)
                        (not (overlap-segment? % path-data-a-split))))))))

(defn create-intersection [path-data-a path-data-a-split path-data-b path-data-b-split sr-a sr-b]
  ;; Pick all segments in path-data-a that are inside path-data-b
  ;; Pick all segments in path-data-b that are inside path-data-a
  (let [path-data-a-geom (path-data->geom-data path-data-a)
        path-data-b-geom (path-data->geom-data path-data-b)]
    (d/concat-vec
     (->> path-data-a-split (filter #(contains-segment? % path-data-b sr-b path-data-b-geom)))
     (->> path-data-b-split (filter #(contains-segment? % path-data-a sr-a path-data-a-geom))))))

(defn create-exclusion [path-data-a path-data-b]
  ;; Pick all segments
  (d/concat-vec path-data-a path-data-b))

(defn path-data-bool-pair
  [bool-type path-data-a path-data-b]

  (let [;; We need to reverse the second path when making a difference/intersection/exclude
        ;; and both shapes are in the same direction
        should-reverse?
        (and (not= :union bool-type)
             (= (subpath/clockwise? path-data-b)
                (subpath/clockwise? path-data-a)))

        path-data-a
        (-> path-data-a
            (close-paths)
            (add-previous))

        path-data-b
        (-> path-data-b
            (close-paths)
            (cond-> should-reverse? (subpath/reverse-path-data))
            (add-previous))

        sr-a
        (segment/path-data->selrect path-data-a)

        sr-b
        (segment/path-data->selrect path-data-b)

        ;; Split path-data in new segments in the intersection with the other path
        [path-data-a-split path-data-b-split]
        (path-data-intersect-split path-data-a path-data-b sr-a sr-b)

        path-data-a-split
        (->> path-data-a-split add-previous (filter is-segment?))

        path-data-b-split
        (->> path-data-b-split add-previous (filter is-segment?))

        result
        (case bool-type
          :union        (create-union        path-data-a path-data-a-split path-data-b path-data-b-split sr-a sr-b)
          :difference   (create-difference   path-data-a path-data-a-split path-data-b path-data-b-split sr-a sr-b)
          :intersection (create-intersection path-data-a path-data-a-split path-data-b path-data-b-split sr-a sr-b)
          :exclude      (create-exclusion    path-data-a-split path-data-b-split))]

    (-> result
        remove-duplicated-segments
        fix-move-to
        subpath/close-subpaths)))

(defn calculate-path-data
  "Create a bool path-data from a collection of path-data items and
  specified type. Returns plain segments"
  [bool-type path-data-items]
  ;; We apply the boolean operation in to each pair and the result to the next
  ;; element
  (if (seq path-data-items)
    (->> path-data-items
         (reduce (partial path-data-bool-pair bool-type))
         (vec))
    []))
