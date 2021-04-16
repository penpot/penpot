;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.tools
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gshp]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [clojure.set :as set]
   [app.common.math :as mth]
   [app.util.path.commands :as upc]
   [app.util.path.geom :as upg]
   ))

(defn remove-line-curves
  "Remove all curves that have both handlers in the same position that the
  beggining and end points. This makes them really line-to commands"
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

(defn make-curve-point
  "Changes the content to make the point a 'curve'. The handlers will be positioned
  in the same vector that results from te previous->next points but with fixed length."
  [content point]
  (let [content-next (d/enumerate (d/with-prev-next content))

        make-curve
        (fn [command previous]
          (if (= :line-to (:command command))
            (let [cur-point (upc/command->point command)
                  pre-point (upc/command->point previous)]
              (-> command
                  (assoc :command :curve-to)
                  (assoc :params (upc/make-curve-params cur-point pre-point))))
            command))

        update-handler
        (fn [command prefix handler]
          (if (= :curve-to (:command command))
            (let [cx (d/prefix-keyword prefix :x)
                  cy (d/prefix-keyword prefix :y)]
              (-> command
                  (assoc-in [:params cx] (:x handler))
                  (assoc-in [:params cy] (:y handler))))
            command))

        calculate-vector
        (fn [point next prev]
          (let [base-vector (if (or (nil? next) (nil? prev) (= next prev))
                              (-> (gpt/to-vec point (or next prev))
                                  (gpt/normal-left))
                              (gpt/to-vec next prev))]
            (-> base-vector
                (gpt/unit)
                (gpt/multiply (gpt/point 100)))))

        redfn (fn [content [index [command prev next]]]
                (if (= point (upc/command->point command))
                  (let [prev-point (if (= :move-to (:command command)) nil (upc/command->point prev))
                        next-point (if (= :move-to (:command next)) nil (upc/command->point next))
                        handler-vector (calculate-vector point next-point prev-point)
                        handler (gpt/add point handler-vector)
                        handler-opposite (gpt/add point (gpt/negate handler-vector))]
                    (-> content
                        (d/update-when index make-curve prev)
                        (d/update-when index update-handler :c2 handler)
                        (d/update-when (inc index) make-curve command)
                        (d/update-when (inc index) update-handler :c1 handler-opposite)))

                  content))]
    (as-> content $
      (reduce redfn $ content-next)
      (remove-line-curves $))))

(defn get-segments
  "Given a content and a set of points return all the segments in the path
  that uses the points"
  [content points]
  (let [point-set (set points)]

    (loop [segments []
           prev-point nil
           start-point nil
           cur-cmd (first content)
           content (rest content)]

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
                       (conj [prev-point cur-point cur-cmd]))]

        (if (some? cur-cmd)
          (recur segments
                 cur-point
                 start-point
                 (first content)
                 (rest content))

          segments)))))

(defn split-segments
  "Given a content creates splits commands between points with new segments"
  [content points value]
  (let [split-command
        (fn [[start end cmd]]
          (case (:command cmd)
            :line-to [cmd (upg/split-line-to start cmd value)]
            :curve-to [cmd (upg/split-curve-to start cmd value)]
            :close-path [cmd [(upc/make-line-to (gpt/line-val start end value)) cmd]]
            nil))

        cmd-changes
        (->> (get-segments content points)
             (into {} (comp (map split-command)
                            (filter (comp not nil?)))))

        process-segments
        (fn [command]
          (if (contains? cmd-changes command)
            (get cmd-changes command)
            [command]))]

    (into [] (mapcat process-segments) content)))

(defn remove-nodes
  "Removes from content the points given. Will try to reconstruct the paths
  to keep everything consistent"
  [content points]

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
                        (and curve? (not (empty? subpath)) (not= old-prev-point new-prev-point))
                        (update :params merge last-handler))

              head-idx (dec (count result))

              result (cond-> result
                       (not remove?)
                       (update head-idx conj cur-cmd))]
          (recur result
                 cur-handler
                 (first content)
                 (rest content)))))))

(defn join-nodes
  "Creates new segments between points that weren't previously"
  [content points]

  (let [segments-set (into #{}
                           (map (fn [[p1 p2 _]] [p1 p2]))
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
         [point-a point-b :as segment] (first segments)
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
  "Reduces the continguous segments in points to a single point"
  [content points]
  (let [point->merge-point (-> content
                               (get-segments points)
                               (group-segments)
                               (calculate-merge-points points))]
    (-> content
        (separate-nodes points)
        (replace-points point->merge-point))))

