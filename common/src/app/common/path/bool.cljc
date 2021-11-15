;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.path.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.path.commands :as upc]
   [app.common.path.subpaths :as ups]))

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

(defn split
  [seg-1 seg-2]
  (let [r1 (gsp/command->selrect seg-1)
        r2 (gsp/command->selrect seg-2)]
    (if (not (gpr/overlaps-rects? r1 r2))
      [[seg-1] [seg-2]]
      (let [[ts-seg-1 ts-seg-2] (split-ts seg-1 seg-2)]
        [(-> (split-command seg-1 ts-seg-1) (add-previous (:prev seg-1)))
         (-> (split-command seg-2 ts-seg-2) (add-previous (:prev seg-2)))]))))

(defn content-intersect-split
  [content-a content-b]

  (let [cache (atom {})]
    (letfn [(split-cache [seg-1 seg-2]
              (cond
                (contains? @cache [seg-1 seg-2])
                (first (get @cache [seg-1 seg-2]))

                (contains? @cache [seg-2 seg-1])
                (second (get @cache [seg-2 seg-1]))

                :else
                (let [value (split seg-1 seg-2)]
                  (swap! cache assoc [seg-1 seg-2] value)
                  (first value))))

            (split-segment-on-content
              [segment content]

              (loop [current (first content)
                     content (rest content)
                     result [segment]]

                (if (nil? current)
                  result
                  (let [result (->> result (into [] (mapcat #(split-cache % current))))]
                    (recur (first content)
                           (rest content)
                           result)))))

            (split-content
              [content-a content-b]
              (into []
                    (mapcat #(split-segment-on-content % content-b))
                    content-a))]

      [(split-content content-a content-b)
       (split-content content-b content-a)])))

(defn is-segment?
  [cmd]
  (and (contains? cmd :prev)
       (contains? #{:line-to :curve-to} (:command cmd))))

(defn contains-segment?
  [segment content]

  (let [point (case (:command segment)
                :line-to  (-> (gsp/command->line segment)
                              (gsp/line-values 0.5))

                :curve-to (-> (gsp/command->bezier segment)
                              (gsp/curve-values 0.5)))]

    (or (gsp/is-point-in-content? point content)
        (gsp/is-point-in-border? point content))))

(defn inside-segment?
  [segment content]
  (let [point (case (:command segment)
                :line-to  (-> (gsp/command->line segment)
                              (gsp/line-values 0.5))

                :curve-to (-> (gsp/command->bezier segment)
                              (gsp/curve-values 0.5)))]

    (gsp/is-point-in-content? point content)))

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

(defn create-union [content-a content-a-split content-b content-b-split]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content-b that are not inside content-a
  (let [content
        (d/concat
         []
         (->> content-a-split (filter #(not (contains-segment? % content-b))))
         (->> content-b-split (filter #(not (contains-segment? % content-a)))))

        ;; Overlapping segments should be added when they are part of the border
        border-content
        (->> content-b-split
             (filterv #(and (contains-segment? % content-a)
                            (overlap-segment? % content-a-split)
                            (not (inside-segment? % content)))))]

    (d/concat content border-content)))

(defn create-difference [content-a content-a-split content-b content-b-split]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content b that are inside content-a
  ;;  removing overlapping
  (d/concat
   []
   (->> content-a-split (filter #(not (contains-segment? % content-b))))

   ;; Reverse second content so we can have holes inside other shapes
   (->> content-b-split
        (filter #(and (contains-segment? % content-a)
                      (not (overlap-segment? % content-a-split)))))))

(defn create-intersection [content-a content-a-split content-b content-b-split]
  ;; Pick all segments in content-a that are inside content-b
  ;; Pick all segments in content-b that are inside content-a
  (d/concat
   []
   (->> content-a-split (filter #(contains-segment? % content-b)))
   (->> content-b-split (filter #(contains-segment? % content-a)))))


(defn create-exclusion [content-a content-b]
  ;; Pick all segments
  (d/concat [] content-a content-b))


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

(defn content-bool-pair
  [bool-type content-a content-b]

  (let [content-a (-> content-a (close-paths) (add-previous))

        content-b (-> content-b
                      (close-paths)
                      (cond-> (ups/clockwise? content-b)
                        (ups/reverse-content))
                      (add-previous))

        ;; Split content in new segments in the intersection with the other path
        [content-a-split content-b-split] (content-intersect-split content-a content-b)
        content-a-split (->> content-a-split add-previous (filter is-segment?))
        content-b-split (->> content-b-split add-previous (filter is-segment?))

        bool-content
        (case bool-type
          :union        (create-union        content-a content-a-split content-b content-b-split)
          :difference   (create-difference   content-a content-a-split content-b content-b-split)
          :intersection (create-intersection content-a content-a-split content-b content-b-split)
          :exclude      (create-exclusion    content-a-split content-b-split))]

    (->> (fix-move-to bool-content)
         (ups/close-subpaths))))

(defn content-bool
  [bool-type contents]
  ;; We apply the boolean operation in to each pair and the result to the next
  ;; element
  (->> contents
       (reduce (partial content-bool-pair bool-type))
       (into [])))
