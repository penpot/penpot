;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.segment
  "A collection of helpers for work with plain segment type"
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as impl]
   [clojure.set :as set]))

#?(:clj (set! *warn-on-reflection* true))

(defn update-handler
  [command prefix point]
  (let [[cox coy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
    (-> command
        (assoc-in [:params cox] (:x point))
        (assoc-in [:params coy] (:y point)))))

(defn get-handler [{:keys [params] :as command} prefix]
  (let [cx (d/prefix-keyword prefix :x)
        cy (d/prefix-keyword prefix :y)]
    (when (and command
               (contains? params cx)
               (contains? params cy))
      (gpt/point (get params cx)
                 (get params cy)))))

(defn get-handlers
  "Retrieve a map where for every point will retrieve a list of
  the handlers that are associated with that point.
  point -> [[index, prefix]]"
  [content]
  (let [prev-point* (volatile! nil)
        vec-conj    (fnil conj [])]
    (impl/-reduce content
                  (fn [result index type _ _ _ _ x y]
                    (let [curr-point (gpt/point x y)
                          prev-point (deref prev-point*)]
                      (vreset! prev-point* curr-point)
                      (if (and prev-point (= :curve-to type))
                        (-> result
                            (update prev-point vec-conj [index :c1])
                            (update curr-point vec-conj [index :c2]))
                        result)))
                  {})))

(defn point-indices
  [content point]
  (->> (d/enumerate content)
       (filter (fn [[_ segment]] (= point (helpers/segment->point segment))))
       (mapv (fn [[index _]] index))))

(defn handler-indices
  "Return an index where the key is the positions and the values the handlers"
  [content point]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-segment pre-segment]]]
                 (if (and (some? pre-segment) (= :curve-to (:command cur-segment)))
                   (let [cur-pos (helpers/segment->point cur-segment)
                         pre-pos (helpers/segment->point pre-segment)]
                     (cond-> []
                       (= pre-pos point) (conj [index :c1])
                       (= cur-pos point) (conj [index :c2])))
                   [])))))

(defn opposite-index
  "Calculates the opposite index given a prefix and an index"
  [content index prefix]

  (let [point (if (= prefix :c2)
                (helpers/segment->point (nth content index))
                (helpers/segment->point (nth content (dec index))))

        point->handlers (get-handlers content)

        handlers (->> point
                      (point->handlers)
                      (filter (fn [[ci cp]] (and (not= index ci) (not= prefix cp)))))]

    (cond
      (= (count handlers) 1)
      (->> handlers first)

      (and (= :c1 prefix) (= (count content) index))
      [(dec index) :c2]

      :else nil)))

(defn get-handler-point
  "Given a segment index and prefix, get a handler point"
  [content index prefix]
  (when (and (some? index)
             (some? prefix))
    (impl/-lookup content index
                  (fn [command c1x c1y c2x c2y x y]
                    (let [prefix (if (= :curve-to command)
                                   prefix
                                   nil)]
                      (case prefix
                        :c1 (gpt/point c1x c1y)
                        :c2 (gpt/point c2x c2y)
                        (gpt/point x y)))))))

;; FIXME: revisit this function
(defn handler->node
  [content index prefix]
  (if (= prefix :c1)
    (helpers/segment->point (nth content (dec index)))
    (helpers/segment->point (nth content index))))

(defn calculate-opposite-handler
  "Given a point and its handler, gives the symmetric handler"
  [point handler]
  (let [handler-vector (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate handler-vector))))

(defn opposite-handler
  "Calculates the coordinates of the opposite handler"
  [point handler]
  (let [phv (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate phv))))

(defn get-points
  "Returns points for the given segment, faster version of
  the `content->points`."
  [content]
  (impl/with-cache content "get-points"
    (impl/-walk content
                (fn [type _ _ _ _ x y]
                  (when (not= type :close-path)
                    (gpt/point x y)))
                [])))

(defn segments->content
  ([segments]
   (segments->content segments false))

  ([segments closed?]
   (let [initial (first segments)
         lines (rest segments)]

     (d/concat-vec
      [{:command :move-to
        :params (select-keys initial [:x :y])}]

      (->> lines
           (map #(hash-map :command :line-to
                           :params (select-keys % [:x :y]))))

      (when closed?
        [{:command :close-path}])))))

;; FIXME: incorrect API, don't need full shape
(defn path->lines
  "Given a path returns a list of lines that approximate the path"
  [shape]
  (loop [command (first (:content shape))
         pending (rest (:content shape))
         result []
         last-start nil
         prev-point nil]

    (if-let [{:keys [command params]} command]
      (let [point (if (= :close-path command)
                    last-start
                    (gpt/point params))

            result (case command
                     :line-to  (conj result [prev-point point])
                     :curve-to (let [h1 (gpt/point (:c1x params) (:c1y params))
                                     h2 (gpt/point (:c2x params) (:c2y params))]
                                 (into result (helpers/curve->lines prev-point point h1 h2)))
                     :move-to  (cond-> result
                                 last-start (conj [prev-point last-start]))
                     result)
            last-start (if (= :move-to command)
                         point
                         last-start)]
        (recur (first pending)
               (rest pending)
               result
               last-start
               point))

      (conj result [prev-point last-start]))))

(def ^:const path-closest-point-accuracy 0.01)

;; FIXME: move to helpers?, this function need performance review, it
;; is executed so many times on path edition
(defn- curve-closest-point
  [position start end h1 h2]
  (let [d (memoize (fn [t] (gpt/distance position (helpers/curve-values start end h1 h2 t))))]
    (loop [t1 0.0
           t2 1.0]
      (if (<= (mth/abs (- t1 t2)) path-closest-point-accuracy)
        (-> (helpers/curve-values start end h1 h2 t1)
            ;; store the segment info
            (with-meta {:t t1 :from-p start :to-p end}))

        (let [ht  (+ t1 (/ (- t2 t1) 2))
              ht1 (+ t1 (/ (- t2 t1) 4))
              ht2 (+ t1 (/ (* 3 (- t2 t1)) 4))

              [t1 t2] (cond
                        (< (d ht1) (d ht2))
                        [t1 ht]

                        (< (d ht2) (d ht1))
                        [ht t2]

                        (and (< (d ht) (d t1)) (< (d ht) (d t2)))
                        [ht1 ht2]

                        (< (d t1) (d t2))
                        [t1 ht]

                        :else
                        [ht t2])]
          (recur (double t1)
                 (double t2)))))))

(defn- line-closest-point
  "Point on line"
  [position from-p to-p]

  (let [e1 (gpt/to-vec from-p to-p)
        e2 (gpt/to-vec from-p position)

        len2 (+ (mth/sq (:x e1)) (mth/sq (:y e1)))
        t (/ (gpt/dot e1 e2) len2)]

    (if (and (>= t 0) (<= t 1) (not (mth/almost-zero? len2)))
      (-> (gpt/add from-p (gpt/scale e1 t))
          (with-meta {:t t
                      :from-p from-p
                      :to-p to-p}))

      ;; There is no perpendicular projection in the line so the closest
      ;; point will be one of the extremes
      (if (<= (gpt/distance position from-p) (gpt/distance position to-p))
        from-p
        to-p))))

;; FIXME: incorrect API, complete shape is not necessary here
(defn path-closest-point
  "Given a path and a position"
  [shape position]

  (let [point+distance
        (fn [[cur-segment prev-segment]]
          (let [from-p (helpers/segment->point prev-segment)
                to-p   (helpers/segment->point cur-segment)
                h1 (gpt/point (get-in cur-segment [:params :c1x])
                              (get-in cur-segment [:params :c1y]))
                h2 (gpt/point (get-in cur-segment [:params :c2x])
                              (get-in cur-segment [:params :c2y]))
                point
                (case (:command cur-segment)
                  :line-to
                  (line-closest-point position from-p to-p)

                  :curve-to
                  (curve-closest-point position from-p to-p h1 h2)

                  nil)]
            (when point
              [point (gpt/distance point position)])))

        find-min-point
        (fn [[min-p min-dist :as acc] [cur-p cur-dist :as cur]]
          (if (and (some? acc) (or (not cur) (<= min-dist cur-dist)))
            [min-p min-dist]
            [cur-p cur-dist]))]

    (->> (:content shape)
         (d/with-prev)
         (map point+distance)
         (reduce find-min-point)
         (first))))


(defn closest-point
  "Given a path and a position"
  [content position]

  (let [point+distance
        (fn [[cur-segment prev-segment]]
          (let [from-p (helpers/segment->point prev-segment)
                to-p   (helpers/segment->point cur-segment)
                h1 (gpt/point (get-in cur-segment [:params :c1x])
                              (get-in cur-segment [:params :c1y]))
                h2 (gpt/point (get-in cur-segment [:params :c2x])
                              (get-in cur-segment [:params :c2y]))
                point
                (case (:command cur-segment)
                  :line-to
                  (line-closest-point position from-p to-p)

                  :curve-to
                  (curve-closest-point position from-p to-p h1 h2)

                  nil)]
            (when point
              [point (gpt/distance point position)])))

        find-min-point
        (fn [[min-p min-dist :as acc] [cur-p cur-dist :as cur]]
          (if (and (some? acc) (or (not cur) (<= min-dist cur-dist)))
            [min-p min-dist]
            [cur-p cur-dist]))]

    (->> content
         (d/with-prev)
         (map point+distance)
         (reduce find-min-point)
         (first))))

(defn- remove-line-curves
  "Remove all curves that have both handlers in the same position that the
  beginning and end points. This makes them really line-to commands"
  [content]
  (let [with-prev (d/enumerate (d/with-prev content))
        process-command
        (fn [content [index [command prev]]]

          (let [cur-point (helpers/segment->point command)
                pre-point (helpers/segment->point prev)
                handler-c1 (get-handler command :c1)
                handler-c2 (get-handler command :c2)]
            (if (and (= :curve-to (:command command))
                     (= cur-point handler-c2)
                     (= pre-point handler-c1))
              (assoc content index {:command :line-to
                                    :params (into {} cur-point)})
              content)))]

    (reduce process-command content with-prev)))

(defn make-corner-point
  "Changes the content to make a point a 'corner'"
  [content point]
  (let [handlers (-> (get-handlers content)
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


(defn- line->curve
  [from-p segment]

  (let [to-p (helpers/segment->point segment)

        v (gpt/to-vec from-p to-p)
        d (gpt/distance from-p to-p)

        dv1 (-> (gpt/normal-left v)
                (gpt/scale (/ d 3)))

        h1 (gpt/add from-p dv1)

        dv2 (-> (gpt/to-vec to-p h1)
                (gpt/unit)
                (gpt/scale (/ d 3)))

        h2 (gpt/add to-p dv2)]
    (-> segment
        (assoc :command :curve-to)
        (update :params (fn [params]
                          ;; ensure plain map
                          (-> (into {} params)
                              (assoc :c1x (:x h1))
                              (assoc :c1y (:y h1))
                              (assoc :c2x (:x h2))
                              (assoc :c2y (:y h2))))))))

;; FIXME: optimize
(defn is-curve?
  [content point]
  (let [handlers (-> (get-handlers content)
                     (get point))
        handler-points (map #(get-handler-point content (first %) (second %)) handlers)]
    (some #(not= point %) handler-points)))

(def ^:private xf:mapcat-points
  (comp
   (mapcat #(vector (:next-p %) (:prev-p %)))
   (remove nil?)))

(defn make-curve-point
  "Changes the content to make the point a 'curve'. The handlers will be positioned
  in the same vector that results from the previous->next points but with fixed length."
  [content point]

  (let [indices (point-indices content point)
        vectors (map (fn [index]
                       (let [segment (nth content index)
                             prev-i (dec index)
                             prev (when (not (= :move-to (:command segment)))
                                    (get content prev-i))
                             next-i (inc index)
                             next (get content next-i)

                             next (when (not (= :move-to (:command next)))
                                    next)]
                         {:index index
                          :prev-i (when (some? prev) prev-i)
                          :prev-c prev
                          :prev-p (helpers/segment->point prev)
                          :next-i (when (some? next) next-i)
                          :next-c next
                          :next-p (helpers/segment->point next)
                          :segment segment}))
                     indices)

        points (into #{} xf:mapcat-points vectors)]

    (if (= (count points) 2)
      (let [v1 (gpt/to-vec (first points) point)
            v2 (gpt/to-vec (first points) (second points))
            vp (gpt/project v1 v2)
            vh (gpt/subtract v1 vp)

            add-curve
            (fn [content {:keys [index prev-p next-p next-i]}]
              (let [cur-segment (get content index)
                    next-segment (get content next-i)

                    ;; New handlers for prev-point and next-point
                    prev-h (when (some? prev-p) (gpt/add prev-p vh))
                    next-h (when (some? next-p) (gpt/add next-p vh))

                    ;; Correct 1/3 to the point improves the curve
                    prev-correction (when (some? prev-h) (gpt/scale (gpt/to-vec prev-h point) (/ 1 3)))
                    next-correction (when (some? next-h) (gpt/scale (gpt/to-vec next-h point) (/ 1 3)))

                    prev-h (when (some? prev-h) (gpt/add prev-h prev-correction))
                    next-h (when (some? next-h) (gpt/add next-h next-correction))]
                (cond-> content
                  (and (= :line-to (:command cur-segment)) (some? prev-p))
                  (update index helpers/update-curve-to prev-p prev-h)

                  (and (= :line-to (:command next-segment)) (some? next-p))
                  (update next-i helpers/update-curve-to next-h next-p)

                  (and (= :curve-to (:command cur-segment)) (some? prev-p))
                  (update index update-handler :c2 prev-h)

                  (and (= :curve-to (:command next-segment)) (some? next-p))
                  (update next-i update-handler :c1 next-h))))]

        (reduce add-curve content vectors))

      (let [add-curve
            (fn [content {:keys [index segment prev-p next-c next-i]}]
              (cond-> content
                (= :line-to (:command segment))
                (update index #(line->curve prev-p %))

                (= :curve-to (:command segment))
                (update index #(line->curve prev-p %))

                (= :line-to (:command next-c))
                (update next-i #(line->curve point %))

                (= :curve-to (:command next-c))
                (update next-i #(line->curve point %))))]
        (reduce add-curve content vectors)))))

(defn get-segments-with-points
  "Given a content and a set of points return all the segments in the path
  that uses the points"
  [content points]
  (let [point-set (set points)]
    (loop [result      (transient [])
           prev-point  nil
           start-point nil
           index       0
           content     (seq content)]
      (if-let [{:keys [command] :as segment} (first content)]
        (let [close-path? (= command :close-path)
              move-to?    (= command :move-to)

              cur-point   (if close-path?
                            start-point
                            (helpers/segment->point segment))

              ;; If there is a move-to we don't have a segment
              prev-point  (if move-to?
                            nil
                            prev-point)

              ;; We update the start point
              start-point  (if move-to?
                             cur-point
                             start-point)

              result      (cond-> result
                            (and (some? prev-point)
                                 (contains? point-set prev-point)
                                 (contains? point-set cur-point))

                            (conj! (-> segment
                                       (assoc :start prev-point)
                                       (assoc :end cur-point)
                                       (assoc :index index))))]
          (recur result
                 cur-point
                 start-point
                 (inc index)
                 (rest content)))

        (persistent! result)))))

(defn split-segments
  "Given a content creates splits commands between points with new segments"
  [content points value]

  (let [split-command
        (fn [{:keys [command start end index] :as segment}]
          (case command
            :line-to [index (helpers/split-line-to start segment value)]
            :curve-to [index (helpers/split-curve-to start segment value)]
            :close-path [index [(helpers/make-line-to (gpt/lerp start end value)) segment]]
            nil))

        segment-changes
        (->> (get-segments-with-points content points)
             (into {} (keep split-command)))

        process-segments
        (fn [[index command]]
          (if (contains? segment-changes index)
            (get segment-changes index)
            [command]))]

    (into [] (mapcat process-segments) (d/enumerate content))))

;; FIXME: rename to next-segment
(defn next-node
  "Calculates the next-node to be inserted."
  [content position prev-point prev-handler]
  (let [position     (select-keys position [:x :y])
        last-command (-> content last :command)
        add-line?    (and prev-point (not prev-handler) (not= last-command :close-path))
        add-curve?   (and prev-point prev-handler (not= last-command :close-path))]
    (cond
      add-line?   {:command :line-to
                   :params position}
      add-curve?  {:command :curve-to
                   :params (helpers/make-curve-params position prev-handler)}
      :else       {:command :move-to
                   :params position})))
(defn remove-nodes
  "Removes from content the points given. Will try to reconstruct the paths
  to keep everything consistent"
  [content points]

  (if (empty? points)
    content

    (let [content (d/with-prev content)]

      (loop [result []
             last-handler nil
             [cur-segment prev-segment] (first content)
             content (rest content)]

        (if (nil? cur-segment)
          ;; The result with be an array of arrays were every entry is a subpath
          (->> result
               ;; remove empty and only 1 node subpaths
               (filter #(> (count %) 1))
               ;; flatten array-of-arrays plain array
               (flatten)
               (into []))

          (let [move? (= :move-to (:command cur-segment))
                curve? (= :curve-to (:command cur-segment))

                ;; When the old command was a move we start a subpath
                result (if move? (conj result []) result)

                subpath (peek result)

                point (helpers/segment->point cur-segment)

                old-prev-point (helpers/segment->point prev-segment)
                new-prev-point (helpers/segment->point (peek subpath))

                remove? (contains? points point)


                ;; We store the first handler for the first curve to be removed to
                ;; use it for the first handler of the regenerated path
                cur-handler (cond
                              (and (not last-handler) remove? curve?)
                              (select-keys (:params cur-segment) [:c1x :c1y])

                              (not remove?)
                              nil

                              :else
                              last-handler)

                cur-segment (cond-> cur-segment
                              ;; If we're starting a subpath and it's not a move make it a move
                              (and (not move?) (empty? subpath))
                              (assoc :command :move-to
                                     :params (select-keys (:params cur-segment) [:x :y]))

                              ;; If have a curve the first handler will be relative to the previous
                              ;; point. We change the handler to the new previous point
                              (and curve? (seq subpath) (not= old-prev-point new-prev-point))
                              (update :params merge last-handler))

                head-idx (dec (count result))

                result (cond-> result
                         (not remove?)
                         (update head-idx conj cur-segment))]
            (recur result
                   cur-handler
                   (first content)
                   (rest content))))))))

(defn join-nodes
  "Creates new segments between points that weren't previously"
  [content points]

  (let [segments-set (into #{}
                           (map (juxt :start :end))
                           (get-segments-with-points content points))

        create-line-command (fn [point other]
                              [(helpers/make-move-to point)
                               (helpers/make-line-to other)])

        not-segment? (fn [point other] (and (not (contains? segments-set [point other]))
                                            (not (contains? segments-set [other point]))))

        new-content (->> (d/map-perm create-line-command not-segment? points)
                         (flatten)
                         (into []))]

    (into content new-content)))


(defn separate-nodes
  "Removes the segments between the points given"
  [content points]

  (let [content (d/with-prev content)]
    (loop [result []
           [cur-segment prev-segment] (first content)
           content (rest content)]

      (if (nil? cur-segment)
        (->> result
             (filter #(> (count %) 1))
             (flatten)
             (into []))

        (let [prev-point (helpers/segment->point prev-segment)
              cur-point (helpers/segment->point cur-segment)

              cur-segment (cond-> cur-segment
                            (and (contains? points prev-point)
                                 (contains? points cur-point))

                            (assoc :command :move-to
                                   :params (select-keys (:params cur-segment) [:x :y])))

              move? (= :move-to (:command cur-segment))

              result (if move? (conj result []) result)
              head-idx (dec (count result))

              result (-> result
                         (update head-idx conj cur-segment))]
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

;; FIXME: revisit impl of this fn
(defn- group-segments [segments]
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

(defn- calculate-merge-points [group-segments points]
  (let [index-merge-point (fn [group] (vector group (gpt/center-points group)))
        index-group (fn [point] (vector point (d/seek #(contains? % point) group-segments)))

        group->merge-point (into {} (map index-merge-point) group-segments)
        point->group (into {} (map index-group) points)]
    (d/mapm #(group->merge-point %2) point->group)))

;; TODO: Improve the replace for curves
(defn- replace-points
  "Replaces the points in a path for its merge-point"
  [content point->merge-point]
  (let [replace-command
        (fn [segment]
          (let [point (helpers/segment->point segment)]
            (if (contains? point->merge-point point)
              (let [merge-point (get point->merge-point point)]
                (-> segment (update :params assoc :x (:x merge-point) :y (:y merge-point))))
              segment)))]
    (->> content
         (mapv replace-command))))

(defn merge-nodes
  "Reduces the contiguous segments in points to a single point"
  [content points]
  (let [segments (get-segments-with-points content points)]
    (if (seq segments)
      (let [point->merge-point (-> segments
                                   (group-segments)
                                   (calculate-merge-points points))]
        (-> content
            (separate-nodes points)
            (replace-points point->merge-point)))
      content)))

(defn transform-content
  "Applies a transformation matrix over content and returns a new
  content as PathData instance."
  [content transform]
  (if (some? transform)
    (impl/-transform content transform)
    content))

(defn move-content
  "Applies a displacement over content and returns a new content as
  PathData instance. Implemented in function of `transform-content`."
  [content move-vec]
  (let [transform (gmt/translate-matrix move-vec)]
    (transform-content content transform)))

(defn calculate-extremities
  "Calculate extremities for the provided content"
  [content]
  (loop [points  (transient #{})
         content (not-empty (vec content))
         from-p  nil
         move-p  nil]
    (if content
      (let [last-p  (peek content)
            content (if (= :move-to (:command last-p))
                      (pop content)
                      content)
            segment (get content 0)
            to-p    (helpers/segment->point segment)]

        (if segment
          (case (:command segment)
            :move-to
            (recur (conj! points to-p)
                   (not-empty (subvec content 1))
                   to-p
                   to-p)

            :close-path
            (recur (conj! points move-p)
                   (not-empty (subvec content 1))
                   move-p
                   move-p)

            :line-to
            (recur (cond-> points
                     (and from-p to-p)
                     (-> (conj! move-p)
                         (conj! to-p)))
                   (not-empty (subvec content 1))
                   to-p
                   move-p)

            :curve-to
            (let [c1 (helpers/segment->point segment :c1)
                  c2 (helpers/segment->point segment :c2)]
              (recur (if (and from-p to-p c1 c2)
                       (reduce conj!
                               (-> points (conj! from-p) (conj! to-p))
                               (helpers/calculate-curve-extremities from-p to-p c1 c2))
                       points)

                     (not-empty (subvec content 1))
                     to-p
                     move-p)))
          (persistent! points)))
      (persistent! points))))

(defn content->selrect
  [content]
  (let [extremities (calculate-extremities content)
        ;; We haven't found any extremes so we turn the commands to points
        extremities
        (if (empty? extremities)
          (->> content (keep helpers/segment->point))
          extremities)]

    ;; If no points are returned we return an empty rect.
    (if (d/not-empty? extremities)
      (grc/points->rect extremities)
      (grc/make-rect))))

(defn content-center
  [content]
  (-> content
      content->selrect
      grc/rect->center))

(defn append-segment
  [content segment]
  (let [content (cond
                  (impl/path-data? content)
                  (vec content)

                  (nil? content)
                  []

                  :else
                  content)]
    (conj content (impl/check-segment segment))))
