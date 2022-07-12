;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.selection
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.page :as ctp]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as md]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.refs :as refs]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [app.util.names :as un]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [linked.set :as lks]
   [potok.core :as ptk]))

(s/def ::set-of-uuid
  (s/every uuid? :kind set?))

(s/def ::ordered-set-of-uuid
  (s/every uuid? :kind d/ordered-set?))

(s/def ::set-of-string
  (s/every string? :kind set?))

(defn interrupt? [e] (= e :interrupt))

;; --- Selection Rect

(declare select-shapes-by-current-selrect)
(declare deselect-all)

(defn update-selrect
  [selrect]
  (ptk/reify ::update-selrect
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selrect] selrect))))

(defn handle-area-selection
  [preserve? ignore-groups?]
  (ptk/reify ::handle-area-selection
    ptk/WatchEvent
    (watch [_ state stream]
      (let [zoom (get-in state [:workspace-local :zoom] 1)
            stop? (fn [event] (or (interrupt? event) (ms/mouse-up? event)))
            stoper (->> stream (rx/filter stop?))

            init-selrect
            {:type :rect
             :x1 (:x @ms/mouse-position)
             :y1 (:y @ms/mouse-position)
             :x2 (:x @ms/mouse-position)
             :y2 (:y @ms/mouse-position)}

            calculate-selrect
            (fn [selrect [delta space?]]
              (let [result
                    (cond-> selrect
                      :always
                      (-> (update :x2 + (:x delta))
                          (update :y2 + (:y delta)))

                      space?
                      (-> (update :x1 + (:x delta))
                          (update :y1 + (:y delta))))]
                (assoc result
                       :x (min (:x1 result) (:x2 result))
                       :y (min (:y1 result) (:y2 result))
                       :width (mth/abs (- (:x2 result) (:x1 result)))
                       :height (mth/abs (- (:y2 result) (:y1 result))))))

            selrect-stream
            (->> ms/mouse-position
                 (rx/buffer 2 1)
                 (rx/map (fn [[from to]] (when (and from to) (gpt/to-vec from to))))
                 (rx/filter some?)
                 (rx/with-latest-from ms/keyboard-space)
                 (rx/scan calculate-selrect init-selrect)
                 (rx/filter #(or (> (:width %) (/ 10 zoom))
                                 (> (:height %) (/ 10 zoom))))
                 (rx/take-until stoper))]
        (rx/concat
         (if preserve?
           (rx/empty)
           (rx/of (deselect-all)))

         (rx/merge
          (->> selrect-stream
               (rx/map update-selrect))

          (->> selrect-stream
               (rx/buffer-time 100)
               (rx/map #(last %))
               (rx/dedupe)
               (rx/map #(select-shapes-by-current-selrect preserve? ignore-groups?))))

         (->> (rx/of (update-selrect nil))
              ;; We need the async so the current event finishes before updating the selrect
              ;; otherwise the `on-click` event will trigger with a `nil` selrect
              (rx/observe-on :async)))))))

;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  ([id]
   (select-shape id false))

  ([id toggle?]
   (us/verify ::us/uuid id)
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (update-in state [:workspace-local :selected] d/toggle-selection id toggle?))

     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)]
         (rx/of (dwc/expand-all-parents [id] objects)))))))

(defn deselect-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::deselect-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :selected] disj id))))

(defn shift-select-shapes
  ([id]
   (ptk/reify ::shift-select-shapes
     ptk/UpdateEvent
     (update [_ state]
       (let [page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)
             selection (-> state
                           wsh/lookup-selected
                           (conj id))]
         (-> state
             (assoc-in [:workspace-local :selected]
                       (cph/expand-region-selection objects selection))))))))

(defn select-shapes
  [ids]
  (us/verify ::ordered-set-of-uuid ids)
  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)
            focus (:workspace-focus-selected state)
            ids (if (d/not-empty? focus)
                  (cp/filter-not-focus objects focus ids)
                  ids)]
        (assoc-in state [:workspace-local :selected] ids)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)]
        (rx/of (dwc/expand-all-parents ids objects))))))

(defn select-all
  []
  (ptk/reify ::select-all
    ptk/WatchEvent
    (watch [_ state _]
      (let [focus (:workspace-focus-selected state)
            objects  (-> (wsh/lookup-page-objects state)
                         (cp/focus-objects focus))

            selected (let [frame-ids (into #{} (comp
                                                (map (d/getf objects))
                                                (map :frame-id))
                                           (wsh/lookup-selected state))
                           frame-id  (if (= 1 (count frame-ids))
                                       (first frame-ids)
                                       uuid/zero)]
                       (cph/get-immediate-children objects frame-id))

            selected (into (d/ordered-set)
                           (comp (remove :blocked) (map :id))
                           selected)]

        (rx/of (select-shapes selected))))))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user.
  When `check-modal` the method will check if a modal is opened
  and not deselect if it's true"
  ([] (deselect-all false))

  ([check-modal]
   (ptk/reify ::deselect-all
     ptk/UpdateEvent
     (update [_ state]

       ;; Only deselect if there is no modal openned
       (cond-> state
         (or (not check-modal)
             (not (::md/modal state)))
         (update :workspace-local
                 #(-> %
                      (assoc :selected (d/ordered-set))
                      (dissoc :selected-frame))))))))

;; --- Select Shapes (By selrect)

(defn select-shapes-by-current-selrect
  [preserve? ignore-groups?]
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state)
            initial-set (if preserve?
                          selected
                          lks/empty-linked-set)
            selrect (get-in state [:workspace-local :selrect])
            blocked? (fn [id] (get-in objects [id :blocked] false))]
        (when selrect
          (rx/empty)
          (->> (uw/ask-buffered!
                {:cmd :selection/query
                 :page-id page-id
                 :rect selrect
                 :include-frames? true
                 :ignore-groups? ignore-groups?
                 :full-frame? true})
               (rx/map #(cph/clean-loops objects %))
               (rx/map #(into initial-set (filter (comp not blocked?)) %))
               (rx/map select-shapes)))))))

(defn select-inside-group
  [group-id position]

  (ptk/reify ::select-inside-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            group    (get objects group-id)
            children (map #(get objects %) (:shapes group))

            ;; We need to reverse the children because if two children
            ;; overlap we want to select the one that's over (and it's
            ;; in the later vector position
            selected (->> children
                          reverse
                          (d/seek #(gsh/has-point? % position)))]
        (when selected
          (rx/of (select-shape (:id selected))))))))


;; --- Duplicate Shapes
(declare prepare-duplicate-change)
(declare prepare-duplicate-frame-change)
(declare prepare-duplicate-shape-change)
(declare prepare-duplicate-flows)
(declare prepare-duplicate-guides)

(defn prepare-duplicate-changes
  "Prepare objects to duplicate: generate new id, give them unique names,
  move to the desired position, and recalculate parents and frames as needed."
  [all-objects page ids delta it]
  (let [shapes         (map (d/getf all-objects) ids)
        unames         (volatile! (un/retrieve-used-names (:objects page)))
        update-unames! (fn [new-name] (vswap! unames conj new-name))
        all-ids        (reduce #(into %1 (cons %2 (cph/get-children-ids all-objects %2))) (d/ordered-set) ids)
        ids-map        (into {} (map #(vector % (uuid/next))) all-ids)

        init-changes
        (-> (pcb/empty-changes it)
            (pcb/with-page page)
            (pcb/with-objects all-objects))

        changes
        (->> shapes
             (reduce #(prepare-duplicate-change %1
                                                all-objects
                                                page
                                                unames
                                                update-unames!
                                                ids-map
                                                %2
                                                delta)
                     init-changes))]

    (-> changes
        (prepare-duplicate-flows shapes page ids-map)
        (prepare-duplicate-guides shapes page ids-map delta))))

(defn- prepare-duplicate-change
  [changes objects page unames update-unames! ids-map shape delta]
  (if (cph/frame-shape? shape)
    (prepare-duplicate-frame-change changes objects page unames update-unames! ids-map shape delta)
    (prepare-duplicate-shape-change changes objects page unames update-unames! ids-map shape delta (:frame-id shape) (:parent-id shape))))

(defn- prepare-duplicate-frame-change
  [changes objects page unames update-unames! ids-map obj delta]
  (let [new-id     (ids-map (:id obj))
        frame-name (un/generate-unique-name @unames (:name obj))
        _          (update-unames! frame-name)

        new-frame  (-> obj
                       (assoc :id new-id
                              :name frame-name
                              :frame-id uuid/zero
                              :shapes [])
                       (dissoc :use-for-thumbnail?)
                       (gsh/move delta)
                       (d/update-when :interactions #(ctsi/remap-interactions % ids-map objects)))

        changes (-> (pcb/add-object changes new-frame)
                    (pcb/amend-last-change #(assoc % :old-id (:id obj))))

        changes (reduce (fn [changes child]
                          (prepare-duplicate-shape-change changes
                                                          objects
                                                          page
                                                          unames
                                                          update-unames!
                                                          ids-map
                                                          child
                                                          delta
                                                          new-id
                                                          new-id))
                        changes
                        (map (d/getf objects) (:shapes obj)))]
    changes))

(defn- prepare-duplicate-shape-change
  [changes objects page unames update-unames! ids-map obj delta frame-id parent-id]
  (if (some? obj)
    (let [new-id      (ids-map (:id obj))
          parent-id   (or parent-id frame-id)
          name        (un/generate-unique-name @unames (:name obj))
          _           (update-unames! name)

          new-obj     (-> obj
                          (assoc :id new-id
                                 :name name
                                 :parent-id parent-id
                                 :frame-id frame-id)
                          (dissoc :shapes)
                          (gsh/move delta)
                          (d/update-when :interactions #(ctsi/remap-interactions % ids-map objects)))

          changes (-> (pcb/add-object changes new-obj {:ignore-touched true})
                      (pcb/amend-last-change #(assoc % :old-id (:id obj))))]

      (reduce (fn [changes child]
                (prepare-duplicate-shape-change changes
                                                objects
                                                page
                                                unames
                                                update-unames!
                                                ids-map
                                                child
                                                delta
                                                frame-id
                                                new-id))
              changes
              (map (d/getf objects) (:shapes obj))))
    changes))

(defn- prepare-duplicate-flows
  [changes shapes page ids-map]
  (let [flows            (-> page :options :flows)
        unames           (volatile! (into #{} (map :name flows)))
        frames-with-flow (->> shapes
                              (filter #(= (:type %) :frame))
                              (filter #(some? (ctp/get-frame-flow flows (:id %)))))]
    (if-not (empty? frames-with-flow)
      (let [update-flows (fn [flows]
                           (reduce
                             (fn [flows frame]
                               (let [name     (un/generate-unique-name @unames "Flow-1")
                                     _        (vswap! unames conj name)
                                     new-flow {:id (uuid/next)
                                               :name name
                                               :starting-frame (get ids-map (:id frame))}]
                                 (ctp/add-flow flows new-flow)))
                             flows
                             frames-with-flow))]
        (pcb/update-page-option changes :flows update-flows))
      changes)))

(defn- prepare-duplicate-guides
  [changes shapes page ids-map delta]
  (let [guides (get-in page [:options :guides])
        frames (->> shapes
                    (filter #(= (:type %) :frame)))
        new-guides (reduce
                    (fn [g frame]
                      (let [new-id     (ids-map (:id frame))
                            new-frame  (-> frame
                                           (gsh/move delta))
                            new-guides (->> guides
                                            (vals)
                                            (filter #(= (:frame-id %) (:id frame)))
                                            (map #(-> %
                                                      (assoc :id (uuid/next))
                                                      (assoc :frame-id new-id)
                                                      (assoc :position (if (= (:axis %) :x)
                                                                         (+ (:position %) (- (:x new-frame) (:x frame)))
                                                                         (+ (:position %) (- (:y new-frame) (:y frame))))))))]

                        (if-not (empty? new-guides)
                          (conj g
                                (into {} (map (juxt :id identity) new-guides)))
                          {})))
                    guides
                    frames)]
    (-> (pcb/with-page changes page)
        (pcb/set-page-option :guides new-guides))))

(defn duplicate-changes-update-indices
  "Updates the changes to correctly set the indexes of the duplicated objects,
  depending on the index of the original object respect their parent."
  [objects ids changes]
  (let [;; index-map is a map that goes from parent-id => vector([id index-in-parent])
        index-map (reduce (fn [index-map id]
                            (let [parent-id    (get-in objects [id :parent-id])
                                  parent-index (cph/get-position-on-parent objects id)]
                              (update index-map parent-id (fnil conj []) [id parent-index])))
                          {}
                          ids)

        inc-indices
        (fn [[offset result] [id index]]
          [(inc offset) (conj result [id (+ index offset)])])

        fix-indices
        (fn [_ entry]
          (->> entry
               (sort-by second)
               (reduce inc-indices [1 []])
               (second)
               (into {})))

        objects-indices (->> index-map (d/mapm fix-indices) (vals) (reduce merge))]

    (pcb/amend-changes
      changes
      (fn [change]
        (assoc change :index (get objects-indices (:old-id change)))))))

(defn clear-memorize-duplicated
  []
  (ptk/reify ::clear-memorize-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-local :duplicated]))))

(defn memorize-duplicated
  "When duplicate an object, remember the operation during the following seconds.
  If the user moves the duplicated object, and then duplicates it again, check
  the displacement and apply it to the third copy. This is useful for doing
  grids or cascades of cloned objects."
  [id-original id-duplicated]
  (ptk/reify ::memorize-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :duplicated] {:id-original id-original
                                                      :id-duplicated id-duplicated}))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stoper (rx/filter (ptk/type? ::memorize-duplicated) stream)]
        (->> (rx/timer 10000) ;; This time may be adjusted after some user testing.
             (rx/take-until stoper)
             (rx/map clear-memorize-duplicated))))))

(defn calc-duplicate-delta
  [obj state objects]
  (let [{:keys [id-original id-duplicated]}
        (get-in state [:workspace-local :duplicated])]
    (if (and (not= id-original (:id obj))
             (not= id-duplicated (:id obj)))

      ;; The default is leave normal shapes in place, but put
      ;; new frames to the right of the original.
      (if (cph/frame-shape? obj)
        (gpt/point (+ (:width obj) 50) 0)
        (gpt/point 0 0))

      (let [pt-original   (-> (get objects id-original) :selrect gpt/point)
            pt-duplicated (-> (get objects id-duplicated) :selrect gpt/point)
            pt-obj        (-> obj :selrect gpt/point)
            distance       (gpt/subtract pt-duplicated pt-original)
            new-pos        (gpt/add pt-duplicated distance)]

        (gpt/subtract new-pos pt-obj)))))

(defn duplicate-selected [move-delta?]
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [it state _]
      (when (or (not move-delta?) (nil? (get-in state [:workspace-local :transform])))
        (let [page     (wsh/lookup-page state)
              objects  (:objects page)
              selected (wsh/lookup-selected state)]
          (when (seq selected)
            (let [obj             (get objects (first selected))
                  delta           (if move-delta?
                                    (calc-duplicate-delta obj state objects)
                                    (gpt/point 0 0))

                  changes         (->> (prepare-duplicate-changes objects page selected delta it)
                                       (duplicate-changes-update-indices objects selected))

                  id-original     (first selected)

                  new-selected    (->> changes
                                       :redo-changes
                                       (filter #(= (:type %) :add-obj))
                                       (filter #(selected (:old-id %)))
                                       (map #(get-in % [:obj :id]))
                                       (into (d/ordered-set)))

                  dup-frames      (->> changes
                                       :redo-changes
                                       (filter #(= (:type %) :add-obj))
                                       (filter #(selected (:old-id %)))
                                       (filter #(= :frame (get-in % [:obj :type])))
                                       (map #(vector (:old-id %) (get-in % [:obj :id]))))

                  id-duplicated   (first new-selected)]

              (rx/concat
               (->> (rx/from dup-frames)
                    (rx/map (fn [[old-id new-id]] (dwt/duplicate-thumbnail old-id new-id))))

               ;; Warning: This order is important for the focus mode.
               (rx/of (dch/commit-changes changes)
                      (select-shapes new-selected)
                      (memorize-duplicated id-original id-duplicated))))))))))

(defn change-hover-state
  [id value]
  (ptk/reify ::change-hover-state
    ptk/UpdateEvent
    (update [_ state]
      (let [hover-value (if value #{id} #{})]
        (assoc-in state [:workspace-local :hover] hover-value)))))

(defn update-focus-shapes
  [added removed]
  (ptk/reify ::update-focus-shapes
    ptk/UpdateEvent
    (update [_ state]

      (let [objects (wsh/lookup-page-objects state)

            focus (-> (:workspace-focus-selected state)
                      (set/union added)
                      (set/difference removed))
            focus (cph/clean-loops objects focus)]

        (-> state
            (assoc :workspace-focus-selected focus))))))

(defn toggle-focus-mode
  []
  (ptk/reify ::toggle-focus-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (wsh/lookup-selected state)]
        (cond-> state
          (and (empty? (:workspace-focus-selected state))
               (d/not-empty? selected))
          (assoc :workspace-focus-selected selected)

          (d/not-empty? (:workspace-focus-selected state))
          (dissoc :workspace-focus-selected))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter #(or (= ::toggle-focus-mode (ptk/type %))
                                    (= :app.main.data.workspace/finalize-page (ptk/type %))) stream)]
        (when (d/not-empty? (:workspace-focus-selected state))
          (rx/merge
           (rx/of dwz/zoom-to-selected-shape
                  (deselect-all))
           (->> (rx/from-atom refs/workspace-page-objects {:emit-current-value? true})
                (rx/take-until stopper)
                (rx/map (comp set keys))
                (rx/buffer 2 1)
                (rx/merge-map
                 (fn [[old-keys new-keys]]
                   (let [removed (set/difference old-keys new-keys)
                         added (set/difference new-keys old-keys)]

                     (if (or (d/not-empty? added) (d/not-empty? removed))
                       (rx/of (update-focus-shapes added removed))
                       (rx/empty))))))))))))
