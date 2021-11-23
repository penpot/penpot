;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.tools
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as upg]
   [app.common.math :as mth]
   [app.common.path.commands :as upc]
   [clojure.set :as set]))

(defn remove-line-curves
  "Remove all curves that have both handlers in the same position that the
  beginning and end points. This makes them really line-to commands"
  [content]
  (let [with-prev (d/enumerate (d/with-prev content))
        process-command
        (fn [content [index [command prev]]]

          (let [cur-point (upc/command->point command)
                pre-point (upc/command->point prev)
                handler-c1 (upc/get-handler command :c1)
                handler-c2 (upc/get-handler command :c2)]
            (if (and (= :curve-to (:command command))
                     (= cur-point handler-c2)
                     (= pre-point handler-c1))
              (assoc content index {:command :line-to
                                    :params cur-point})
              content)))]

    (reduce process-command content with-prev)))

(defn make-corner-point
  "Changes the content to make a point a 'corner'"
  [content point]
  (let [handlers (-> (upc/content->handlers content)
                     (get point))
        change-content
        (fn [content [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> content
                (assoc-in [index :params cx] (:x point))
                (assoc-in [index :params cy] (:y point)))))]
    (as-> content $
      (reduce change-content $ handlers)
      (remove-line-curves $))))

(defn line->curve
  [from-p cmd]

  (let [to-p (upc/command->point cmd)

        v (gpt/to-vec from-p to-p)
        d (gpt/distance from-p to-p)

        dv1 (-> (gpt/normal-left v)
                (gpt/scale (/ d 3)))

        h1 (gpt/add from-p dv1)

        dv2 (-> (gpt/to-vec to-p h1)
                (gpt/unit)
                (gpt/scale (/ d 3)))

        h2 (gpt/add to-p dv2)]
    (-> cmd
        (assoc :command :curve-to)
        (assoc-in [:params :c1x] (:x h1))
        (assoc-in [:params :c1y] (:y h1))
        (assoc-in [:params :c2x] (:x h2))
        (assoc-in [:params :c2y] (:y h2)))))

(defn make-curve-point
  "Changes the content to make the point a 'curve'. The handlers will be positioned
  in the same vector that results from te previous->next points but with fixed length."
  [content point]

  (let [indices (upc/point-indices content point)
        vectors (->> indices (mapv (fn [index]
                                     (let [cmd (nth content index)
                                           prev-i (dec index)
                                           prev (when (not (= :move-to (:command cmd)))
                                                  (get content prev-i))
                                           next-i (inc index)
                                           next (get content next-i)

                                           next (when (not (= :move-to (:command next)))
                                                  next)]
                                       (hash-map :index index
                                                 :prev-i (when (some? prev) prev-i)
                                                 :prev-c prev
                                                 :prev-p (upc/command->point prev)
                                                 :next-i (when (some? next) next-i)
                                                 :next-c next
                                                 :next-p (upc/command->point next)
                                                 :command cmd)))))


        points (->> vectors (mapcat #(vector (:next-p %) (:prev-p %))) (remove nil?) (into #{}))]

    (cond
      (= (count points) 2)
      ;;
      (let [v1 (gpt/to-vec (first points) point)
            v2 (gpt/to-vec (first points) (second points))
            vp (gpt/project v1 v2)
            vh (gpt/subtract v1 vp)

            add-curve
            (fn [content {:keys [index prev-p next-p next-i]}]
              (let [cur-cmd (get content index)
                    next-cmd (get content next-i)

                    ;; New handlers for prev-point and next-point
                    prev-h (when (some? prev-p) (gpt/add prev-p vh))
                    next-h (when (some? next-p) (gpt/add next-p vh))

                    ;; Correct 1/3 to the point improves the curve
                    prev-correction (when (some? prev-h) (gpt/scale (gpt/to-vec prev-h point) (/ 1 3)))
                    next-correction (when (some? next-h) (gpt/scale (gpt/to-vec next-h point) (/ 1 3)))

                    prev-h (when (some? prev-h) (gpt/add prev-h prev-correction))
                    next-h (when (some? next-h) (gpt/add next-h next-correction))
                    ]
                (cond-> content
                  (and (= :line-to (:command cur-cmd)) (some? prev-p))
                  (update index upc/update-curve-to prev-p prev-h)

                  (and (= :line-to (:command next-cmd)) (some? next-p))
                  (update next-i upc/update-curve-to next-h next-p)

                  (and (= :curve-to (:command cur-cmd)) (some? prev-p))
                  (update index upc/update-handler :c2 prev-h)

                  (and (= :curve-to (:command next-cmd)) (some? next-p))
                  (update next-i upc/update-handler :c1 next-h))))]
        (->> vectors (reduce add-curve content)))

      :else
      (let [add-curve
            (fn [content {:keys [index command prev-p next-c next-i]}]
              (cond-> content
                (= :line-to (:command command))
                (update index #(line->curve prev-p %))

                (= :line-to (:command next-c))
                (update next-i #(line->curve point %))))]
        (->> vectors (reduce add-curve content))))))

(defn get-segments
  "Given a content and a set of points return all the segments in the path
  that uses the points"
  [content points]
  (let [point-set (set points)]

    (loop [segments    []
           prev-point  nil
           start-point nil
           index       0
           cur-cmd     (first content)
           content     (rest content)]

      (let [;; Close-path makes a segment from the last point to the initial path point
            cur-point (if (= :close-path (:command cur-cmd))
                        start-point
                        (upc/command->point cur-cmd))

            ;; If there is a move-to we don't have a segment
            prev-point (if (= :move-to (:command cur-cmd))
                         nil
                         prev-point)

            ;; We update the start point
            start-point (if (= :move-to (:command cur-cmd))
                          cur-point
                          start-point)

            is-segment? (and (some? prev-point)
                             (contains? point-set prev-point)
                             (contains? point-set cur-point))

            segments (cond-> segments
                       is-segment?
                       (conj {:start prev-point
                              :end cur-point
                              :cmd cur-cmd
                              :index index}))]

        (if (some? cur-cmd)
          (recur segments
                 cur-point
                 start-point
                 (inc index)
                 (first content)
                 (rest content))

          segments)))))

(defn split-segments
  "Given a content creates splits commands between points with new segments"
  [content points value]

  (let [split-command
        (fn [{:keys [start end cmd index]}]
          (case (:command cmd)
            :line-to [index (upg/split-line-to start cmd value)]
            :curve-to [index (upg/split-curve-to start cmd value)]
            :close-path [index [(upc/make-line-to (gpt/lerp start end value)) cmd]]
            nil))

        cmd-changes
        (->> (get-segments content points)
             (into {} (comp (map split-command)
                            (filter (comp not nil?)))))

        process-segments
        (fn [[index command]]
          (if (contains? cmd-changes index)
            (get cmd-changes index)
            [command]))]

    (into [] (mapcat process-segments) (d/enumerate content))))

(defn remove-nodes
  "Removes from content the points given. Will try to reconstruct the paths
  to keep everything consistent"
  [content points]

  (if (empty? points)
    content

    (let [content (d/with-prev content)]

      (loop [result []
             last-handler nil
             [cur-cmd prev-cmd] (first content)
             content (rest content)]

        (if (nil? cur-cmd)
          ;; The result with be an array of arrays were every entry is a subpath
          (->> result
               ;; remove empty and only 1 node subpaths
               (filter #(> (count %) 1))
               ;; flatten array-of-arrays plain array
               (flatten)
               (into []))

          (let [move? (= :move-to (:command cur-cmd))
                curve? (= :curve-to (:command cur-cmd))

                ;; When the old command was a move we start a subpath
                result (if move? (conj result []) result)

                subpath (peek result)

                point (upc/command->point cur-cmd)

                old-prev-point (upc/command->point prev-cmd)
                new-prev-point (upc/command->point (peek subpath))

                remove? (contains? points point)


                ;; We store the first handler for the first curve to be removed to
                ;; use it for the first handler of the regenerated path
                cur-handler (cond
                              (and (not last-handler) remove? curve?)
                              (select-keys (:params cur-cmd) [:c1x :c1y])

                              (not remove?)
                              nil

                              :else
                              last-handler)

                cur-cmd (cond-> cur-cmd
                          ;; If we're starting a subpath and it's not a move make it a move
                          (and (not move?) (empty? subpath))
                          (assoc :command :move-to
                                 :params (select-keys (:params cur-cmd) [:x :y]))

                          ;; If have a curve the first handler will be relative to the previous
                          ;; point. We change the handler to the new previous point
                          (and curve? (seq subpath) (not= old-prev-point new-prev-point))
                          (update :params merge last-handler))

                head-idx (dec (count result))

                result (cond-> result
                         (not remove?)
                         (update head-idx conj cur-cmd))]
            (recur result
                   cur-handler
                   (first content)
                   (rest content))))))))

(defn join-nodes
  "Creates new segments between points that weren't previously"
  [content points]

  (let [segments-set (into #{}
                           (map (fn [{:keys [start end]}] [start end]))
                           (get-segments content points))

        create-line-command (fn [point other]
                              [(upc/make-move-to point)
                               (upc/make-line-to other)])

        not-segment? (fn [point other] (and (not (contains? segments-set [point other]))
                                            (not (contains? segments-set [other point]))))

        new-content (->> (d/map-perm create-line-command not-segment? points)
                         (flatten)
                         (into []))]

    (d/concat content new-content)))


(defn separate-nodes
  "Removes the segments between the points given"
  [content points]

  (let [content (d/with-prev content)]
    (loop [result []
           [cur-cmd prev-cmd] (first content)
           content (rest content)]

      (if (nil? cur-cmd)
        (->> result
             (filter #(> (count %) 1))
             (flatten)
             (into []))

        (let [prev-point (upc/command->point prev-cmd)
              cur-point (upc/command->point cur-cmd)

              cur-cmd (cond-> cur-cmd
                        (and (contains? points prev-point)
                             (contains? points cur-point))

                        (assoc :command :move-to
                               :params (select-keys (:params cur-cmd) [:x :y])))

              move? (= :move-to (:command cur-cmd))

              result (if move? (conj result []) result)
              head-idx (dec (count result))

              result (-> result
                         (update head-idx conj cur-cmd))]
          (recur result
                 (first content)
                 (rest content)))))))


(defn- add-to-set
  "Given a list of sets adds the value to the target set"
  [set-list target value]
  (->> set-list
       (mapv (fn [it]
               (cond-> it
                 (= it target) (conj value))))))

(defn- join-sets
  "Given a list of sets join two sets in the list into a new one"
  [set-list target other]
  (conj (->> set-list
             (filterv #(and (not= % target)
                            (not= % other))))
        (set/union target other)))

(defn group-segments [segments]
  (loop [result []
         {point-a :start point-b :end :as segment} (first segments)
         segments (rest segments)]

    (if (nil? segment)
      result

      (let [set-a (d/seek #(contains? % point-a) result)
            set-b (d/seek #(contains? % point-b) result)

            result (cond-> result
                     (and (nil? set-a) (nil? set-b))
                     (conj #{point-a point-b})

                     (and (some? set-a) (nil? set-b))
                     (add-to-set set-a point-b)

                     (and (nil? set-a) (some? set-b))
                     (add-to-set set-b point-a)

                     (and (some? set-a) (some? set-b) (not= set-a set-b))
                     (join-sets set-a set-b))]
        (recur result
               (first segments)
               (rest segments))))))

(defn calculate-merge-points [group-segments points]
  (let [index-merge-point (fn [group] (vector group (-> (gpt/center-points group)
                                                        (update :x mth/round)
                                                        (update :y mth/round))))
        index-group (fn [point] (vector point (d/seek #(contains? % point) group-segments)))

        group->merge-point (into {} (map index-merge-point) group-segments)
        point->group (into {} (map index-group) points)]
    (d/mapm #(group->merge-point %2) point->group)))

;; TODO: Improve the replace for curves
(defn replace-points
  "Replaces the points in a path for its merge-point"
  [content point->merge-point]
  (let [replace-command
        (fn [cmd]
          (let [point (upc/command->point cmd)]
            (if (contains? point->merge-point point)
              (let [merge-point (get point->merge-point point)]
                (-> cmd (update :params assoc :x (:x merge-point) :y (:y merge-point))))
              cmd)))]
    (->> content
         (mapv replace-command))))

(defn merge-nodes
  "Reduces the contiguous segments in points to a single point"
  [content points]
  (let [point->merge-point (-> content
                               (get-segments points)
                               (group-segments)
                               (calculate-merge-points points))]
    (-> content
        (separate-nodes points)
        (replace-points point->merge-point))))

