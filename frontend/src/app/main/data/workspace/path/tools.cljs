;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.tools
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.store :as store]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn process-path-tool
  "Runs a position-based path tool and remaps the selection."
  ([tool-fn]
   (process-path-tool nil tool-fn))
  ([points tool-fn]
   (ptk/reify ::process-path-tool
     ptk/UpdateEvent
     (update [_ state]
       (let [shape (st/get-path state)
             id    (st/get-path-id state)

             old-content (:content shape)

             ;; Segment selections include their endpoint nodes.
             selected-nodes
             (helpers/selected-node-indices
              old-content
              (st/get-selection state id))

             points
             (or points (helpers/node-positions old-content selected-nodes))]

         (if (and (seq points) (some? shape))
           (let [new-content
                 (-> (tool-fn old-content points)
                     (path/close-subpaths))]
             (-> (cond-> (st/set-content state new-content)
                   (seq new-content)
                   (update-in (st/get-path-location state) path/update-geometry))
                 (update-in [:workspace-local :edit-path id :selection]
                            #(helpers/remap-selection % old-content new-content))
                 (update-in [:workspace-local :edit-path id :handler-types]
                            #(helpers/remap-handler-types % old-content new-content))))
           state)))

     ptk/WatchEvent
     (watch [_ state _]
       (when (empty? (st/get-path state :content))
         (rx/of (dwe/clear-edition-mode)))))))

(defn make-corner
  ([]
   (make-corner nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (->> points
           (filter #(path/is-curve-point? content %))
           (reduce path/make-corner-point content))))))

(defn make-curve
  ([]
   (make-curve nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (->> points
           (remove #(path/is-curve-point? content %))
           (reduce path/make-curve-point content))))))

(defn- apply-handler-type-modifiers
  "Returns modifiers that reshape a node's handlers to `type`."
  [content node-index type]
  (if-let [[idx prefix] (helpers/node-primary-handler content node-index)]
    (case type
      :mirror  (helpers/move-handler-modifiers content idx prefix true true true 0 0)
      :aligned (helpers/align-handler-modifiers content idx prefix 0 0)
      {})
    {}))

(defn set-handler-type
  "Sets and stores the handler behavior of selected nodes."
  [type]
  (ptk/reify ::set-handler-type
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)
            nodes     (helpers/handler-target-nodes content selection)]
        (if (and (some? content) (seq nodes))
          (let [modifiers   (reduce (fn [acc node-index]
                                      (d/deep-merge acc (apply-handler-type-modifiers content node-index type)))
                                    {} nodes)
                new-content (path/apply-content-modifiers content modifiers)]
            (-> (st/set-content state new-content)
                (update-in (st/get-path-location state) path/update-geometry)
                (update-in [:workspace-local :edit-path id :handler-types]
                           (fn [ht] (reduce #(assoc %1 %2 type) (or ht {}) nodes)))))
          state)))))

(defn add-node []
  (process-path-tool (fn [content points] (path/split-segments content points 0.5))))

(defn remove-node
  "Removes nodes and heals the gap with a fitted curve."
  ([]
   (process-path-tool path/remove-nodes))
  ([point]
   (process-path-tool #{point} path/remove-nodes)))

(defn toggle-node-curve
  "Toggles a node between a corner and a curve."
  [index]
  (ptk/reify ::toggle-node-curve
    ptk/WatchEvent
    (watch [_ state _]
      (let [content (st/get-path state :content)]
        (when (and (some? content)
                   (< index (count content))
                   (helpers/node? content index))
          (let [point (helpers/node-position content index)]
            (rx/of (if (path/is-curve-point? content point)
                     (make-corner point)
                     (make-curve point)))))))))

(defn- update-path-content
  "Updates path content, geometry, selection, and handler types."
  [state new-content]
  (let [id          (st/get-path-id state)
        old-content (st/get-path state :content)]
    (-> (cond-> (st/set-content state new-content)
          (seq new-content)
          (update-in (st/get-path-location state) path/update-geometry))
        (update-in [:workspace-local :edit-path id :selection]
                   #(helpers/remap-selection % old-content new-content))
        (update-in [:workspace-local :edit-path id :handler-types]
                   #(helpers/remap-handler-types % old-content new-content)))))

(defn remove-segments
  "Removes segments and opens the path at their endpoints."
  [indices]
  (ptk/reify ::remove-segments
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (if (and (some? content) (seq indices))
          (update-path-content state (path/remove-segments content indices))
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (when (empty? (st/get-path state :content))
        (rx/of (dwe/clear-edition-mode))))))

(defn remove-segment
  [index]
  (remove-segments #{index}))

(defn remove-node-with-segments
  "Removes a node and its incident segments without healing the gap."
  [index]
  (ptk/reify ::remove-node-with-segments
    ptk/WatchEvent
    (watch [_ state _]
      (let [content  (st/get-path state :content)
            incident (into #{}
                           (comp (filter #(or (= index (:to-index %))
                                              (= index (:from-index %))))
                                 (map :index))
                           (helpers/segment-entries content))]
        (when (seq incident)
          (rx/of (remove-segments incident)))))))

(defn delete-selected-with-segments
  "Removes selected nodes and their incident segments without healing."
  []
  (ptk/reify ::delete-selected-with-segments
    ptk/WatchEvent
    (watch [_ state _]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (helpers/selected-node-indices
                      content
                      (st/get-selection state id))
            incident (into #{}
                           (comp (filter #(or (contains? selected (:to-index %))
                                              (contains? selected (:from-index %))))
                                 (map :index))
                           (helpers/segment-entries content))]
        (when (seq incident)
          (rx/of (remove-segments incident)))))))

(defn toggle-segment-curve
  "Toggles a segment between a line and a curve."
  [index]
  (ptk/reify ::toggle-segment-curve
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (if (some? content)
          (update-path-content state (path/toggle-segment-curve content index))
          state)))))

(defn remove-handler
  "Collapses one handler onto its node."
  [index prefix]
  (ptk/reify ::remove-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (if (some? content)
          (update-path-content state (path/collapse-handler content index prefix))
          state)))))

(defn merge-nodes []
  (process-path-tool path/merge-nodes))

(defn join-nodes []
  (process-path-tool path/join-nodes))

(def ^:private separate-node-screen-offset
  "Screen offset between separated node ends."
  8)

(defn separate-nodes []
  ;; Keep the visible gap stable across zoom levels.
  (let [zoom   (get-in @store/state [:workspace-local :zoom] 1)
        step   (/ separate-node-screen-offset zoom)
        offset (gpt/point step step)]
    (process-path-tool
     (fn [content points]
       (path/separate-nodes content points offset)))))

(defn delete-selected
  "Heals selected nodes or opens selected segments."
  []
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)
            nodes     (get selection :nodes #{})
            segments  (get selection :segments #{})]
        (rx/of
         (cond
           ;; Node selection takes priority in mixed selections.
           (seq nodes)
           (process-path-tool (helpers/node-positions content nodes) path/remove-nodes)

           ;; Segment-only selection opens the path.
           (seq segments)
           (separate-nodes)

           :else
           (remove-node)))))))

(defn flip-nodes
  "Flips selected nodes, or the whole path when none are selected."
  [axis]
  (ptk/reify ::flip-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (helpers/selected-node-indices
                      content
                      (st/get-selection state id))
            indices  (if (seq selected)
                       selected
                       (helpers/node-indices content))
            content  (path/flip-content content indices axis)]
        (-> (st/set-content state content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn align-nodes
  "Aligns selected nodes and their handles within their bounds."
  [axis]
  (ptk/reify ::align-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (get (st/get-selection state id) :nodes #{})
            content  (path/align-content content selected axis)]
        (-> (st/set-content state content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn distribute-nodes
  "Distributes selected nodes evenly along `axis`."
  [axis]
  (ptk/reify ::distribute-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (get (st/get-selection state id) :nodes #{})
            content  (path/distribute-content content selected axis)]
        (-> (st/set-content state content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn- axis-point
  "Copy of `p` with `axis` (`:x`/`:y`) replaced by `value`."
  [p axis value]
  (if (= axis :x) (gpt/point value (:y p)) (gpt/point (:x p) value)))

(defn- handler-target-points
  "Returns handler targets for an absolute coordinate edit."
  [content handlers handler-types axis value]
  (reduce
   (fn [pts [index prefix]]
     (let [hp   (path/get-handler-point content index prefix)
           hp'  (axis-point hp axis value)
           node-index (helpers/handler-node-index index prefix)
           mode (or (get handler-types node-index)
                    (helpers/derive-handler-type content node-index))
           [op-idx op-prefix] (path/opposite-index content index prefix)
           pts  (assoc pts [index prefix] hp')]
       (if (and (contains? #{:mirror :aligned} mode) (some? op-idx))
         (let [node (path/handler->node content index prefix)
               opp  (path/get-handler-point content op-idx op-prefix)
               opp' (helpers/opposite-handler-target node hp' opp mode)]
           (assoc pts [op-idx op-prefix] opp'))
         pts)))
   {}
   handlers))

(defn- translated-handler-target-points
  "Returns standalone handler targets for a group translation."
  [content handlers node-indices delta]
  (into {}
        (comp
         (remove (fn [[index prefix]]
                   (contains? node-indices
                              (helpers/handler-node-index index prefix))))
         (keep (fn [[index prefix :as identity]]
                 (when-let [point (path/get-handler-point content index prefix)]
                   [identity (gpt/add point delta)]))))
        handlers))

(defn set-selection-coordinate
  "Sets one coordinate of the current path selection."
  [axis value]
  (ptk/reify ::set-selection-coordinate
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)
            htypes    (dm/get-in state [:workspace-local :edit-path id :handler-types])
            segments  (get selection :segments #{})
            handlers  (get selection :handlers #{})
            node-idx  (helpers/selected-node-indices content selection)

            new-content
            (if (seq segments)
              ;; Translate segment selections as one group.
              (let [rect     (helpers/selection-coordinate-rect content selection)
                    cur      (if (= axis :x) (dm/get-prop rect :x) (dm/get-prop rect :y))
                    delta    (axis-point (gpt/point 0 0) axis (- value cur))
                    htargets (translated-handler-target-points
                              content handlers node-idx delta)]
                (cond-> (path/translate-selected-nodes content node-idx delta)
                  (seq htargets) (path/set-handler-points htargets)))
              ;; Set node and handler coordinates directly.
              (let [pts (handler-target-points content handlers htypes axis value)]
                (cond-> content
                  (seq node-idx) (path/set-nodes-coordinate node-idx axis value)
                  (seq pts)      (path/set-handler-points pts))))]
        (-> (st/set-content state new-content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))
