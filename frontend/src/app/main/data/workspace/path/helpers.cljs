;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.helpers
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.intersect :as gsi]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]))

(defn append-node
  "Creates a new node in the path. Usually used when drawing."
  [shape position prev-point prev-handler]
  (let [segment (path/next-node (:content shape) position prev-point prev-handler)]
    (-> shape
        (update :content path/append-segment segment)
        (path/update-geometry))))

(defn angle-points [common p1 p2]
  (mth/abs
   (gpt/angle-with-other
    (gpt/to-vec common p1)
    (gpt/to-vec common p2))))

(defn opposite-handler-target
  "Returns the opposite handler target for mirror or aligned modes."
  [node handler opposite mode]
  (if (and (some? node) (some? handler) (some? opposite))
    (case mode
      :mirror
      (gpt/subtract (gpt/scale node 2) handler)

      :aligned
      (let [handler-vector (gpt/to-vec node handler)]
        (if (mth/almost-zero? (gpt/length handler-vector))
          opposite
          (gpt/subtract node
                        (gpt/scale (gpt/unit handler-vector)
                                   (gpt/distance node opposite)))))

      opposite)
    opposite))

(defn- calculate-opposite-delta [node handler opposite match-angle? match-distance? dx dy]
  (if (and (some? handler) (some? opposite))
    (let [;; To match the angle, the angle should be matching (angle between points 180deg)
          angle-handlers (angle-points node handler opposite)

          match-angle? (and match-angle? (<= (mth/abs (- 180 angle-handlers)) 0.1))

          ;; To match distance the distance should be matching
          match-distance? (and match-distance? (mth/almost-zero? (- (gpt/distance node handler)
                                                                    (gpt/distance node opposite))))

          new-handler (-> handler (update :x + dx) (update :y + dy))

          v1 (gpt/to-vec node handler)
          v2 (gpt/to-vec node new-handler)

          delta-angle (gpt/angle-with-other v1 v2)
          delta-sign (gpt/angle-sign v1 v2)

          distance-scale (/ (gpt/distance node handler)
                            (gpt/distance node new-handler))

          new-opposite (cond-> opposite
                         match-angle?
                         (gpt/rotate node (* delta-sign delta-angle))

                         match-distance?
                         (gpt/scale-from node distance-scale))]
      [(- (:x new-opposite) (:x opposite))
       (- (:y new-opposite) (:y opposite))])
    ;; Leave missing opposite handles unchanged.
    [0 0]))

(defn handlers-joined?
  "True when a node's handlers are collinear and opposite."
  [content index prefix]
  (let [[op-idx op-prefix] (path/opposite-index content index prefix)
        node     (path/handler->node content index prefix)
        handler  (path/get-handler-point content index prefix)
        opposite (when op-idx (path/get-handler-point content op-idx op-prefix))]
    (boolean
     (and (some? op-idx)
          (some? handler)
          (some? opposite)
          (not= handler node)
          (not= opposite node)
          (<= (mth/abs (- 180 (angle-points node handler opposite))) 0.1)))))

(defn move-handler-modifiers
  ([content index prefix match-distance? match-angle? dx dy]
   (move-handler-modifiers content index prefix match-distance? match-angle? false dx dy))
  ([content index prefix match-distance? match-angle? rejoin? dx dy]

   (let [[cx cy] (path.helpers/prefix->coords prefix)
         [op-idx op-prefix] (path/opposite-index content index prefix)

         node (path/handler->node content index prefix)
         handler (path/get-handler-point content index prefix)
         opposite (path/get-handler-point content op-idx op-prefix)

         [ocx ocy] (path.helpers/prefix->coords op-prefix)
         [odx ody] (calculate-opposite-delta node handler opposite match-angle? match-distance? dx dy)

         hnv (if (some? handler)
               (gpt/to-vec node (-> handler (update :x + dx) (update :y + dy)))
               (gpt/point dx dy))
         mirrored-opposite (opposite-handler-target
                            node (gpt/add node hnv) opposite :mirror)]

     (-> {}
         (update index assoc cx dx cy dy)

         (cond->
          ;; Force an exact mirror when rejoining handlers.
          (and (some? op-idx) rejoin? (not= opposite node))
           (update op-idx assoc
                   ocx (- (:x mirrored-opposite) (:x opposite))
                   ocy (- (:y mirrored-opposite) (:y opposite)))

           (and (some? op-idx) (not rejoin?) (not= opposite node))
           (update op-idx assoc ocx odx ocy ody)

           (and (some? op-idx) (= opposite node) match-distance? match-angle?)
           (update op-idx assoc
                   ocx (- (:x mirrored-opposite) (:x opposite))
                   ocy (- (:y mirrored-opposite) (:y opposite))))))))

(defn align-handler-modifiers
  "Moves a handler and aligns its opposite without changing its length."
  [content index prefix dx dy]
  (let [[cx cy]            (path.helpers/prefix->coords prefix)
        [op-idx op-prefix] (path/opposite-index content index prefix)
        node               (path/handler->node content index prefix)
        opposite           (when (some? op-idx)
                             (path/get-handler-point content op-idx op-prefix))
        handler            (path/get-handler-point content index prefix)
        modifiers          (-> {} (update index assoc cx dx cy dy))]
    (if (and (some? handler) (some? opposite) (not= opposite node))
      (let [moved-handler (-> handler (update :x + dx) (update :y + dy))
            handler-vector (gpt/to-vec node moved-handler)
            target        (opposite-handler-target node moved-handler opposite :aligned)]
        (if (mth/almost-zero? (gpt/length handler-vector))
          modifiers
          (let [[ocx ocy] (path.helpers/prefix->coords op-prefix)]
            (update modifiers op-idx assoc
                    ocx (- (:x target) (:x opposite))
                    ocy (- (:y target) (:y opposite))))))
      modifiers)))

;; --- Per-node handler type (mirror / aligned / independent)

(defn handler-node-index
  "Returns the anchor command index for a handler."
  [index prefix]
  (if (= prefix :c1) (dec index) index))

(defn node-primary-handler
  "Returns a curve handler for a node, preferring its incoming handle."
  [content node-index]
  (let [n       (count content)
        out-idx (inc node-index)]
    (cond
      (and (>= node-index 0) (< node-index n)
           (= :curve-to (:command (nth content node-index nil))))
      [node-index :c2]

      (and (< out-idx n)
           (= :curve-to (:command (nth content out-idx nil))))
      [out-idx :c1]

      :else nil)))

(defn handlers-equal-length?
  "True when a node's two handlers are the same distance from the node."
  [content index prefix]
  (let [[op-idx op-prefix] (path/opposite-index content index prefix)
        node     (path/handler->node content index prefix)
        handler  (path/get-handler-point content index prefix)
        opposite (when op-idx (path/get-handler-point content op-idx op-prefix))]
    (boolean
     (and (some? handler) (some? opposite)
          (mth/almost-zero? (- (gpt/distance node handler)
                               (gpt/distance node opposite)))))))

(defn derive-handler-type
  "Infers a node's handler type from its geometry."
  [content node-index]
  (if-let [[idx prefix] (node-primary-handler content node-index)]
    (cond
      (not (handlers-joined? content idx prefix)) :independent
      (handlers-equal-length? content idx prefix) :mirror
      :else                                       :aligned)
    :independent))

(defn remap-handler-types
  "Remaps handler types by node position after structural changes."
  [handler-types old-content new-content]
  (let [handler-types (or handler-types {})]
    (if (= (count old-content) (count new-content))
      handler-types
      (let [types-by-position
            (reduce-kv
             (fn [result index type]
               (let [segment (nth old-content index nil)]
                 (if (or (nil? segment) (= :close-path (:command segment)))
                   result
                   (update result
                           (path.helpers/segment->point segment)
                           (fnil conj #{})
                           type))))
             {}
             handler-types)]
        (into {}
              (keep (fn [[index segment]]
                      (when-not (= :close-path (:command segment))
                        (let [types (get types-by-position
                                         (path.helpers/segment->point segment))]
                          (when (= 1 (count types))
                            [index (first types)])))))
              (d/enumerate new-content))))))

;; Nodes and segments use command indices. Handlers use `[index prefix]`.
;; Selection and hover use grouped index sets:
;;   {:nodes #{index} :segments #{index} :handlers #{[index prefix]}}

(def empty-selection
  {:nodes #{} :segments #{} :handlers #{}})

(defn node?
  "True when the command at the given content index is a selectable node."
  [content index]
  (and (number? index)
       (<= 0 index)
       (< index (count content))
       (not= :close-path (:command (nth content index nil)))))

(defn node-indices
  "Indices of every selectable node in the content."
  [content]
  (into []
        (comp (remove (fn [[_ seg]] (= :close-path (:command seg))))
              (map first))
        (d/enumerate content)))

(defn node-position
  "Position of the node at the given content command index."
  [content index]
  (path.helpers/segment->point (nth content index)))

(defn curve-node?
  "True when the node at `index` has a visible curve handler."
  [content index]
  (when (node? content index)
    (let [node           (node-position content index)
          incoming       (when (= :curve-to (:command (nth content index nil)))
                           (path/get-handler-point content index :c2))
          outgoing-index (inc index)
          outgoing       (when (= :curve-to (:command (nth content outgoing-index nil)))
                           (path/get-handler-point content outgoing-index :c1))]
      (boolean (some #(and (some? %) (not= node %)) [incoming outgoing])))))

(defn node-positions
  "Set of positions for the given node indices in the content."
  [content indices]
  (let [indices (set indices)]
    (into #{}
          (comp (filter (fn [[index _]] (contains? indices index)))
                (map (fn [[_ seg]] (path.helpers/segment->point seg))))
          (d/enumerate content))))

(defn nodes-in-rect
  "Indices of the nodes whose position falls inside the given rect."
  [content rect]
  (into #{}
        (comp (remove (fn [[_ seg]] (= :close-path (:command seg))))
              (filter (fn [[_ seg]] (gsh/has-point-rect? rect (path.helpers/segment->point seg))))
              (map first))
        (d/enumerate content)))

(def segment-entries
  "Returns selectable path segments."
  path/segment-entries)

(defn segment-node-indices
  "Unique endpoint-node indices for the selected segment command indices."
  [content segment-indices]
  (let [segment-indices (set segment-indices)]
    (into #{}
          (comp (filter #(contains? segment-indices (:index %)))
                (mapcat (juxt :from-index :to-index))
                (remove nil?))
          (segment-entries content))))

(defn check-enabled
  "Returns path actions enabled for selected node indices."
  [content selected-nodes]
  (when content
    (let [selected-nodes    (into #{} (filter #(node? content %)) selected-nodes)
          selected-segments (filter (fn [{:keys [from-index to-index]}]
                                      (and (contains? selected-nodes from-index)
                                           (contains? selected-nodes to-index)))
                                    (segment-entries content))
          num-segments      (count selected-segments)
          num-nodes         (count selected-nodes)
          nodes-selected?   (seq selected-nodes)
          segments-selected? (seq selected-segments)
          max-segments      (/ (* num-nodes (dec num-nodes)) 2)
          curves-selected?  (some #(curve-node? content %) selected-nodes)
          corners-selected? (some #(not (curve-node? content %)) selected-nodes)]
      {:make-corner (and nodes-selected? curves-selected?)
       :make-curve (and nodes-selected? corners-selected?)
       :merge-nodes (and nodes-selected? (>= num-nodes 2))
       :join-nodes (and nodes-selected? (>= num-nodes 2) (< num-segments max-segments))
       :separate-nodes (or segments-selected? (= num-nodes 1))})))

(defn selected-node-indices
  "Returns selected nodes plus endpoints of selected segments."
  [content selection]
  (into (get selection :nodes #{})
        (segment-node-indices content (get selection :segments #{}))))

(defn selection-coordinate-rect
  "Returns the bounds of selected segments, nodes, and handlers."
  [content selection]
  (let [segments    (get selection :segments #{})
        node-indices (selected-node-indices content selection)
        handlers    (get selection :handlers #{})
        segment-rect (when (seq segments)
                       (path/calc-selrect
                        (path/extract-content content {:segments segments})))
        point-rect   (grc/points->rect
                      (into (node-positions content node-indices)
                            (keep (fn [[index prefix]]
                                    (path/get-handler-point content index prefix)))
                            handlers))]
    (grc/join-rects (keep identity [segment-rect point-rect]))))

(defn handler-target-nodes
  "Returns nodes targeted by the current node and handler selection."
  [content selection]
  (into (selected-node-indices content selection)
        (map (fn [[idx prefix]] (handler-node-index idx prefix)))
        (get selection :handlers #{})))

(defn handler-selection-state
  "Returns targeted curve nodes and their shared handler mode."
  [content handler-types target-nodes]
  (let [curve-nodes (into #{} (filter #(curve-node? content %)) target-nodes)
        modes       (into #{}
                          (map (fn [index]
                                 (or (get handler-types index)
                                     (derive-handler-type content index))))
                          curve-nodes)]
    {:nodes curve-nodes
     :active-type (cond
                    (empty? modes) nil
                    (= 1 (count modes)) (first modes)
                    :else :mixed)}))

(defn handler-trigger-action
  "Returns the handler menu action for the active mode."
  [active-type]
  (if (= active-type :mixed) :open :select))

(def segment-insert-threshold
  "Maximum screen distance for midpoint insertion."
  12)

(defn segment-mid-point
  "Returns a segment's arc-length midpoint with split metadata."
  [{:keys [from to segment] :as entry}]
  (let [curve (path.helpers/entry->bezier entry)
        t     (if (= :line-to (:command segment))
                0.5
                (path.helpers/curve-arc-length-t curve))]
    (with-meta (path.helpers/curve-values curve t)
      {:from-p from :to-p to :t t})))

(defn insertion-mid-points
  "Precomputes segment midpoint insertion candidates."
  [content]
  (into []
        (comp (remove #(= :close-path (:command (:segment %))))
              (map segment-mid-point))
        (segment-entries content)))

(defn- closest-insertion-mid-point
  [mid-points position threshold]
  (some->> mid-points
           (reduce
            (fn [closest mid-point]
              (let [distance (gpt/distance position mid-point)]
                (if (and (<= distance threshold)
                         (or (nil? closest)
                             (< distance (first closest))))
                  [distance mid-point]
                  closest)))
            nil)
           second))

(defn insertion-point
  "Returns the on-path point a nearby click would insert, with split metadata."
  ([content position threshold anywhere?]
   (insertion-point content position threshold anywhere? nil))
  ([content position threshold anywhere? mid-points]
   (if anywhere?
     (let [point (path/closest-point content position 0.01)]
       (when (and (some? point) (<= (gpt/distance position point) threshold))
         point))
     (closest-insertion-mid-point
      (or mid-points (insertion-mid-points content)) position threshold))))

(defn- segment-lines
  [{:keys [from to segment]}]
  (if (= :curve-to (:command segment))
    (path.helpers/curve->lines from
                               to
                               (path/get-handler segment :c1)
                               (path/get-handler segment :c2))
    [[from to]]))

(defn segments-in-rect
  "Returns segments that cross or fall inside `rect`."
  [content rect]
  (let [rect-lines (gsi/points->lines (grc/rect->points rect))]
    (into #{}
          (comp
           (filter
            (fn [entry]
              (let [lines (segment-lines entry)]
                (or (some (fn [[from to]]
                            (or (grc/contains-point? rect from)
                                (grc/contains-point? rect to)))
                          lines)
                    (gsi/intersects-lines? rect-lines lines)))))
           (map :index))
          (segment-entries content))))

(defn handler-entries
  "Visible path handlers as `{:identity [index prefix] :point p}` entries."
  [content]
  (into []
        (comp
         (mapcat
          (fn [[index segment]]
            (when (= :curve-to (:command segment))
              (keep
               (fn [prefix]
                 (let [handler (path/get-handler-point content index prefix)
                       node    (path/handler->node content index prefix)]
                   (when (and handler (not= handler node))
                     {:identity [index prefix]
                      :point handler})))
               [:c1 :c2])))))
        (d/enumerate content)))

(defn handlers-in-rect
  "Identities of visible path handlers whose control point is inside `rect`."
  [content rect]
  (into #{}
        (comp (filter #(grc/contains-point? rect (:point %)))
              (map :identity))
        (handler-entries content)))

(defn remap-selected-nodes
  "Remaps selected nodes by position after structural changes."
  [selected-nodes old-content new-content]
  (if (empty? selected-nodes)
    selected-nodes
    (let [positions (node-positions old-content selected-nodes)]
      (into #{}
            (comp (remove (fn [[_ seg]] (= :close-path (:command seg))))
                  (filter (fn [[_ seg]] (contains? positions (path.helpers/segment->point seg))))
                  (map first))
            (d/enumerate new-content)))))

(defn- fragment-covered-nodes
  "Returns nodes already included in a duplicated segment fragment."
  [content {:keys [nodes segments]}]
  (let [nodes    (or nodes #{})
        segments (or segments #{})]
    (into #{}
          (comp (filter (fn [{:keys [index from-index to-index]}]
                          (or (contains? segments index)
                              (and (contains? nodes from-index)
                                   (contains? nodes to-index)))))
                (mapcat (juxt :from-index :to-index)))
          (segment-entries content))))

(defn duplicate-selection-content
  "Duplicates selected nodes and segments for splicing as new subpaths."
  [content selection offset]
  (let [fragment (path/extract-content content selection)
        fragment (cond-> fragment
                   (and (seq fragment) (some? offset))
                   (path/move-content offset))
        fragment (vec fragment)
        covered  (fragment-covered-nodes content selection)
        free     (sort (remove covered (get selection :nodes #{})))]
    (reduce (fn [{:keys [sub selected]} node-index]
              (if-let [{ext :content ext-selected :selected}
                       (path/duplicate-node-content content node-index offset)]
                (let [start (count sub)]
                  {:sub      (into sub ext)
                   :selected (into selected (map #(+ start %)) ext-selected)})
                {:sub sub :selected selected}))
            {:sub fragment :selected (set (node-indices fragment))}
            free)))

(defn remap-selection
  "Remaps a grouped selection after path content changes."
  [selection old-content new-content]
  (let [selection (or selection empty-selection)]
    (if (= (count old-content) (count new-content))
      (-> selection
          (update :handlers
                  (fn [handlers]
                    (into #{}
                          (filter (fn [[index _]]
                                    (= :curve-to (:command (nth new-content index nil)))))
                          handlers)))
          ;; Drop indices that became subpath breaks.
          (update :segments
                  (fn [segments]
                    (into #{}
                          (remove (fn [index]
                                    (= :move-to (:command (nth new-content index nil)))))
                          segments))))
      (assoc empty-selection
             :nodes (remap-selected-nodes (get selection :nodes #{})
                                          old-content
                                          new-content)))))
