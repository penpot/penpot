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
   [app.common.path.commands :as upc]
   [app.common.path.subpaths :as ups]))

(defn- reverse-command
  "Reverses a single command"
  [command]

  (let [{old-x :x old-y :y} (:params command)
        {:keys [x y]} (:prev command)
        {:keys [c1x c1y c2x c2y]} (:params command)]

    (-> command
        (assoc :prev (gpt/point old-x old-y))
        (update :params assoc :x x :y y)

        (cond-> (= :curve-to (:command command))
          (update :params assoc
                  :c1x c2x :c1y c2y
                  :c2x c1x :c2y c1y)))))

(defn- split-command
  [cmd values]
  (case (:command cmd)
    :line-to  (gsp/split-line-to-ranges (:prev cmd) cmd values)
    :curve-to (gsp/split-curve-to-ranges (:prev cmd) cmd values)
    [cmd]))

(defn split [seg-1 seg-2]
  (let [[ts-seg-1 ts-seg-2]
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
          [[] []])]

    [(split-command seg-1 ts-seg-1)
     (split-command seg-2 ts-seg-2)]))

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

(defn content-intersect-split
  "Given two path contents will return the intersect between them"
  [content-a content-b]

  (if (or (empty? content-a) (empty? content-b))
    [content-a content-b]

    (loop [current       (first content-a)
           pending       (rest content-a)
           content-b     content-b
           new-content-a []]

      (if (not (some? current))
        [new-content-a content-b]

        (let [[new-current new-pending new-content-b]

              (loop [current      current
                     pending      pending
                     other        (first content-b)
                     head-content []
                     tail-content (rest content-b)]

                (if (not (some? other))
                  ;; Finished recorring second content
                  [current pending head-content]

                  ;; We split the current
                  (let [[new-as new-bs] (split current other)
                        new-as (add-previous new-as (:prev current))
                        new-bs (add-previous new-bs (:prev other))]

                    (if (> (count new-as) 1)
                      ;; We add the new-a's to the stack and change the b then we iterate to the top
                      (recur (first new-as)
                             (d/concat [] (rest new-as) pending)
                             (first tail-content)
                             (d/concat [] head-content new-bs)
                             (rest tail-content))

                      ;; No current segment-segment split we continue searching
                      (recur current
                             pending
                             (first tail-content)
                             (conj head-content other)
                             (rest tail-content))))))]

          (recur (first new-pending)
                 (rest new-pending)
                 new-content-b
                 (conj new-content-a new-current)))))))

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
    (gsp/is-point-in-content? point content)))

(defn create-union [content-a content-a-split content-b content-b-split]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content-b that are not inside content-a
  (d/concat
   []
   (->> content-a-split (filter #(not (contains-segment? % content-b))))
   (->> content-b-split (filter #(not (contains-segment? % content-a))))))

(defn create-difference [content-a content-a-split content-b content-b-split]
  ;; Pick all segments in content-a that are not inside content-b
  ;; Pick all segments in content b that are inside content-a
  (d/concat
   []
   (->> content-a-split (filter #(not (contains-segment? % content-b))))

   ;; Reverse second content so we can have holes inside other shapes
   (->> content-b-split
        (reverse)
        (mapv reverse-command)
        (filter #(contains-segment? % content-a)))))

(defn create-intersection [content-a content-a-split content-b content-b-split]
  ;; Pick all segments in content-a that are inside content-b
  ;; Pick all segments in content-b that are inside content-a
  (d/concat
   []
   (->> content-a-split (filter #(contains-segment? % content-b)))
   (->> content-b-split (filter #(contains-segment? % content-a)))))


(defn create-exclusion [content-a content-b]
  ;; Pick all segments but reverse content-b (so it makes an exclusion)
  (let [content-b' (->> (reverse content-b)
                        (mapv reverse-command))]
    (d/concat [] content-a content-b')))


(defn fix-move-to
  [content]
  ;; Remove the field `:prev` and makes the necesaries `move-to`
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

  (let [content-a (add-previous content-a)
        content-b (add-previous content-b)

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
