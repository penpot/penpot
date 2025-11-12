;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.selection
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.focus :as cpf]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as md]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.specialized-panel :as-alias dwsp]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.streams :as ms]
   [app.main.worker :as mw]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [clojure.set :as set]
   [linked.set :as lks]
   [potok.v2.core :as ptk]))

(defn interrupt?
  [e]
  (= e :interrupt))

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
  [append? remove? ignore-groups?]
  (ptk/reify ::handle-area-selection
    ptk/WatchEvent
    (watch [_ state stream]
      (let [zoom          (dm/get-in state [:workspace-local :zoom] 1)
            stopper       (mse/drag-stopper stream)
            init-position @ms/mouse-position

            initial-set   (if (or append? remove?)
                            (dsh/lookup-selected state)
                            lks/empty-linked-set)

            init-selrect  (grc/make-rect
                           (dm/get-prop init-position :x)
                           (dm/get-prop init-position :y)
                           0 0)

            calculate-selrect
            (fn [selrect [delta space?]]
              (let [selrect (-> selrect
                                (update :x2 + (:x delta))
                                (update :y2 + (:y delta)))
                    selrect (if ^boolean space?
                              (-> selrect
                                  (update :x1 + (:x delta))
                                  (update :y1 + (:y delta)))
                              selrect)]
                (grc/update-rect selrect :corners)))

            selrect-stream
            (->> ms/mouse-position
                 (rx/buffer 2 1)
                 (rx/map (fn [[from to]] (when (and from to) (gpt/to-vec from to))))
                 (rx/filter some?)
                 (rx/with-latest-from ms/keyboard-space)
                 (rx/scan calculate-selrect init-selrect)
                 (rx/filter #(or (> (dm/get-prop % :width) (/ 10 zoom))
                                 (> (dm/get-prop % :height) (/ 10 zoom))))
                 (rx/take-until stopper))]

        (rx/concat
         (if (or append? remove?)
           (rx/empty)
           (rx/of (deselect-all)))

         (rx/merge
          (->> selrect-stream
               (rx/map update-selrect))

          (->> selrect-stream
               (rx/buffer-time 100)
               (rx/map last)
               (rx/pipe (rxo/distinct-contiguous))
               (rx/map #(select-shapes-by-current-selrect initial-set remove? ignore-groups?)))

          ;; The last "tick" from the mouse cannot be buffered so we are sure
          ;; a selection is returned. Without this we can have empty selections on
          ;; very fast movement
          (->> selrect-stream
               (rx/last)
               (rx/map #(select-shapes-by-current-selrect initial-set remove? ignore-groups? false))))

         (->> (rx/of (update-selrect nil))
              ;; We need the async so the current event finishes before updating the selrect
              ;; otherwise the `on-click` event will trigger with a `nil` selrect
              (rx/observe-on :async)))))))

;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  ([id]
   (select-shape id false))

  ([id toggle?]
   (dm/assert! (uuid? id))
   (ptk/reify ::select-shape
     ev/PerformanceEvent

     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (update-in [:workspace-local :selected] d/toggle-selection id toggle?)
           (assoc-in [:workspace-local :last-selected] id)))

     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id              (:current-page-id state)
             objects              (dsh/lookup-page-objects state page-id)
             selected-id          (dsh/lookup-selected state)
             selected             (dsh/lookup-shapes state selected-id)
             frame-ids            (map (fn [item] (let [parent (cfh/get-frame objects (:id item))]
                                                    (:id parent))) selected)
             params-without-board (-> (rt/get-params state)
                                      (dissoc :board-id))
             params-board         (-> (rt/get-params state)
                                      (assoc :board-id frame-ids))]

         (rx/of (dwc/expand-all-parents [id] objects)
                :interrupt
                ::dwsp/interrupt
                (if (some #(= % uuid/zero) frame-ids)
                  (rt/nav :workspace params-without-board {::rt/replace true})
                  (rt/nav :workspace params-board {::rt/replace true}))))))))

(defn select-prev-shape
  ([]
   (ptk/reify ::select-prev-shape
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected       (dsh/lookup-selected state)
             count-selected (count selected)
             first-selected (first selected)
             page-id        (:current-page-id state)
             objects        (dsh/lookup-page-objects state page-id)
             current        (get objects first-selected)
             parent         (get objects (:parent-id current))
             sibling-ids    (:shapes parent)
             current-index  (d/index-of sibling-ids first-selected)
             sibling        (if (= (dec (count sibling-ids)) current-index)
                              (first sibling-ids)
                              (nth sibling-ids (inc current-index)))]

         (cond
           (= 1 count-selected)
           (rx/of (select-shape sibling))

           (> count-selected 1)
           (rx/of (select-shape first-selected))))))))

(defn select-next-shape
  ([]
   (ptk/reify ::select-next-shape
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected       (dsh/lookup-selected state)
             count-selected (count selected)
             first-selected (first selected)
             page-id        (:current-page-id state)
             objects        (dsh/lookup-page-objects state page-id)
             current        (get objects first-selected)
             parent         (get objects (:parent-id current))
             sibling-ids    (:shapes parent)
             current-index  (d/index-of sibling-ids first-selected)
             sibling        (if (= 0 current-index)
                              (last sibling-ids)
                              (nth sibling-ids (dec current-index)))]
         (cond
           (= 1 count-selected)
           (rx/of (select-shape sibling))

           (> count-selected 1)
           (rx/of (select-shape first-selected))))))))

(defn deselect-shape
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::deselect-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of ::dwsp/interrupt))
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-local :selected] disj id)
          (update :workspace-local dissoc :last-selected)))))

(defn shift-select-shapes
  ([id]
   (shift-select-shapes id nil))

  ([id objects]
   (ptk/reify ::shift-select-shapes
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of ::dwsp/interrupt))
     ptk/UpdateEvent
     (update [_ state]
       (let [objects (or objects (dsh/lookup-page-objects state))
             append-to-selection (cfh/expand-region-selection objects (into #{} [(get-in state [:workspace-local :last-selected]) id]))
             selection (-> state
                           dsh/lookup-selected
                           (conj id))]
         (-> state
             (assoc-in [:workspace-local :selected]
                       (set/union selection append-to-selection))
             (update :workspace-local assoc :last-selected id)))))))

(defn select-shapes
  [ids]
  (dm/assert!
   "expected valid coll of uuids"
   (and (every? uuid? ids)
        (d/ordered-set? ids)))

  (ptk/reify ::select-shapes
    ev/PerformanceEvent

    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dsh/lookup-page-objects state)
            focus (:workspace-focus-selected state)
            ids (if (d/not-empty? focus)
                  (cpf/filter-not-focus objects focus ids)
                  ids)]
        (assoc-in state [:workspace-local :selected] ids)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)]
        (rx/of
         (dwc/expand-all-parents ids objects)
         ::dwsp/interrupt)))))

(defn select-all
  []
  (ptk/reify ::select-all
    ev/PerformanceEvent

    ptk/WatchEvent
    (watch [_ state _]
      (let [;; Make the select-all aware of the focus mode; in this
            ;; case delimit the objects to the focused shapes if focus
            ;; mode is active
            focus    (:workspace-focus-selected state)
            objects  (-> (dsh/lookup-page-objects state)
                         (cpf/focus-objects focus))

            lookup   (d/getf objects)
            parents  (->> (dsh/lookup-selected state)
                          (into #{} (comp (keep lookup) (map :parent-id))))

            ;; If we have a only unique parent, then use it as main
            ;; anchor for the selection; if not, use the root frame as
            ;; parent
            parent   (if (= 1 (count parents))
                       (-> parents first lookup)
                       (lookup uuid/zero))

            toselect (->> (cfh/get-immediate-children objects (:id parent))
                          (into (d/ordered-set) (comp (remove :hidden) (remove :blocked) (map :id))))]

        (rx/of (select-shapes toselect))))))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user.
  When `check-modal` the method will check if a modal is opened
  and not deselect if it's true"
  ([] (deselect-all false))

  ([check-modal]
   (ptk/reify ::deselect-all
     ptk/WatchEvent
     (watch [_ state _]
       (let [params-without-board (-> (rt/get-params state)
                                      (dissoc :board-id))]
         (rx/of ::dwsp/interrupt
                (dwe/clear-edition-mode)
                (rt/nav :workspace params-without-board {::rt/replace true}))))

     ptk/UpdateEvent
     (update [_ state]

       ;; Only deselect if there is no modal opened
       (cond-> state
         (or (not check-modal)
             (not (::md/modal state)))
         (update :workspace-local
                 #(-> %
                      (assoc :selected (d/ordered-set))
                      (dissoc :selected-frame))))))))

;; --- Select Shapes (By selrect)

(defn select-shapes-by-current-selrect
  "Sends the current selection rectangle to the worker to compute the selection,
  and sends its result to select-shapes for storage in the state."
  ([initial-set remove? ignore-groups?]
   (select-shapes-by-current-selrect initial-set remove? ignore-groups? true))

  ([initial-set remove? ignore-groups? buffered?]
   (ptk/reify ::select-shapes-by-current-selrect
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id     (:current-page-id state)
             objects     (dsh/lookup-page-objects state page-id)
             selrect     (dm/get-in state [:workspace-local :selrect])
             blocked?    (fn [id] (dm/get-in objects [id :blocked] false))
             ask-worker  (if buffered? mw/ask-buffered! mw/ask!)
             filter-objs (comp
                          (filter (complement blocked?))
                          (remove (partial cfh/hidden-parent? objects)))]

         (if (some? selrect)
           (->> (ask-worker
                 {:cmd :index/query-selection
                  :page-id page-id
                  :rect selrect
                  :include-frames? true
                  :ignore-groups? ignore-groups?
                  :full-frame? true
                  :using-selrect? true})
                (rx/filter some?)
                (rx/map #(cfh/clean-loops objects %))
                (rx/map (if remove?
                          #(apply disj initial-set %)
                          #(into initial-set filter-objs %)))
                (rx/map select-shapes))
           (rx/empty)))))))

(defn select-inside-group
  [group-id position]

  (ptk/reify ::select-inside-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            objects  (dsh/lookup-page-objects state page-id)
            group    (get objects group-id)
            children (map #(get objects %) (:shapes group))

            ;; We need to reverse the children because if two children
            ;; overlap we want to select the one that's over (and it's
            ;; in the later vector position
            selected (->> (reverse children)
                          (d/seek #(gsh/has-point? % position)))]
        (when selected
          (rx/of (select-shape (:id selected))))))))

;; --- Duplicate Shapes

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
      (let [stopper (rx/filter (ptk/type? ::memorize-duplicated) stream)]
        (->> (rx/timer 10000) ;; This time may be adjusted after some user testing.
             (rx/take-until stopper)
             (rx/map clear-memorize-duplicated))))))

(defn calc-duplicate-delta
  [obj state objects]
  (let [{:keys [id-original id-duplicated]}
        (get-in state [:workspace-local :duplicated])
        move? (and (cfh/frame-shape? obj)
                   (not (ctk/instance-head? obj)))]
    (if (or (and (not= id-original (:id obj))
                 (not= id-duplicated (:id obj)))
            ;; As we can remove duplicated elements may be we can still caching a deleted id
            (not (contains? objects id-original))
            (not (contains? objects id-duplicated)))

      ;; The default is leave normal shapes in place, but put
      ;; new frames to the right of the original.
      (if move?
        (gpt/point (+ (:width obj) 50) 0)
        (gpt/point 0 0))

      (let [pt-original   (-> (get objects id-original) :selrect gpt/point)
            pt-duplicated (-> (get objects id-duplicated) :selrect gpt/point)
            pt-obj        (-> obj :selrect gpt/point)
            distance       (gpt/subtract pt-duplicated pt-original)
            new-pos        (gpt/add pt-duplicated distance)]

        (gpt/subtract new-pos pt-obj)))))

(defn duplicate-shapes
  [ids & {:keys [move-delta? alt-duplication? change-selection? return-ref]
          :or {move-delta? false alt-duplication? false change-selection? true return-ref nil}}]
  (ptk/reify ::duplicate-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page     (dsh/lookup-page state)
            objects  (:objects page)
            ids (into #{}
                      (comp (map (d/getf objects))
                            (filter #(ctk/allow-duplicate? objects %))
                            (map :id))
                      ids)]
        (when (seq ids)
          (let [obj             (get objects (first ids))
                delta           (if move-delta?
                                  (calc-duplicate-delta obj state objects)
                                  (gpt/point 0 0))

                file-id         (:current-file-id state)

                libraries       (dsh/lookup-libraries state)
                library-data    (dsh/lookup-file-data state file-id)

                changes         (-> (pcb/empty-changes it)
                                    (cll/generate-duplicate-changes objects page ids delta libraries library-data file-id {:alt-duplication? alt-duplication?})
                                    (cll/generate-duplicate-changes-update-indices objects ids))

                tags            (or (:tags changes) #{})

                changes         (cond-> changes alt-duplication? (assoc :tags (conj tags :alt-duplication)))

                id-original     (first ids)

                new-ids         (->> changes
                                     :redo-changes
                                     (filter #(= (:type %) :add-obj))
                                     (filter #(ids (:old-id %)))
                                     (map #(get-in % [:obj :id]))
                                     (into (d/ordered-set)))

                id-duplicated   (first new-ids)

                frames          (into #{}
                                      (map #(get-in objects [% :frame-id]))
                                      ids)
                undo-id         (js/Symbol)]
            (rx/concat
             (->> (rx/from ids)
                  (rx/map (fn [shape-id]
                            (let [shape       (get objects shape-id)
                                  parent-type (cfh/get-shape-type objects (:parent-id shape))
                                  external-lib? (not= file-id (:component-file shape))
                                  component     (ctn/get-component-from-shape shape libraries)
                                  origin        "workspace:duplicate-shapes"]

                              ;; NOTE: we don't emit the create-shape event all the time for
                              ;; avoid send a lot of events (that are not necessary); this
                              ;; decision is made explicitly by the responsible team.
                              (if (ctk/instance-head? shape)
                                (ev/event {::ev/name "use-library-component"
                                           ::ev/origin origin
                                           :is-external-library external-lib?
                                           :type (get shape :type)
                                           :parent-type parent-type
                                           :is-variant (ctk/is-variant? component)})
                                (if (cfh/has-layout? objects (:parent-id shape))
                                  (ev/event {::ev/name "layout-add-element"
                                             ::ev/origin origin
                                             :type (get shape :type)
                                             :parent-type parent-type})
                                  (ev/event {::ev/name "create-shape"
                                             ::ev/origin origin
                                             :type (get shape :type)
                                             :parent-type parent-type})))))))

             ;; Warning: This order is important for the focus mode.
             (->> (rx/of
                   (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (when change-selection?
                     (select-shapes new-ids))
                   (ptk/data-event :layout/update {:ids frames})
                   (memorize-duplicated id-original id-duplicated)
                   (dwu/commit-undo-transaction undo-id))
                  (rx/tap #(when (some? return-ref)
                             (reset! return-ref id-duplicated)))))))))))

(defn duplicate-selected
  ([move-delta?]
   (duplicate-selected move-delta? false))
  ([move-delta? alt-duplication?]
   (ptk/reify ::duplicate-selected
     ptk/WatchEvent
     (watch [_ state _]
       (when (or (not move-delta?) (nil? (get-in state [:workspace-local :transform])))
         (let [selected (dsh/lookup-selected state)]
           (rx/of (duplicate-shapes selected
                                    :move-delta? move-delta?
                                    :alt-duplication? alt-duplication?))))))))

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

      (let [objects (dsh/lookup-page-objects state)

            focus (-> (:workspace-focus-selected state)
                      (set/union added)
                      (set/difference removed))
            focus (cfh/clean-loops objects focus)]

        (-> state
            (assoc :workspace-focus-selected focus))))))

(defn toggle-focus-mode
  "Zoom in on and center viewport on selection;
   hide all other layers in viewport and layer panel.

   When in focus mode, exit restoring previous viewport and selection.
  "
  []
  (ptk/reify ::toggle-focus-mode
    ev/Event
    (-data [_] {})

    ptk/UpdateEvent
    (update [_ state]
      (let [selected (dsh/lookup-selected state)
            have-selection? (d/not-empty? selected)
            in-mode? (d/not-empty? (:workspace-focus-selected state))]

        (if in-mode?
          ;; Exit focus, restoring previous viewport, selection, etc
          (-> state
              (assoc :workspace-local (:workspace-pre-focus state))
              (dissoc :workspace-focus-selected)
              (dissoc :workspace-pre-focus))
          (if have-selection?
            ;; Enter focus and save viewport, selection, etc
            (-> state
                (assoc :workspace-focus-selected selected)
                (assoc :workspace-pre-focus (:workspace-local state)))
            state))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter #(or (= ::toggle-focus-mode (ptk/type %))
                                    (= ::dwpg/finalize-page (ptk/type %))) stream)]
        (when (d/not-empty? (:workspace-focus-selected state))
          (->> (rx/merge
                (rx/of dwz/zoom-to-selected-shape
                       (deselect-all))
                (->> (rx/from-atom refs/workspace-page-objects {:emit-current-value? true})
                     (rx/map (comp set keys))
                     (rx/buffer 2 1)
                     (rx/merge-map
                      ;; While focus is active, update it with any new and deleted shapes
                      (fn [[old-keys new-keys]]
                        (let [removed (set/difference old-keys new-keys)
                              added (set/difference new-keys old-keys)]

                          (if (or (d/not-empty? added) (d/not-empty? removed))
                            (rx/of (update-focus-shapes added removed))
                            (rx/empty)))))))
               (rx/take-until stopper)))))))
