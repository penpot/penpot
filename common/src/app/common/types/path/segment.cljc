;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.path.segment
  "A collection of helpers for work with plain segment type"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.path.fit :as fit]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as impl]
   [app.common.types.path.subpath :as subpath]
   [clojure.set :as set]))

#?(:clj (set! *warn-on-reflection* true))

(defn- update-handler
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

;; FIXME: can be optimized with internal reduction
(defn point-indices
  [content point]
  (->> (d/enumerate content)
       (filter (fn [[_ segment]] (= point (helpers/segment->point segment))))
       (map (fn [[index _]] index))))

(defn handler-indices
  "Returns [[index prefix] ...] of all handlers associated with point."
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
  "Calculates the opposite handler index given a content, index and prefix."
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

;; FIXME: rename to get-point
(defn get-handler-point
  "Given a segment index and prefix, get a handler point"
  [content index prefix]
  (when (and (some? index)
             (some? content))
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

(defn segment-entries
  "Returns selectable segments with their command index and endpoints."
  [content]
  (loop [index               0
         pending             (seq content)
         previous            nil
         previous-index      nil
         subpath-start       nil
         subpath-start-index nil
         result              []]
    (if-let [{:keys [command] :as segment} (first pending)]
      (let [close-path?         (= command :close-path)
            move-to?            (= command :move-to)
            point               (if close-path?
                                  subpath-start
                                  (helpers/segment->point segment))
            point-index         (if close-path? subpath-start-index index)
            result              (cond-> result
                                  (and previous point (not move-to?))
                                  (conj {:index index
                                         :from previous
                                         :from-index previous-index
                                         :to point
                                         :to-index point-index
                                         :segment segment}))
            subpath-start       (if move-to? point subpath-start)
            subpath-start-index (if move-to? index subpath-start-index)]
        (recur (inc index)
               (next pending)
               point
               point-index
               subpath-start
               subpath-start-index
               result))
      result)))

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

;; FIXME: move to helpers?, this function need performance review, it
;; is executed so many times on path edition
(defn- curve-closest-point
  [position start end h1 h2 precision]
  (let [d (memoize (fn [t] (gpt/distance position (helpers/curve-values start end h1 h2 t))))]
    (loop [t1 0.0
           t2 1.0]
      (if (<= (mth/abs (- t1 t2)) precision)
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
  "Finds the closest point in the line segment defined by from-p and to-p"
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

(defn closest-point
  "Returns the closest point in the path to the position, at a given precision"
  [content position precision]
  (let [point+distance
        (fn [[cur-segment prev-segment]]
          (let [from-p (helpers/segment->point prev-segment)
                to-p (helpers/segment->point cur-segment)
                h1 (gpt/point (get-in cur-segment [:params :c1x])
                              (get-in cur-segment [:params :c1y]))
                h2 (gpt/point (get-in cur-segment [:params :c2x])
                              (get-in cur-segment [:params :c2y]))
                point
                (case (:command cur-segment)
                  :line-to
                  (line-closest-point position from-p to-p)

                  :curve-to
                  (curve-closest-point position from-p to-p h1 h2 precision)

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
  beginning and end points. This makes them really line-to commands.

  NOTE: works with plain format so it expects to receive a vector"
  [content]
  (assert (vector? content) "expected a plain format for `content`")

  (let [with-prev (d/enumerate (d/with-prev content))

        process-segment
        (fn [content [index [segment prev]]]
          (let [cur-point (helpers/segment->point segment)
                pre-point (helpers/segment->point prev)
                handler-c1 (get-handler segment :c1)
                handler-c2 (get-handler segment :c2)]
            (if (and (= :curve-to (:command segment))
                     (= cur-point handler-c2)
                     (= pre-point handler-c1))
              (assoc content index {:command :line-to
                                    :params (into {} cur-point)})
              content)))]

    (reduce process-segment content with-prev)))

(defn make-corner-point
  "Changes the content to make a point a 'corner'"
  [content point]
  (let [handlers
        (-> (get-handlers content)
            (get point))

        transform-content
        (fn [content [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> content
                (assoc-in [index :params cx] (:x point))
                (assoc-in [index :params cy] (:y point)))))

        content
        (reduce transform-content (vec content) handlers)

        content
        (remove-line-curves content)]

    (impl/from-plain content)))

;; FIXME: optimize
(defn is-curve?
  [content point]
  (let [handlers (-> (get-handlers content)
                     (get point))
        handler-points (map #(get-handler-point content (first %) (second %)) handlers)]
    (some #(not= point %) handler-points)))

(def ^:private xf:mapcat-points
  (comp
   (mapcat #(list (:next-p %) (:prev-p %)))
   (remove nil?)))

(defn- curve-neighbourhood
  "Returns the adjacent segments and points for one node."
  [content index]
  (let [segment (get content index)
        prev-i  (dec index)
        prev    (when (not= :move-to (:command segment))
                  (get content prev-i))
        next-i  (inc index)
        next    (get content next-i)
        next    (when (not= :move-to (:command next)) next)]
    {:index index
     :prev-i (when (some? prev) prev-i)
     :prev-c prev
     :prev-p (helpers/segment->point prev)
     :next-i (when (some? next) next-i)
     :next-c next
     :next-p (helpers/segment->point next)
     :segment segment}))

(defn- smooth-tangent
  "Returns tangent data for a smooth curve node."
  [content point indices neighbourhoods neighbour-points]
  (let [[first-point second-point] (vec neighbour-points)
        prev-neighbour (some :prev-p neighbourhoods)
        next-neighbour (some :next-p neighbourhoods)
        seam?          (and (= 2 (count indices))
                            (= :move-to (:command (get content (first indices))))
                            (let [end-index (last indices)]
                              (or (= end-index (dec (count content)))
                                  (= :close-path
                                     (:command (get content (inc end-index)))))))
        first-unit     (gpt/unit (gpt/to-vec point first-point))
        second-unit    (gpt/unit (gpt/to-vec point second-point))
        angle-tangent  (let [delta (gpt/subtract second-unit first-unit)]
                         (if (mth/almost-zero? (gpt/length delta))
                           (gpt/perpendicular first-unit)
                           (gpt/unit delta)))
        tangent        (if seam?
                         (let [chord (gpt/to-vec prev-neighbour next-neighbour)]
                           (if (mth/almost-zero? (gpt/length chord))
                             angle-tangent
                             (gpt/unit chord)))
                         angle-tangent)
        length         (/ (min (gpt/distance point first-point)
                               (gpt/distance point second-point))
                          3)]
    {:tangent tangent
     :length length
     :seam? seam?
     :prev-neighbour prev-neighbour
     :next-neighbour next-neighbour}))

(defn- smooth-handle
  "Returns a smooth handle toward `neighbour`."
  [point {:keys [tangent length seam? prev-neighbour next-neighbour]} neighbour]
  (when (some? neighbour)
    (let [direction (gpt/unit (gpt/to-vec point neighbour))
          side      (cond
                      (and seam? (= neighbour prev-neighbour)) -1
                      (and seam? (= neighbour next-neighbour)) 1
                      :else (if (neg? (gpt/dot direction tangent)) -1 1))]
      (gpt/add point (gpt/scale tangent (* side length))))))

(defn- apply-smooth-neighbour
  "Adds smooth handles around one matching node."
  [content point tangent-data {:keys [index prev-p next-p next-i]}]
  (let [curr-command (:command (get content index))
        next-command (:command (get content next-i))
        prev-h       (smooth-handle point tangent-data prev-p)
        next-h       (smooth-handle point tangent-data next-p)]
    (cond-> content
      (and (= :line-to curr-command) (some? prev-p))
      (update index helpers/update-curve-to prev-p prev-h)

      (and (= :line-to next-command) (some? next-p))
      (update next-i helpers/update-curve-to next-h next-p)

      (and (= :curve-to curr-command) (some? prev-p))
      (update index update-handler :c2 prev-h)

      (and (= :curve-to next-command) (some? next-p))
      (update next-i update-handler :c1 next-h))))

(defn- corner-handle
  [point neighbour]
  (gpt/add point (gpt/scale (gpt/to-vec point neighbour) (/ 1 3))))

(defn- apply-corner-neighbour
  "Adds independent handles around one matching node."
  [content point {:keys [index segment prev-p next-c next-i next-p]}]
  (cond-> content
    (and (= :line-to (:command segment)) (some? prev-p))
    (update index helpers/update-curve-to prev-p (corner-handle point prev-p))

    (and (= :curve-to (:command segment)) (some? prev-p))
    (update index update-handler :c2 (corner-handle point prev-p))

    (and (= :line-to (:command next-c)) (some? next-p))
    (update next-i helpers/update-curve-to (corner-handle point next-p) next-p)

    (and (= :curve-to (:command next-c)) (some? next-p))
    (update next-i update-handler :c1 (corner-handle point next-p))))

(defn make-curve-point
  "Adds curve handles to every node at `point`."
  [content point]
  (let [indices        (vec (point-indices content point))
        content        (vec content)
        neighbourhoods (mapv #(curve-neighbourhood content %) indices)
        neighbour-points (into #{} xf:mapcat-points neighbourhoods)]
    (if (= (count neighbour-points) 2)
      (let [tangent-data (smooth-tangent
                          content point indices neighbourhoods neighbour-points)]
        (reduce #(apply-smooth-neighbour %1 point tangent-data %2)
                content
                neighbourhoods))
      (reduce #(apply-corner-neighbour %1 point %2) content neighbourhoods))))

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

(defn collapse-handler
  "Collapses a handler onto its node and simplifies flat curves to lines."
  [content index prefix]
  (let [content (vec content)
        node    (handler->node content index prefix)
        [cx cy] (helpers/prefix->coords prefix)]
    (if (and (some? node)
             (= :curve-to (dm/get-in content [index :command])))
      (impl/from-plain
       (-> content
           (assoc-in [index :params cx] (:x node))
           (assoc-in [index :params cy] (:y node))
           (remove-line-curves)))
      (impl/from-plain content))))

(def ^:private curve-toggle-bow
  "Perpendicular handle offset used when curving a line."
  0.25)

(defn toggle-segment-curve
  "Toggles a segment between a line and a bowed curve."
  [content index]
  (let [content (vec content)
        segment (get content index)
        from    (helpers/segment->point (get content (dec index)))
        to      (helpers/segment->point segment)]
    (impl/from-plain
     (case (:command segment)
       :line-to
       (if (some? from)
         (let [v    (gpt/to-vec from to)
               perp (gpt/scale (gpt/point (- (:y v)) (:x v)) curve-toggle-bow)
               h1   (-> from (gpt/add (gpt/scale v (/ 1 3))) (gpt/add perp))
               h2   (-> from (gpt/add (gpt/scale v (/ 2 3))) (gpt/add perp))]
           (update content index helpers/update-curve-to h1 h2))
         content)

       :curve-to
       (assoc content index {:command :line-to
                             :params (select-keys (:params segment) [:x :y])})

       content))))

(defn- subpath-start-indices
  "Returns the starting command index for every command in `content`."
  [content]
  (loop [i      0
         start  0
         result (transient [])]
    (if (>= i (count content))
      (persistent! result)
      (let [start (if (= :move-to (:command (nth content i))) i start)]
        (recur (inc i) start (conj! result start))))))

(defn remove-segments
  "Removes segments and opens their subpaths. Closing segments become
   lines when needed to preserve geometry."
  [content indices]
  (let [content (vec content)
        indices (set indices)
        starts  (subpath-start-indices content)

        broken  (into #{} (keep #(nth starts % nil)) indices)

        content
        (into []
              (comp
               (map-indexed
                (fn [i cmd]
                  (cond
                    (contains? indices i)
                    (when-not (= :close-path (:command cmd))
                      {:command :move-to
                       :params (select-keys (:params cmd) [:x :y])})

                    ;; Preserve the closing edge of broken subpaths.
                    (and (= :close-path (:command cmd))
                         (contains? broken (nth starts i)))
                    {:command :line-to
                     :params (-> (nth content (nth starts i))
                                 (get :params)
                                 (select-keys [:x :y]))}

                    :else cmd)))
               (remove nil?))
              content)

        subpaths
        (reduce (fn [acc cmd]
                  (if (or (= :move-to (:command cmd)) (empty? acc))
                    (conj acc [cmd])
                    (update acc (dec (count acc)) conj cmd)))
                []
                content)]
    (impl/from-plain
     (into [] (comp (filter #(> (count %) 1)) cat) subpaths))))

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
(def ^:private ^:const chain-samples-per-segment 8)

(defn- chain-samples
  "Returns ordered samples along a segment chain."
  [chain]
  (into [(:start (first chain))]
        (mapcat
         (fn [{:keys [start end segment]}]
           (let [ts (map #(/ (double %) chain-samples-per-segment)
                         (range 1 (inc chain-samples-per-segment)))]
             (if (= :curve-to (:command segment))
               (let [curve (helpers/command->bezier segment start)]
                 (map #(helpers/curve-values curve %) ts))
               (map #(helpers/line-values [start end] %) ts)))))
        chain))

(defn- chain-tangent
  "Returns an inward unit tangent at one end of a chain."
  [{:keys [start end segment]} at-start? origin samples]
  (let [tangent
        (if (= :curve-to (:command segment))
          (let [curve (helpers/command->bezier segment start)]
            (cond-> (helpers/curve-tangent curve (if at-start? 0 1))
              (not at-start?) (gpt/negate)))
          (if at-start?
            (gpt/to-vec start end)
            (gpt/to-vec end start)))
        tangent (gpt/unit tangent)]
    (if (gpt/almost-zero? tangent)
      (->> samples
           (map #(gpt/to-vec origin %))
           (remove gpt/almost-zero?)
           (map gpt/unit)
           (first))
      tangent)))

(defn- flat-chain?
  "True when a sampled chain is nearly straight."
  [start end samples]
  (or (mth/almost-zero? (gpt/distance start end))
      (every? #(< (gpt/point-line-distance % start end) 0.01) samples)))

(defn- restore-split-curve
  "Rejoins two untouched De Casteljau pieces into one cubic."
  [chain]
  (when (= 2 (count chain))
    (let [{left-segment :segment left-start :start} (first chain)
          {right-segment :segment}                 (second chain)]
      (when (and (= :curve-to (:command left-segment))
                 (= :curve-to (:command right-segment)))
        (let [[start split left-h1 left-h2 :as left-curve]
              (helpers/command->bezier left-segment left-start)
              [_ end right-h1 right-h2 :as right-curve]
              (helpers/command->bezier right-segment split)
              left-length  (gpt/distance left-h2 split)
              right-length (gpt/distance split right-h1)]
          (when (and (not (mth/almost-zero? left-length))
                     (not (mth/almost-zero? right-length)))
            (let [t            (/ left-length (+ left-length right-length))
                  original-h1  (-> (gpt/to-vec start left-h1)
                                   (gpt/scale (/ 1.0 t))
                                   (gpt/add start))
                  original-h2  (-> (gpt/to-vec end right-h2)
                                   (gpt/scale (/ 1.0 (- 1.0 t)))
                                   (gpt/add end))
                  candidate    [start end original-h1 original-h2]
                  [left' right'] (helpers/curve-split candidate t)]
              (when (every? true?
                            (map gpt/close?
                                 (concat left-curve right-curve)
                                 (concat left' right')))
                (helpers/make-curve-to end original-h1 original-h2)))))))))

(defn- approximate-chain
  "Replaces a segment chain with a line or fitted curve."
  [chain]
  (or (restore-split-curve chain)
      (let [start   (:start (first chain))
            end     (:end (peek chain))
            samples (chain-samples chain)
            tan1    (chain-tangent (first chain) true start (rest samples))
            tan2    (chain-tangent (peek chain) false end (rest (rseq samples)))]
        (if (or (flat-chain? start end samples)
                (nil? tan1)
                (nil? tan2))
          (helpers/make-line-to end)
          (let [[h1 h2] (fit/fit-cubic samples tan1 tan2)]
            (helpers/make-curve-to end h1 h2))))))

(defn- split-content-subpaths
  "Splits plain path commands into subpath command vectors."
  [content]
  (reduce
   (fn [subpaths segment]
     (if (= :move-to (:command segment))
       (conj subpaths [segment])
       (if (seq subpaths)
         (update subpaths (dec (count subpaths)) conj segment)
         subpaths)))
   []
   content))

(defn- removed-point-joins-subpaths?
  "True when a removed point is an endpoint shared by open subpaths."
  [subpaths points]
  (let [open-endpoints
        (keep (fn [subpath]
                (let [start (some-> subpath first helpers/segment->point)
                      end   (some-> subpath peek helpers/segment->point)]
                  (when (and (some? start)
                             (some? end)
                             (not (subpath/pt= start end)))
                    #{start end})))
              subpaths)]
    (some (fn [point]
            (< 1 (count (filter (fn [endpoints]
                                  (some #(subpath/pt= point %) endpoints))
                                open-endpoints))))
          points)))

(defn- rotate-removed-closed-start
  "Rotates a closed subpath so a removed seam becomes an interior node."
  [subpath points]
  (let [subpath  (vec subpath)
        close?   (= :close-path (:command (peek subpath)))
        body      (cond-> subpath close? pop)
        start     (some-> body first helpers/segment->point)
        end       (some-> body peek helpers/segment->point)
        closed?   (or close? (= start end))]
    (if-not (and closed? (contains? points start))
      subpath
      (let [segments (subvec body 1)
            ;; Materialize an implicit close segment before rotating.
            segments (cond-> segments
                       (and close? (not= start end))
                       (conj (helpers/make-line-to start)))
            new-start-index
            (first
             (keep-indexed
              (fn [index segment]
                (when-not (contains? points (helpers/segment->point segment))
                  index))
              segments))]
        (if (nil? new-start-index)
          []
          (let [new-start (helpers/segment->point
                           (nth segments new-start-index))
                rotated   (into []
                                (concat
                                 (subvec segments (inc new-start-index))
                                 (subvec segments 0 (inc new-start-index))))]
            (cond-> (into [(helpers/make-move-to new-start)] rotated)
              close? (conj {:command :close-path :params {}}))))))))

(defn- remove-nodes*
  "Removes interior nodes from prepared content."
  [content points]
  (loop [result []
         pending []
         subpath-start nil
         prev-point nil
         segments (seq content)]

    (if (nil? segments)
      ;; Drop subpaths left with only a start point.
      (into [] (comp (filter #(> (count %) 1)) cat) result)

      (let [segment (first segments)
            move?   (= :move-to (:command segment))
            close?  (= :close-path (:command segment))
            point   (if close? subpath-start (helpers/segment->point segment))
            remove? (and (not close?) (contains? points point))

            ;; Start a result subpath for each move command.
            result  (if move? (conj result []) result)
            head    (dec (count result))
            subpath (peek result)

            [result pending]
            (cond
              ;; Collect removed interior nodes until the next kept node.
              remove?
              [result (if (seq subpath)
                        (conj pending {:start prev-point :end point :segment segment})
                        [])]

              move?
              [(update result head conj segment) []]

              ;; Promote the first kept node to the subpath start.
              (empty? subpath)
              [(update result head conj (helpers/make-move-to point)) []]

              (seq pending)
              (if (and close? (contains? points subpath-start))
                ;; Close straight onto the new start.
                [(update result head conj segment) []]
                (let [chain  (conj pending {:start prev-point :end point :segment segment})
                      approx (approximate-chain chain)
                      ;; The close command already draws a zero-length replacement.
                      skip?  (and close?
                                  (= :line-to (:command approx))
                                  (< (gpt/distance (:start (first chain)) point) 0.01))
                      result (cond-> result
                               (not skip?) (update head conj approx)
                               close?      (update head conj segment))]
                  [result []]))

              :else
              [(update result head conj segment) []])]

        (recur result
               pending
               (if move? point subpath-start)
               point
               (next segments))))))

(defn remove-nodes
  "Removes nodes and joins surrounding segments with a fitted replacement."
  [content points]
  (if (empty? points)
    content
    (let [subpaths (split-content-subpaths content)
          content  (if (removed-point-joins-subpaths? subpaths points)
                     (subpath/close-subpaths content)
                     content)
          content  (into []
                         (mapcat #(rotate-removed-closed-start % points))
                         (split-content-subpaths
                          content))]
      (remove-nodes* content points))))

(defn join-nodes
  "Creates new segments between points that weren't previously.
  Returns plain segments vector."
  [content points]

  (let [;; Materialize the content to a vector (plain format)
        content
        (vec content)

        segments-set
        (into #{}
              (map (juxt :start :end))
              (get-segments-with-points content points))

        create-line-segment
        (fn [point other]
          [(helpers/make-move-to point)
           (helpers/make-line-to other)])

        not-segment?
        (fn [point other]
          (and (not (contains? segments-set [point other]))
               (not (contains? segments-set [other point]))))

        ;; FIXME: implement map-perm in terms of transducer, will
        ;; improve performance and remove the need to use flatten
        new-content
        (->> (d/map-perm create-line-segment not-segment? points)
             (flatten)
             (into []))]

    (into content new-content)))

(def ^:private separate-node-offset (gpt/point 8 8))

(defn- separate-node
  "Splits a node into offset open ends, preserving adjacent handles."
  [content point offset]
  (let [content (vec content)
        n       (count content)
        {ox :x oy :y} offset
        seg?    (fn [c] (and (some? c)
                             (not= :move-to (:command c))
                             (not= :close-path (:command c))))]
    (loop [i      0
           k      0
           result (transient [])]
      (if (>= i n)
        (persistent! result)
        (let [cmd   (nth content i)
              nxt   (nth content (inc i) nil)
              at-p? (and (not= :close-path (:command cmd))
                         (= point (helpers/segment->point cmd)))]
          (cond
            ;; Offset a subpath start.
            (and at-p? (= :move-to (:command cmd)))
            (let [off (gpt/point (* k ox) (* k oy))]
              (recur (inc i) (inc k)
                     (conj! result (-> cmd
                                       (update-in [:params :x] + (:x off))
                                       (update-in [:params :y] + (:y off))))))

            ;; Split an interior node into two subpaths.
            (and at-p? (seg? cmd) (seg? nxt))
            (let [off  (gpt/point (* k ox) (* k oy))
                  cmd' (cond-> (-> cmd
                                   (update-in [:params :x] + (:x off))
                                   (update-in [:params :y] + (:y off)))
                         (= :curve-to (:command cmd))
                         (-> (update-in [:params :c2x] + (:x off))
                             (update-in [:params :c2y] + (:y off))))
                  k2   (inc k)
                  off2 (gpt/point (* k2 ox) (* k2 oy))
                  mv   (helpers/make-move-to (gpt/add point off2))
                  nxt' (cond-> nxt
                         (= :curve-to (:command nxt))
                         (-> (update-in [:params :c1x] + (:x off2))
                             (update-in [:params :c1y] + (:y off2))))]
              (recur (+ i 2) (inc k2)
                     (-> result (conj! cmd') (conj! mv) (conj! nxt'))))

            ;; Open and offset a closed seam.
            (and at-p? (seg? cmd) (= :close-path (:command nxt)))
            (let [off  (gpt/point (* k ox) (* k oy))
                  cmd' (cond-> (-> cmd
                                   (update-in [:params :x] + (:x off))
                                   (update-in [:params :y] + (:y off)))
                         (= :curve-to (:command cmd))
                         (-> (update-in [:params :c2x] + (:x off))
                             (update-in [:params :c2y] + (:y off))))]
              ;; Drop the close command so the seam stays open.
              (recur (+ i 2) (inc k) (conj! result cmd')))

            ;; Offset the end of an open subpath.
            (and at-p? (seg? cmd) (not= :close-path (:command nxt)))
            (let [off (gpt/point (* k ox) (* k oy))]
              (recur (inc i) (inc k)
                     (conj! result (cond-> (-> cmd
                                               (update-in [:params :x] + (:x off))
                                               (update-in [:params :y] + (:y off)))
                                     (= :curve-to (:command cmd))
                                     (-> (update-in [:params :c2x] + (:x off))
                                         (update-in [:params :c2y] + (:y off)))))))

            :else
            (recur (inc i) k (conj! result cmd))))))))

(defn separate-nodes
  "Removes segments between points or splits one node into offset open ends."
  ([content points]
   (separate-nodes content points separate-node-offset))
  ([content points offset]
   (if (= 1 (count points))
     (separate-node (vec content) (first points) offset)

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
                    (rest content)))))))))


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
  "Joins and merges `points` into one point."
  [content points]
  (let [content  (join-nodes content points)
        segments (get-segments-with-points content points)]
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
    (impl/-transform (impl/path-data content) transform)
    content))

(defn move-content
  "Applies a displacement over content and returns a new content as
  PathData instance. Implemented in function of `transform-content`."
  [content move-vec]
  (let [transform (gmt/translate-matrix move-vec)]
    (transform-content content transform)))

(defn- calculate-extremities
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
                     (-> (conj! from-p)
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

(defn points->content
  "Given a vector of points generate a path content.

  Mainly used for generate a path content from user drawing points
  using curve drawing tool."
  [points & {:keys [close]}]
  (let [initial (first points)
        point->params
        (fn [point]
          {:x (dm/get-prop point :x)
           :y (dm/get-prop point :y)})]
    (loop [points (rest points)
           result [{:command :move-to
                    :params (point->params initial)}]]
      (if-let [point (first points)]
        (recur (rest points)
               (conj result {:command :line-to
                             :params (point->params point)}))

        (let [result (if close
                       (conj result {:command :close-path})
                       result)]
          (impl/from-plain result))))))

(defn smooth-points->content
  "Fits smooth path content through `points`, falling back to lines."
  [points tolerance]
  (let [curves (when (>= (count points) 3)
                 (fit/fit-curve points tolerance))]
    (if (empty? curves)
      (points->content points)
      (impl/from-plain
       (into [(helpers/make-move-to (ffirst curves))]
             (map (fn [[_ end h1 h2]]
                    (helpers/make-curve-to end h1 h2)))
             curves)))))
