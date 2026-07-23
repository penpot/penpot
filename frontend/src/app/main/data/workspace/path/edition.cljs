;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.edition
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.tools :as tools]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.streams :as ms]
   [app.render-wasm.svg-fills :as svg-fills]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [potok.v2.core :as ptk]))

(defn- handler-modifier-delta
  [modifiers index prefix]
  (let [[cx cy] (path.helpers/prefix->coords prefix)]
    (gpt/point (dm/get-in modifiers [index cx] 0)
               (dm/get-in modifiers [index cy] 0))))

(defn- remove-handler-modifier
  [modifiers [index prefix]]
  (let [[cx cy]  (path.helpers/prefix->coords prefix)
        modifiers (update modifiers index dissoc cx cy)]
    (cond-> modifiers
      (empty? (get modifiers index)) (dissoc index))))

(defn- stored-handler-drag-mode
  "Returns a handler's stored drag mode, ignoring stale mirror state."
  [content handler-types index prefix]
  (case (get handler-types (helpers/handler-node-index index prefix))
    :mirror      (if (helpers/handlers-joined? content index prefix)
                   :mirror
                   :smart)
    :aligned     :aligned
    :independent :independent
    :smart))

(defn- active-selected-handlers
  "Returns valid handlers for the current drag."
  [content primary selected-handlers move-selection?]
  (let [handlers (if move-selection? selected-handlers #{primary})
        handlers (into #{}
                       (filter (fn [[index _]]
                                 (= :curve-to (:command (nth content index nil)))))
                       handlers)]
    (cond-> handlers
      (empty? handlers) (conj primary))))

(defn- handler-drag-modifiers
  "Returns modifiers for one dragged handler."
  [content handler-types selected-handlers start-modifiers move-delta mode
   move-selection? [index prefix]]
  (let [start-delta       (handler-modifier-delta start-modifiers index prefix)
        delta             (gpt/add start-delta move-delta)
        opposite-id       (path/opposite-index content index prefix)
        opposite-selected? (and move-selection?
                                (contains? selected-handlers opposite-id))
        joined?           (helpers/handlers-joined? content index prefix)
        handler-mode      (if move-selection?
                            (stored-handler-drag-mode
                             content handler-types index prefix)
                            mode)
        modifiers         (case handler-mode
                            :aligned
                            (helpers/align-handler-modifiers
                             content index prefix (:x delta) (:y delta))

                            :mirror
                            (helpers/move-handler-modifiers
                             content index prefix true true true (:x delta) (:y delta))

                            :independent
                            (helpers/move-handler-modifiers
                             content index prefix false false false (:x delta) (:y delta))

                            (helpers/move-handler-modifiers
                             content index prefix false
                             (and joined? (not opposite-selected?))
                             false (:x delta) (:y delta)))]
    (cond-> modifiers
      opposite-selected? (remove-handler-modifier opposite-id))))

(defn- selected-handler-modifiers
  "Combines modifiers for all dragged handlers."
  [content handler-types selected-handlers start-modifiers move-delta mode move-selection?]
  (reduce
   (fn [modifiers handler-id]
     (d/deep-merge
      modifiers
      (handler-drag-modifiers
       content handler-types selected-handlers start-modifiers move-delta
       mode move-selection? handler-id)))
   {}
   selected-handlers))

(defn- transient-prev-handler
  "Returns the mirrored transient drawing handler."
  [content [index prefix] handler-mode moving-handler edit-mode prev-handler]
  (when (and (= edit-mode :draw)
             (= prefix :c2)
             (= index (dec (count content)))
             (some? prev-handler)
             (not= handler-mode :independent))
    (let [node (path/handler->node content index prefix)
          mode (if (= handler-mode :mirror) :mirror :aligned)]
      (helpers/opposite-handler-target node moving-handler prev-handler mode))))

(defn modify-selected-handlers
  "Moves selected handlers using each node's handler mode."
  [id primary start-modifiers dx dy mode move-selection?]
  (ptk/reify ::modify-selected-handlers
    ptk/UpdateEvent
    (update [_ state]
      (let [content           (st/get-path state :content)
            handler-types     (dm/get-in state
                                         [:workspace-local :edit-path id :handler-types]
                                         {})
            selected-handlers (active-selected-handlers
                               content primary
                               (dm/get-in state
                                          [:workspace-local :edit-path id :selection :handlers]
                                          #{})
                               move-selection?)
            move-delta        (gpt/point dx dy)
            moved-modifiers   (selected-handler-modifiers
                               content handler-types selected-handlers start-modifiers
                               move-delta mode move-selection?)
            modifiers         (d/deep-merge start-modifiers moved-modifiers)
            [primary-index primary-prefix] primary
            primary-mode      (if move-selection?
                                (stored-handler-drag-mode
                                 content handler-types primary-index primary-prefix)
                                mode)
            primary-handler   (path/get-handler-point content primary-index primary-prefix)
            primary-delta     (gpt/add
                               (handler-modifier-delta start-modifiers
                                                       primary-index
                                                       primary-prefix)
                               move-delta)
            moving-handler    (gpt/add primary-handler primary-delta)
            edit-mode         (dm/get-in state [:workspace-local :edit-path id :edit-mode])
            prev-handler      (dm/get-in state [:workspace-local :edit-path id :prev-handler])
            new-prev-handler  (transient-prev-handler
                               content primary primary-mode moving-handler
                               edit-mode prev-handler)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers)
            (assoc-in [:workspace-local :edit-path id :moving-handler] moving-handler)
            (cond-> (some? new-prev-handler)
              (assoc-in [:workspace-local :edit-path id :prev-handler] new-prev-handler)))))))

(defn- apply-content-modifiers*
  [id new-content]
  (ptk/reify ::apply-content-modifiers*
    ptk/UpdateEvent
    (update [_ state]
      (cond-> (-> state
                  (st/set-content new-content)
                  (update-in [:workspace-local :edit-path id]
                             dissoc
                             :content-modifiers
                             :moving-nodes
                             :moving-handler))
        (seq new-content)
        (update-in (st/get-path-location state) path/update-geometry)))

    ptk/WatchEvent
    (watch [_ _ _]
      ;; Moving modifiers keep node indices stable.
      (when (empty? new-content)
        (rx/of (dwe/clear-edition-mode))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [_ state _]
      (let [id    (st/get-path-id state)
            shape (st/get-path state)

            content-modifiers
            (dm/get-in state [:workspace-local :edit-path id :content-modifiers])]
        (if (or (nil? shape) (nil? content-modifiers))
          (rx/of (dwe/clear-edition-mode))
          (let [content     (get shape :content)
                new-content (path/apply-content-modifiers content content-modifiers)]
            (when (some? new-content)
              (rx/of (apply-content-modifiers* id new-content)))))))))

(def ^:private merge-drop-distance
  "Maximum screen distance for merging dropped nodes."
  10)

(defn merge-dragged-on-drop
  "Merges the closest moved and stationary nodes after a drag."
  []
  (ptk/reify ::merge-dragged-on-drop
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)

            ;; Include endpoints of selected segments.
            moved-indices (into (get selection :nodes #{})
                                (helpers/segment-node-indices content (get selection :segments #{})))
            moved     (helpers/node-positions content moved-indices)
            moved-set (set moved)

            zoom      (dm/get-in state [:workspace-local :zoom] 1)
            threshold (/ merge-drop-distance zoom)
            others    (remove moved-set (path/get-points content))

            pairs     (->> moved
                           (keep (fn [p]
                                   (let [near (filter #(<= (gpt/distance % p) threshold) others)]
                                     (when (seq near)
                                       (let [t (apply min-key #(gpt/distance % p) near)]
                                         [p t (gpt/distance t p)]))))))
            best      (when (seq pairs)
                        (apply min-key #(nth % 2) pairs))]
        (if (some? best)
          (let [[p t _] best]
            (rx/of (tools/process-path-tool #{p t} path/merge-nodes)))
          (rx/empty))))))

(defn modify-content-point
  [content {dx :x dy :y} modifiers point]
  (let [point-indices (path/point-indices content point) ;; [indices]
        handler-indices (path/handler-indices content point) ;; [[index prefix]]

        modify-point
        (fn [modifiers index]
          (-> modifiers
              (update index assoc :x dx :y dy)))

        modify-handler
        (fn [modifiers [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> modifiers
                (update index assoc cx dx cy dy))))]

    (as-> modifiers $
      (reduce modify-point   $ point-indices)
      (reduce modify-handler $ handler-indices))))

(defn set-move-modifier
  "Adds a move delta for selected nodes and handlers."
  [points handler-ids move-modifier]
  (ptk/reify ::set-modifiers
    ptk/UpdateEvent
    (update [_ state]
      (let [id      (st/get-path-id state)
            content (st/get-path state :content)
            {dx :x dy :y} move-modifier

            content-modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})

            content-modifiers
            (->> points
                 (reduce (partial modify-content-point content move-modifier) content-modifiers))

            content-modifiers
            (->> handler-ids
                 (reduce (fn [modifiers [index prefix]]
                           (let [cx (d/prefix-keyword prefix :x)
                                 cy (d/prefix-keyword prefix :y)]
                             (update modifiers index assoc cx dx cy dy)))
                         content-modifiers))]

        (-> state
            (assoc-in [:workspace-local :edit-path id :content-modifiers] content-modifiers))))))

(defn- move-node-indices
  [state node-indices from-point to-point]
  (let [id        (st/get-path-id state)
        content   (st/get-path state :content)
        to-point  (cond-> to-point
                    (:shift? to-point) (path.helpers/position-fixed-angle from-point))
        delta     (gpt/subtract to-point from-point)
        points    (helpers/node-positions content node-indices)
        reducer   (partial modify-content-point content delta)
        modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
        modifiers (reduce reducer modifiers points)]
    (-> state
        (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
        (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers))))

(defn move-selected-path-point [from-point to-point]
  (ptk/reify ::move-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id             (st/get-path-id state)
            selected-nodes (dm/get-in state
                                      [:workspace-local :edit-path id :selection :nodes]
                                      #{})]
        (move-node-indices state selected-nodes from-point to-point)))))

(defn move-selected-path-segment [from-point to-point]
  (ptk/reify ::move-segment
    ptk/UpdateEvent
    (update [_ state]
      (let [id           (st/get-path-id state)
            content      (st/get-path state :content)
            selection    (st/get-selection state id)
            node-indices (helpers/selected-node-indices content selection)]
        (move-node-indices state node-indices from-point to-point)))))

(defn- clear-drag-cursor []
  (ptk/reify ::clear-drag-cursor
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (d/update-in-when state [:workspace-local :edit-path id] dissoc :drag-cursor)))))

(defn set-drag-cursor
  "Shows `cursor` until the current drag stops."
  [cursor]
  (ptk/reify ::set-drag-cursor
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (d/update-in-when state [:workspace-local :edit-path id] assoc :drag-cursor cursor)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> (rx/merge
            (mse/drag-stopper stream)
            (rx/filter streams/finish-edition? stream))
           (rx/take 1)
           (rx/map #(clear-drag-cursor))))))

(declare drag-selected-points)

(def ^:private duplicate-screen-offset 10)

(defn duplicate-offset
  "Returns a duplicate offset that stays constant in screen pixels."
  [zoom]
  (let [step (/ duplicate-screen-offset zoom)]
    (gpt/point step step)))

(defn splice-duplicated
  "Adds duplicate subpaths and selects their new nodes."
  [{:keys [sub selected]}]
  (ptk/reify ::splice-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (if (and (some? id) (seq sub))
          (let [content     (st/get-path state :content)
                base        (count content)
                new-content (path/splice-content content sub)
                pasted      (into #{} (map #(+ base %)) selected)]
            (-> state
                (st/set-content new-content)
                (update-in (st/get-path-location state) path/update-geometry)
                (assoc-in [:workspace-local :edit-path id :selection]
                          (assoc helpers/empty-selection :nodes pasted))))
          state)))))

(defn- duplicate-and-drag
  "Duplicates the selection and drags the copy from `start-position`."
  [start-position]
  (ptk/reify ::duplicate-and-drag
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)
            zoom      (dm/get-in state [:workspace-local :zoom] 1)
            result    (helpers/duplicate-selection-content
                       content selection (duplicate-offset zoom))]
        (if (seq (:sub result))
          (rx/of (splice-duplicated result)
                 (drag-selected-points start-position))
          (rx/of (drag-selected-points start-position)))))))

(declare curve-config-node-drag)

(defn start-move-path-point
  "Handles node clicks and drags in move mode."
  [index shift? alt? mod?]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            selected-nodes (get (st/get-selection state id) :nodes #{})
            selected? (contains? selected-nodes index)
            content   (st/get-path state :content)
            position  (when (and (some? content)
                                 (< index (count content))
                                 (helpers/node? content index))
                        (helpers/node-position content index))]
        (cond
          (and mod? alt?)
          (streams/drag-stream
           (rx/empty)
           (if (some? position)
             (rx/of (tools/remove-node-with-segments index))
             (rx/empty)))

          mod?
          (streams/drag-stream
           (rx/of (set-drag-cursor "move-handles")
                  (curve-config-node-drag index))
           (rx/of (tools/toggle-node-curve index)))

          alt?
          (streams/drag-stream
           (rx/of
            (set-drag-cursor "move-copy")
            (when-not selected? (selection/select-node index false))
            (duplicate-and-drag @ms/mouse-position))
           (if (some? position)
             (rx/of (tools/remove-node position))
             (rx/of (selection/select-node index false))))

          :else
          (streams/drag-stream
           (rx/of
            (set-drag-cursor "move-move")
            (when-not selected? (selection/select-node index shift?))
            (drag-selected-points @ms/mouse-position))
           (rx/of (selection/select-node index shift?))))))))

(defn drag-selected-points
  [start-position]
  (ptk/reify ::drag-selected-points
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (mse/drag-stopper stream)

            id (dm/get-in state [:workspace-local :edition])

            content (st/get-path state :content)

            selected-nodes (get (st/get-selection state id) :nodes #{})
            selected-points (helpers/node-positions content selected-nodes)

            start-position (apply min-key #(gpt/distance start-position %) selected-points)

            points  (path/get-points content)]

        (rx/concat
         ;; This stream checks the consecutive mouse positions to do the dragging
         (->> points
              (streams/move-points-stream start-position selected-points)
              (rx/map #(move-selected-path-point start-position %))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)
                (merge-dragged-on-drop)))))))

(declare drag-selected-segments)
(declare bend-selected-segment)
(declare create-node-at-position)

(defn start-move-path-segment
  "Handles segment clicks and drags in move mode."
  [index shift? alt? mod?]
  (ptk/reify ::start-move-path-segment
    ptk/WatchEvent
    (watch [_ state _]
      (let [id                (st/get-path-id state)
            zoom              (dm/get-in state [:workspace-local :zoom] 1)
            content           (st/get-path state :content)
            selection         (st/get-selection state id)
            selected-segments (get selection :segments #{})
            ;; Both selected endpoints also select their segment for dragging.
            segment-ends      (helpers/segment-node-indices content #{index})
            selected?         (or (contains? selected-segments index)
                                  (and (seq segment-ends)
                                       (every? (get selection :nodes #{}) segment-ends)))
            position          @ms/mouse-position
            threshold         (/ helpers/segment-insert-threshold zoom)]
        (cond
          (and mod? alt?)
          (streams/drag-stream
           (rx/empty)
           (rx/of (tools/remove-segment index)))

          mod?
          (let [entry (d/seek #(= index (:index %)) (helpers/segment-entries content))
                bend? (and (some? entry)
                           (not= :close-path (:command (:segment entry))))]
            (streams/drag-stream
             (rx/of (set-drag-cursor "move-curve")
                    (if bend?
                      (bend-selected-segment index position)
                      (drag-selected-segments position)))
             (rx/of (tools/toggle-segment-curve index))))

          alt?
          (let [insert-point (helpers/insertion-point content position threshold true)]
            (streams/drag-stream
             (rx/of
              (set-drag-cursor "move-copy")
              (when-not selected? (selection/select-segment index false))
              (duplicate-and-drag position))
             (if (some? insert-point)
               (rx/of (create-node-at-position (meta insert-point)))
               (rx/of (selection/select-segment index false)))))

          :else
          (let [insert-point (when-not shift?
                               (helpers/insertion-point content position threshold false))
                click-event  (if (some? insert-point)
                               (create-node-at-position (meta insert-point))
                               (selection/select-segment index shift?))]
            (streams/drag-stream
             (rx/of
              (set-drag-cursor "move-move")
              (when-not selected? (selection/select-segment index shift?))
              (drag-selected-segments position))
             (rx/of click-event))))))))

(defn- segment-entry
  [content index]
  (d/seek #(= index (:index %)) (helpers/segment-entries content)))

(defn drag-selected-segments
  [start-position]
  (ptk/reify ::drag-selected-segments
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper           (mse/drag-stopper stream)
            id                (dm/get-in state [:workspace-local :edition])
            content           (st/get-path state :content)
            selection         (st/get-selection state id)
            node-indices      (helpers/selected-node-indices content selection)
            selected-points   (helpers/node-positions content node-indices)
            points            (path/get-points content)]
        (if (empty? selected-points)
          (rx/empty)
          (rx/concat
           (->> points
                (streams/move-points-stream start-position selected-points)
                (rx/map #(move-selected-path-segment start-position %))
                (rx/take-until stopper))
           (rx/of (apply-content-modifiers)
                  (merge-dragged-on-drop))))))))

(defn bend-segment-modifier
  "Bends segment `index` so its point at `t` reaches `target`."
  [index base-curve t target]
  (ptk/reify ::bend-segment-modifier
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            deltas    (path.helpers/bend-curve-deltas base-curve t target)
            modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})]
        (assoc-in state [:workspace-local :edit-path id :content-modifiers]
                  (assoc modifiers index deltas))))))

(defn bend-selected-segment
  [index start-position]
  (ptk/reify ::bend-selected-segment
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper    (mse/drag-stopper stream)
            content    (st/get-path state :content)
            entry      (segment-entry content index)
            base-curve (path.helpers/entry->bezier entry)
            ;; Keep the grabbed curve parameter fixed during the drag.
            t          (path.helpers/curve-closest-t base-curve start-position 0.001)]
        (rx/concat
         (->> ms/mouse-position
              (rx/filter gpt/point?)
              (rx/map streams/to-pixel-snap)
              (rx/map #(bend-segment-modifier index base-curve t %))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)))))))

(defn- curve-config-modifier
  "Pulls out smooth node handles toward `position`."
  [node in-index in-base in-neighbour out-index out-base out-neighbour position]
  (ptk/reify ::curve-config-modifier
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            v         (gpt/to-vec node position)
            both?     (and (some? in-index) (some? out-index))

            ;; Pick which handle follows the pointer from the drag direction.
            ref       (when (and (some? in-neighbour) (some? out-neighbour))
                        (gpt/subtract (gpt/unit (gpt/to-vec node out-neighbour))
                                      (gpt/unit (gpt/to-vec node in-neighbour))))
            s         (if (and both? (some? ref) (neg? (gpt/dot v ref))) -1 1)

            out-handle (if both? (gpt/add node (gpt/scale v s)) (gpt/add node v))
            in-handle  (if both? (gpt/subtract node (gpt/scale v s)) (gpt/add node v))

            modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
            modifiers (cond-> modifiers
                        (some? in-index)
                        (assoc in-index
                               {:c2x (- (:x in-handle) (:x in-base))
                                :c2y (- (:y in-handle) (:y in-base))})

                        (some? out-index)
                        (assoc out-index
                               {:c1x (- (:x out-handle) (:x out-base))
                                :c1y (- (:y out-handle) (:y out-base))}))]
        (assoc-in state [:workspace-local :edit-path id :content-modifiers] modifiers)))))

(defn curve-config-node-drag
  "Replaces a node's handles with a smooth mirrored pair during a drag."
  [index]
  (ptk/reify ::curve-config-node-drag
    ptk/WatchEvent
    (watch [_ state stream]
      (let [content  (st/get-path state :content)
            node     (when (and (some? content)
                                (< index (count content))
                                (helpers/node? content index))
                       (helpers/node-position content index))
            in-cmd   (nth content index nil)
            out-cmd  (nth content (inc index) nil)
            in?      (contains? #{:line-to :curve-to} (:command in-cmd))
            out?     (contains? #{:line-to :curve-to} (:command out-cmd))
            ;; New curve handles start at the node.
            in-base  (when in?
                       (if (= :curve-to (:command in-cmd))
                         (path/get-handler in-cmd :c2)
                         node))
            out-base (when out?
                       (if (= :curve-to (:command out-cmd))
                         (path/get-handler out-cmd :c1)
                         node))
            ;; Neighbours keep handles on their matching leg.
            in-neighbour  (when in? (helpers/node-position content (dec index)))
            out-neighbour (when out? (helpers/node-position content (inc index)))
            stopper  (rx/merge
                      (mse/drag-stopper stream)
                      (->> stream
                           (rx/filter streams/finish-edition?)))]
        (if (and (some? node) (or in? out?))
          (rx/concat
           (->> ms/mouse-position
                (rx/filter gpt/point?)
                ;; Apply Shift changes without waiting for pointer movement.
                (rx/combine-latest-with ms/keyboard-shift)
                (rx/map (fn [[position shift?]]
                          (assoc position :shift? shift?)))
                (rx/map
                 (fn [{:keys [x y shift?]}]
                   (let [position (cond-> (gpt/point x y)
                                    shift? (path.helpers/position-fixed-angle node))]
                     (curve-config-modifier node
                                            (when in? index)
                                            in-base
                                            in-neighbour
                                            (when out? (inc index))
                                            out-base
                                            out-neighbour
                                            position))))
                (rx/take-until stopper))
           (rx/of (apply-content-modifiers)))
          (rx/empty))))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(defn finish-move-selected []
  (ptk/reify ::finish-move-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [id (dm/get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id] dissoc :current-move))))))

(defn move-selected
  [direction shift?]

  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::move-selected
      IDeref
      (-deref [_] direction)

      ptk/UpdateEvent
      (update [_ state]
        (let [id (dm/get-in state [:workspace-local :edition])
              current-move (dm/get-in state [:workspace-local :edit-path id :current-move])]
          (if (nil? current-move)
            (-> state
                (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
                (assoc-in [:workspace-local :edit-path id :current-move] same-event))
            state)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [id (dm/get-in state [:workspace-local :edition])
              current-move (dm/get-in state [:workspace-local :edit-path id :current-move])]
          ;; id can be null if we just selected the tool but we didn't start drawing
          (if (and id (= same-event current-move))
            (let [content           (st/get-path state :content)
                  selection         (st/get-selection state id)
                  selected-nodes    (get selection :nodes #{})
                  selected-segments (get selection :segments #{})
                  selected-handlers (get selection :handlers #{})

                  ;; Move nodes rigidly and handlers independently.
                  node-indices      (into selected-nodes
                                          (helpers/segment-node-indices content selected-segments))
                  points            (helpers/node-positions content node-indices)
                  handler-ids       (into #{}
                                          (filter (fn [[index _]]
                                                    (= :curve-to (:command (nth content index nil)))))
                                          selected-handlers)

                  move-events (->> stream
                                   (rx/filter (ptk/type? ::move-selected))
                                   (rx/filter #(= direction (deref %))))

                  stopper (->> move-events (rx/debounce 100) (rx/take 1))

                  scale (if shift? (gpt/point 10) (gpt/point 1))

                  mov-vec (gpt/multiply (get-displacement direction) scale)]

              (rx/concat
               (rx/merge
                (->> move-events
                     (rx/take-until stopper)
                     (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                     (rx/map #(set-move-modifier points handler-ids %)))

                ;; First event is not read by the stream so we need to send it again
                (rx/of (move-selected direction shift?)))

               (rx/of (apply-content-modifiers)
                      (finish-move-selected))))
            (rx/empty)))))))

(declare drag-selected-handlers)

(defn- handler-drag-mode
  "Returns the live handler matching mode for a drag."
  [plain-mode mod? alt?]
  (cond
    (and mod? alt?) :aligned
    mod?            :mirror
    alt?            :independent
    :else           plain-mode))

(defn- handler-drag-cursor
  [mod? alt?]
  (if (or mod? alt?) "move-handles" "move-move"))

(defn start-move-handler
  "Handles handler clicks and drags in both edit modes."
  [index prefix shift? alt? mod?]
  (ptk/reify ::start-move-handler
    ptk/WatchEvent
    (watch [_ state _]
      (let [id                (st/get-path-id state)
            handler-id        [index prefix]
            content           (st/get-path state :content)
            selected-handlers (dm/get-in state
                                         [:workspace-local :edit-path id :selection :handlers]
                                         #{})
            selected?         (contains? selected-handlers handler-id)

            handler-types     (dm/get-in state [:workspace-local :edit-path id :handler-types] {})
            plain-mode        (stored-handler-drag-mode
                               content handler-types index prefix)]
        (cond
          (and mod? alt?)
          (streams/drag-stream
           (rx/of (set-drag-cursor (handler-drag-cursor mod? alt?))
                  (drag-selected-handlers handler-id plain-mode))
           (rx/empty))

          (or mod? alt?)
          (streams/drag-stream
           (rx/of (set-drag-cursor (handler-drag-cursor mod? alt?))
                  (drag-selected-handlers handler-id plain-mode))
           (rx/of (tools/remove-handler index prefix)))

          :else
          (streams/drag-stream
           (rx/of
            (set-drag-cursor (handler-drag-cursor mod? alt?))
            (when-not selected?
              (selection/select-handler index prefix shift?))
            (drag-selected-handlers handler-id plain-mode))
           (rx/of (selection/select-handler index prefix shift?))))))))

(defn drag-selected-handlers
  "Drags selected handlers using the live matching mode."
  [[index prefix :as primary] plain-mode]
  (ptk/reify ::drag-selected-handlers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id              (st/get-path-id state)
            content         (st/get-path state :content)
            points          (path/get-points content)
            start-modifiers (dm/get-in state
                                       [:workspace-local :edit-path id :content-modifiers]
                                       {})
            start-delta     (handler-modifier-delta start-modifiers index prefix)
            point           (path/handler->node content index prefix)
            handler         (-> (path/get-handler-point content index prefix)
                                (gpt/add start-delta))
            [op-idx op-prefix] (path/opposite-index content index prefix)
            opposite        (when op-idx
                              (-> (path/get-handler-point content op-idx op-prefix)
                                  (gpt/add (handler-modifier-delta start-modifiers
                                                                   op-idx
                                                                   op-prefix))))
            stopper         (rx/merge
                             (mse/drag-stopper stream)
                             (->> stream
                                  (rx/filter streams/finish-edition?)))

            handler-events  (rx/share
                             (streams/move-handler-stream handler point handler opposite points))]
        (rx/concat
         (rx/merge
          (->> handler-events
               (rx/map
                (fn [{:keys [x y shift? alt? mod?]}]
                  (let [position (cond-> (gpt/point x y)
                                   shift? (path.helpers/position-fixed-angle point))
                        delta    (gpt/subtract position handler)
                        mode     (handler-drag-mode plain-mode mod? alt?)
                        move-selection? (not (or mod? alt?))]
                    (modify-selected-handlers id
                                              primary
                                              start-modifiers
                                              (:x delta)
                                              (:y delta)
                                              mode
                                              move-selection?))))
               (rx/take-until stopper))
          ;; Update the cursor only when the matching mode changes.
          (->> handler-events
               (rx/map (fn [{:keys [alt? mod?]}] (handler-drag-cursor mod? alt?)))
               (rx/pipe (rxo/distinct-contiguous))
               (rx/map set-drag-cursor)
               (rx/take-until stopper)))
         (rx/of (apply-content-modifiers)))))))

(declare stop-path-edit)

(defn- resolve-edit-fills
  "Resolves the fills inherited by the editing copy.
  Frames stop group fill inheritance."
  [shape objects]
  (let [own (svg-fills/resolve-shape-fills shape)]
    (if (seq own)
      own
      (loop [parent-id (:parent-id shape)]
        (let [parent (get objects parent-id)]
          (cond
            (nil? parent)             []
            (cfh/group-shape? parent) (svg-fills/resolve-shape-fills parent)
            (cfh/frame-shape? parent) []
            :else                     (recur (:parent-id parent))))))))

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [objects   (dsh/lookup-page-objects state)
            shape     (get objects id)
            shape     (-> shape
                          (path/convert-to-path objects)
                          (update :content path/close-subpaths)
                          (path/update-geometry))
            shape     (assoc shape :fills (resolve-edit-fills shape objects))]

        (-> state
            (assoc-in [:workspace-drawing :object] shape)
            (update-in [:workspace-local :edit-path id]
                       (fn [state]
                         (let [state (if state
                                       (if (= :move (:edit-mode state))
                                         (assoc state :edit-mode :draw)
                                         state)
                                       {:edit-mode :move
                                        :selection helpers/empty-selection
                                        :hover helpers/empty-selection
                                        :handler-types {}
                                        :snap-toggled true})]
                           (assoc state :old-content (:content shape))))))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::start-path-edit) stream)]
        (rx/concat
         (rx/of (undo/start-path-undo))
         ;; Finalize once on the canonical edition stop event.
         (->> stream
              (rx/filter (ptk/type? ::dwe/clear-edition-mode))
              (rx/take 1)
              (rx/map #(stop-path-edit id))
              (rx/take-until stopper)))))))

(defn stop-path-edit
  [id]
  (ptk/reify ::stop-path-edit
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (changes/finalize-path-content id)
       (fn [state]
         (-> state
             (update-in [:workspace-local :edit-path] dissoc id)
             (update :workspace-drawing dissoc :object :lock)))
       (ptk/data-event :layout/update {:ids [id]})))))

(defn- split-segments
  [_id {:keys [from-p to-p t]}]
  (ptk/reify ::split-segments
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (-> state
            (st/set-content (-> content
                                (path/split-segments #{from-p to-p} t)
                                (path/content)))
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn create-node-at-position
  [params]
  (ptk/reify ::create-node-at-position
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)]
        (rx/of (split-segments id params))))))
