;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg.path.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.path :as gsp]
   [app.common.math :as mth]
   [app.common.svg.path.command :as upc]
   [app.common.svg.path.subpath :as ups]))

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
                  (assoc :prev (gsp/command->point prev))))))))

(defn close-paths
  "Removes the :close-path commands and replace them for line-to so we can calculate
  the intersections"
  [content]

  (loop [head (first content)
         content (rest content)
         result []
         last-move nil
         last-p nil]

    (if (nil? head)
      result
      (let [head-p (gsp/command->point head)
            head (cond
                   (and (= :close-path (:command head))
                        (or (nil? last-p) ;; Ignore consecutive close-paths
                            (< (gpt/distance last-p last-move) 0.01)))
                   nil

                   (= :close-path (:command head))
                   (upc/make-line-to last-move)

                   :else
                   head)]

        (recur (first content)
               (rest content)
               (cond-> result (some? head) (conj head))
               (if (= :move-to (:command head))
                 head-p
                 last-move)
               head-p)))))

(defn- split-command
  [cmd values]
  (case (:command cmd)
    :line-to  (gsp/split-line-to-ranges (:prev cmd) cmd values)
    :curve-to (gsp/split-curve-to-ranges (:prev cmd) cmd values)
    [cmd]))

(defn split-ts [seg-1 seg-2]
  (cond
    (and (= :line-to (:command seg-1))
         (= :line-to (:command seg-2)))
    (gsp/line-line-intersect (gsp/command->line seg-1) (gsp/command->line seg-2))

    (and (= :line-to (:command seg-1))
         (= :curve-to (:command seg-2)))
    (gsp/line-curve-intersect (gsp/command->line seg-1) (gsp/command->bezier seg-2))

    (and (= :curve-to (:command seg-1))
         (= :line-to (:command seg-2)))
    (let [[seg-2' seg-1']
          (gsp/line-curve-intersect (gsp/command->line seg-2) (gsp/command->bezier seg-1))]
      ;; Need to reverse because we send the arguments reversed
      [seg-1' seg-2'])

    (and (= :curve-to (:command seg-1))
         (= :curve-to (:command seg-2)))
    (gsp/curve-curve-intersect (gsp/command->bezier seg-1) (gsp/command->bezier seg-2))

    :else
    [[] []]))

(defn content-intersect-split
  [content-a content-b sr-a sr-b]

  (let [command->selrect (memoize gsp/command->selrect)]

    (letfn [(overlap-segment-selrect?
              [segment selrect]
              (if (= :move-to (:command segment))
                false
                (let [r1 (command->selrect segment)]
                  (grc/overlaps-rects? r1 selrect))))

            (overlap-segments?
              [seg-1 seg-2]
              (if (or (= :move-to (:command seg-1))
                      (= :move-to (:command seg-2)))
                false
                (let [r1 (command->selrect seg-1)
                      r2 (command->selrect seg-2)]
                  (grc/overlaps-rects? r1 r2))))

            (split
              [seg-1 seg-2]
              (if (not (overlap-segments? seg-1 seg-2))
                [seg-1]
                (let [[ts-seg-1 _] (split-ts seg-1 seg-2)]
                  (-> (split-command seg-1 ts-seg-1)
                      (add-previous (:prev seg-1))))))

            (split-segment-on-content
              [segment content content-sr]

              (if (overlap-segment-selrect? segment content-sr)
                (->> content
                     (filter #(overlap-segments? segment %))
                     (reduce
                      (fn [result current]
                        (into [] (mapcat #(split % current)) result))
                      [segment]))
                [segment]))

            (split-content
              [content-a content-b sr-b]
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
                :line-to  (-> (gsp/command->line segment)
                              (gsp/line-values 0.5))

                :curve-to (-> (gsp/command->bezier segment)
                              (gsp/curve-values 0.5)))]

    (and (grc/contains-point? content-sr point)
         (or
          (gsp/is-point-in-geom-data? point content-geom)
          (gsp/is-point-in-border? point content)))))

(defn inside-segment?
  [segment content-sr content-geom]
  (let [point (case (:command segment)
                :line-to  (-> (gsp/command->line segment)
                              (gsp/line-values 0.5))

                :curve-to (-> (gsp/command->bezier segment)
                              (gsp/curve-values 0.5)))]

    (and (grc/contains-point? content-sr point)
         (gsp/is-point-in-geom-data? point content-geom))))

(defn overlap-segment?
  "Finds if the current segment is overlapping against other
  segment meaning they have the same coordinates"
  [segment content]

  (let [overlap-single?
        (fn [other]
          (when (and (= (:command segment) (:command other))
                     (contains? #{:line-to :curve-to} (:command segment)))

            (case (:command segment)
              :line-to (let [[p1 q1] (gsp/command->line segment)
                             [p2 q2] (gsp/command->line other)]

                         (when (or (and (< (gpt/distance p1 p2) 0.1)
                                        (< (gpt/distance q1 q2) 0.1))
                                   (and (< (gpt/distance p1 q2) 0.1)
                                        (< (gpt/distance q1 p2) 0.1)))
                           [segment other]))

              :curve-to (let [[p1 q1 h11 h21] (gsp/command->bezier segment)
                              [p2 q2 h12 h22] (gsp/command->bezier other)]

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
                     (conj result (upc/make-move-to (:prev current)))
                     result)]
        (recur (first content)
               (rest content)
               (gsp/command->point current)
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

(defn create-union [content-a content-a-split content-b content-b-split sr-a sr-b]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content-b that are not inside content-a
  (let [content-a-geom (gsp/content->geom-data content-a)
        content-b-geom (gsp/content->geom-data content-b)

        content
        (concat
         (->> content-a-split (filter #(not (contains-segment? % content-b sr-b content-b-geom))))
         (->> content-b-split (filter #(not (contains-segment? % content-a sr-a content-a-geom)))))

        content-geom (gsp/content->geom-data content)

        content-sr (gsp/content->selrect (fix-move-to content))

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
  (let [content-a-geom (gsp/content->geom-data content-a)
        content-b-geom (gsp/content->geom-data content-b)]
    (d/concat-vec
     (->> content-a-split (filter #(not (contains-segment? % content-b sr-b content-b-geom))))

     ;; Reverse second content so we can have holes inside other shapes
     (->> content-b-split
          (filter #(and (contains-segment? % content-a sr-a content-a-geom)
                        (not (overlap-segment? % content-a-split))))))))

(defn create-intersection [content-a content-a-split content-b content-b-split sr-a sr-b]
  ;; Pick all segments in content-a that are inside content-b
  ;; Pick all segments in content-b that are inside content-a
  (let [content-a-geom (gsp/content->geom-data content-a)
        content-b-geom (gsp/content->geom-data content-b)]
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
        should-reverse? (and (not= :union bool-type)
                             (= (ups/clockwise? content-b)
                                (ups/clockwise? content-a)))

        content-a (-> content-a
                      (close-paths)
                      (add-previous))

        content-b (-> content-b
                      (close-paths)
                      (cond-> should-reverse? (ups/reverse-content))
                      (add-previous))

        sr-a (gsp/content->selrect content-a)
        sr-b (gsp/content->selrect content-b)

        ;; Split content in new segments in the intersection with the other path
        [content-a-split content-b-split] (content-intersect-split content-a content-b sr-a sr-b)
        content-a-split (->> content-a-split add-previous (filter is-segment?))
        content-b-split (->> content-b-split add-previous (filter is-segment?))

        content
        (case bool-type
          :union        (create-union        content-a content-a-split content-b content-b-split sr-a sr-b)
          :difference   (create-difference   content-a content-a-split content-b content-b-split sr-a sr-b)
          :intersection (create-intersection content-a content-a-split content-b content-b-split sr-a sr-b)
          :exclude      (create-exclusion    content-a-split content-b-split))]

    (-> content
        remove-duplicated-segments
        fix-move-to
        ups/close-subpaths)))

(defn content-bool
  [bool-type contents]
  ;; We apply the boolean operation in to each pair and the result to the next
  ;; element
  (if (seq contents)
    (->> contents
         (reduce (partial content-bool-pair bool-type))
         (into []))
    []))
