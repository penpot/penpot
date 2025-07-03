;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.bool
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.segment :as segment]
   [app.common.types.path.subpath :as subpath]))

(def default-fills
  [{:fill-color clr/black}])

(def group-style-properties
  #{:shadow :blur})

;; FIXME: revisit
(def style-properties
  (into group-style-properties
        [:fills :strokes]))

(defn add-previous
  ([content]
   (add-previous content nil))
  ([content first]
   (->> (d/with-prev content)
        (mapv (fn [[cmd prev]]
                (cond-> cmd
                  (and (nil? prev) (some? first))
                  (assoc :prev first)

                  (some? prev)
                  (assoc :prev (helpers/segment->point prev))))))))

(defn close-paths
  "Removes the :close-path commands and replace them for line-to so we can calculate
  the intersections"
  [content]

  (loop [segments   (seq content)
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

(defn content-intersect-split
  [content-a content-b sr-a sr-b]

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

            (split-segment-on-content [segment content content-sr]
              (if (overlap-segment-selrect? segment content-sr)
                (->> content
                     (filter #(overlap-segments? segment %))
                     (reduce
                      (fn [result current]
                        (into [] (mapcat #(split % current)) result))
                      [segment]))
                [segment]))

            (split-content [content-a content-b sr-b]
              (into []
                    (mapcat #(split-segment-on-content % content-b sr-b))
                    content-a))]

      [(split-content content-a content-b sr-b)
       (split-content content-b content-a sr-a)])))

(defn is-segment?
  [cmd]
  (and (contains? cmd :prev)
       (contains? #{:line-to :curve-to} (:command cmd))))

(defn contains-segment?
  [segment content content-sr content-geom]

  (let [point (case (:command segment)
                :line-to  (-> (helpers/command->line segment)
                              (helpers/line-values 0.5))

                :curve-to (-> (helpers/command->bezier segment)
                              (helpers/curve-values 0.5)))]

    (and (grc/contains-point? content-sr point)
         (or
          (helpers/is-point-in-geom-data? point content-geom)
          (helpers/is-point-in-border? point content)))))

(defn inside-segment?
  [segment content-sr content-geom]
  (let [point (case (:command segment)
                :line-to  (-> (helpers/command->line segment)
                              (helpers/line-values 0.5))

                :curve-to (-> (helpers/command->bezier segment)
                              (helpers/curve-values 0.5)))]

    (and (grc/contains-point? content-sr point)
         (helpers/is-point-in-geom-data? point content-geom))))

(defn overlap-segment?
  "Finds if the current segment is overlapping against other
  segment meaning they have the same coordinates"
  [segment content]

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

    (->> content
         (d/seek overlap-single?)
         (some?))))

(defn fix-move-to
  [content]
  ;; Remove the field `:prev` and makes the necessaries `move-to`
  ;; then clean the subpaths

  (loop [current (first content)
         content (rest content)
         prev nil
         result []]

    (if (nil? current)
      result

      (let [result (if (not= (:prev current) prev)
                     (conj result (helpers/make-move-to (:prev current)))
                     result)]
        (recur (first content)
               (rest content)
               (helpers/segment->point current)
               (conj result (dissoc current :prev)))))))

(defn remove-duplicated-segments
  "Remove from the content segments"
  [content]
  (letfn [;; This is a comparator for float points with a precission
          ;; used to remove already existing segments
          (comparator [[fx1 fy1 tx1 ty1 :as v1] [fx2 fy2 tx2 ty2 :as v2]]
            (if (and (mth/close? tx1 tx2)
                     (mth/close? ty1 ty2)
                     (mth/close? fx1 fx2)
                     (mth/close? fy1 fy2))
              0 ;; equal
              (compare v1 v2)))]

    (loop [current (first content)
           content (rest content)
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

          (recur (first content)
                 (rest content)
                 segments
                 result))))))

(defn close-content
  [content]
  (into []
        (mapcat :data)
        (->> content
             (subpath/close-subpaths)
             (subpath/get-subpaths))))

(defn- content->geom-data
  [content]

  (->> content
       (close-content)
       (filter #(not= (= :line-to (:command %))
                      (= :curve-to (:command %))))
       (mapv (fn [segment]
               {:command (:command segment)
                :segment segment
                :geom (if (= :line-to (:command segment))
                        (helpers/command->line segment)
                        (helpers/command->bezier segment))
                :selrect (helpers/command->selrect segment)}))))

(defn create-union [content-a content-a-split content-b content-b-split sr-a sr-b]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content-b that are not inside content-a
  (let [content-a-geom (content->geom-data content-a)
        content-b-geom (content->geom-data content-b)

        content
        (concat
         (->> content-a-split (filter #(not (contains-segment? % content-b sr-b content-b-geom))))
         (->> content-b-split (filter #(not (contains-segment? % content-a sr-a content-a-geom)))))

        content-geom (content->geom-data content)

        content-sr (segment/content->selrect (fix-move-to content))

        ;; Overlapping segments should be added when they are part of the border
        border-content
        (->> content-b-split
             (filter #(and (contains-segment? % content-a sr-a content-a-geom)
                           (overlap-segment? % content-a-split)
                           (not (inside-segment? % content-sr content-geom)))))]

    ;; Ensure that the output is always a vector
    (d/concat-vec content border-content)))

(defn create-difference [content-a content-a-split content-b content-b-split sr-a sr-b]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content b that are inside content-a
  ;;  removing overlapping
  (let [content-a-geom (content->geom-data content-a)
        content-b-geom (content->geom-data content-b)]
    (d/concat-vec
     (->> content-a-split (filter #(not (contains-segment? % content-b sr-b content-b-geom))))

     ;; Reverse second content so we can have holes inside other shapes
     (->> content-b-split
          (filter #(and (contains-segment? % content-a sr-a content-a-geom)
                        (not (overlap-segment? % content-a-split))))))))

(defn create-intersection [content-a content-a-split content-b content-b-split sr-a sr-b]
  ;; Pick all segments in content-a that are inside content-b
  ;; Pick all segments in content-b that are inside content-a
  (let [content-a-geom (content->geom-data content-a)
        content-b-geom (content->geom-data content-b)]
    (d/concat-vec
     (->> content-a-split (filter #(contains-segment? % content-b sr-b content-b-geom)))
     (->> content-b-split (filter #(contains-segment? % content-a sr-a content-a-geom))))))

(defn create-exclusion [content-a content-b]
  ;; Pick all segments
  (d/concat-vec content-a content-b))

(defn content-bool-pair
  [bool-type content-a content-b]

  (let [;; We need to reverse the second path when making a difference/intersection/exclude
        ;; and both shapes are in the same direction
        should-reverse?
        (and (not= :union bool-type)
             (= (subpath/clockwise? content-b)
                (subpath/clockwise? content-a)))

        content-a
        (-> content-a
            (close-paths)
            (add-previous))

        content-b
        (-> content-b
            (close-paths)
            (cond-> should-reverse? (subpath/reverse-content))
            (add-previous))

        sr-a
        (segment/content->selrect content-a)

        sr-b
        (segment/content->selrect content-b)

        ;; Split content in new segments in the intersection with the other path
        [content-a-split content-b-split]
        (content-intersect-split content-a content-b sr-a sr-b)

        content-a-split
        (->> content-a-split add-previous (filter is-segment?))

        content-b-split
        (->> content-b-split add-previous (filter is-segment?))

        content
        (case bool-type
          :union        (create-union        content-a content-a-split content-b content-b-split sr-a sr-b)
          :difference   (create-difference   content-a content-a-split content-b content-b-split sr-a sr-b)
          :intersection (create-intersection content-a content-a-split content-b content-b-split sr-a sr-b)
          :exclude      (create-exclusion    content-a-split content-b-split))]

    (-> content
        remove-duplicated-segments
        fix-move-to
        subpath/close-subpaths)))

(defn calculate-content
  "Create a bool content from a collection of contents and specified
  type. Returns plain segments"
  [bool-type contents]
  ;; We apply the boolean operation in to each pair and the result to the next
  ;; element
  (if (seq contents)
    (->> contents
         (reduce (partial content-bool-pair bool-type))
         (vec))
    []))
