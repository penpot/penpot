;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.instances
  "Components and their instances: create a component from shapes, instantiate
   it, restore a deleted one, and detach a copy from its component (the copy
   heads below it go up a nesting level)."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.logic.libraries :as cll]
   [app.common.logic.variant-properties :as clvp]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape.layout :as ctsl]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

(defn generate-instantiate-component
  "Generate changes to create a new instance from a component."
  ([changes objects file-id component-id position page libraries]
   (generate-instantiate-component changes objects file-id component-id position page libraries nil nil nil {}))
  ([changes objects file-id component-id position page libraries old-id parent-id frame-id params]
   (generate-instantiate-component changes objects file-id component-id position page libraries old-id parent-id frame-id {} params))
  ([changes objects file-id component-id position page libraries old-id parent-id frame-id ids-map
    {:keys [force-frame?]
     :or {force-frame? false}}]

   (let [component     (ctf/get-component libraries file-id component-id)
         library       (get libraries file-id)
         parent        (when parent-id (get objects parent-id))

         ;; When we are intanciating a variant, it can't be on a variant-container
         parent        (when parent
                         (if (and (ctk/is-variant? component)
                                  (ctk/is-variant-container? parent))
                           (get objects (:parent-id parent))
                           parent))
         parent-id     (d/nilv (:id parent) parent-id)
         frame-id      (d/nilv (:frame-id parent) frame-id)

         [new-shape new-shapes]
         (ctn/make-component-instance page
                                      component
                                      (:data library)
                                      position
                                      (cond-> {}
                                        (contains? ids-map old-id)
                                        (assoc :force-id (get ids-map old-id))

                                        force-frame?
                                        (assoc :force-frame-id frame-id)))

         first-shape
         (cond-> (first new-shapes)
           (not (nil? parent-id))
           (assoc :parent-id parent-id)
           (and (not (nil? parent)) (= :frame (:type parent)))
           (assoc :frame-id (:id parent))
           (and (not (nil? parent)) (not= :frame (:type parent)))
           (assoc :frame-id (:frame-id parent))
           (and (not (nil? parent)) (ctn/in-any-component? objects parent))
           (dissoc :component-root)
           (and (nil? parent) (not (nil? frame-id)))
           (assoc :frame-id frame-id))

         ;; on copy/paste old id is used later to reorder the paster layers
         changes
         (cond-> (pcb/add-object changes first-shape {:ignore-touched true})
           (some? old-id) (pcb/amend-last-change #(assoc % :old-id old-id)))

         duplicated-parent?
         (->> ids-map vals (some #(= % (:parent-id first-shape))))

         changes
         (if (and (ctsl/grid-layout? objects (:parent-id first-shape)) (not duplicated-parent?))
           (let [target-cell (-> position meta :cell)

                 [row column]
                 (when (some? target-cell)
                   [(:row target-cell) (:column target-cell)])]
             (-> changes
                 (pcb/update-shapes
                  [(:parent-id first-shape)]
                  (fn [shape objects]
                    (-> shape
                        (ctsl/assign-cells objects)
                        (cond-> (and (some? row) (some? column))
                          (-> (ctsl/push-into-cell [(:id first-shape)] row column)
                              (ctsl/assign-cells objects)))))
                  {:with-objects? true :ignore-touched true})
                 (pcb/reorder-grid-children [(:parent-id first-shape)])))
           changes)

         changes
         (reduce #(pcb/add-object %1 %2 {:ignore-touched true})
                 changes
                 (rest new-shapes))]

     [new-shape changes])))

(declare generate-detach-recursive)
(declare generate-advance-nesting-level)
(declare generate-detach-immediate)
(declare generate-rehead-swapped)

(defn generate-detach-instance
  "Generate changes to remove the links between a shape and all its children
  with a component."
  [changes container libraries shape-id]
  (let [shape (ctn/get-shape container shape-id)]
    (cll/shape-log :debug shape-id container
                   :msg "Detach instance" :shape-id shape-id :container (:id container))
    (let [changes  (generate-detach-recursive changes container libraries shape-id true (true? (:component-root shape)))
          objects  (pcb/get-objects changes)
          advanced (into []
                         (filter (fn [id]
                                   (let [before (get-in container [:objects id])
                                         after  (get objects id)]
                                     (and (ctk/swapped? after)
                                          (not= (:shape-ref before) (:shape-ref after))))))
                         (cfh/get-children-ids (:objects container) shape-id))]
      (cll/generate-sync-advanced-placement changes container libraries advanced))))

(defn- generate-detach-recursive
  [changes container libraries shape-id first component-root?]
  (let [shape (ctn/get-shape container shape-id)]
    (cll/shape-log :trace shape-id container
                   :msg "  Processing" :shape-id shape-id)
    (if (and (ctk/instance-head? shape) (not first))
      ; Subinstances are not detached
      (cond-> changes
        component-root?
        ; If the initial shape was component-root, first level subinstances are converted in top instances
        (pcb/update-shapes [shape-id] #(do (log/trace :msg "  -> promote to root")
                                           (assoc % :component-root true)))

        (ctk/swapped? shape)
        ; A swapped head left with no copy around it is a copy of its own component
        (generate-rehead-swapped container libraries shape)

        (not (ctk/swapped? shape))
        ; Near shape-ref of shape and children need to be advanced one level
        (generate-advance-nesting-level nil container libraries (:id shape)))

      ;; Otherwise, detach the shape and all children
      (let [children-ids (:shapes shape)]
        (log/trace :msg "  -> detach")
        (reduce #(generate-detach-recursive %1 container libraries %2 false component-root?)
                (pcb/update-shapes changes [(:id shape)] ctk/detach-shape)
                children-ids)))))

(defn rehead-swapped
  "Make a swapped head a plain copy of its own component, for when it leaves
  every copy level. Keep placement inherited from the old position as
  overrides when it differs from the new main."
  [shape main]
  (let [placement-groups (disj cll/placement-groups :geometry-group)
        value            (fn [source attr]
                           (case attr
                             :constraints-h (or (:constraints-h source) (gsh/default-constraints-h shape))
                             :constraints-v (or (:constraints-v source) (gsh/default-constraints-v shape))
                             :fixed-scroll (boolean (:fixed-scroll source))
                             (get source attr)))
        groups           (into #{}
                               (keep (fn [attr]
                                       (let [group (ctk/resolve-sync-group (:type shape) attr)]
                                         (when (and (contains? placement-groups group)
                                                    (not= (value shape attr) (value main attr)))
                                           group))))
                               (keys ctk/sync-attrs))]
    (cond-> (-> shape
                (assoc :shape-ref (:id main))
                (ctk/remove-swap-group))
      (seq groups) (update :touched (fnil into #{}) groups))))

(defn- generate-rehead-swapped
  [changes container libraries shape]
  (if-let [main (ctf/find-content-ref-shape nil container libraries shape :include-deleted? true)]
    (pcb/update-shapes changes [(:id shape)] #(rehead-swapped % main))
    (generate-detach-immediate changes container (:id shape) (atom #{}))))

(defn swapped-descendant-ids
  "The ids of the shapes below a swapped head in the subtree of the shape.
  They refer into the head's own component, so they do not change when the
  head changes its nesting level."
  [objects shape-id]
  (into #{}
        (comp (filter ctk/swapped?)
              (mapcat #(cfh/get-children-ids objects (:id %))))
        (cfh/get-children-with-self objects shape-id)))

(defn inherited-touched
  "The touched groups a copy shape takes from the main shape it skips when it
  goes up a nesting level. A swapped head takes only the placement ones,
  since its content does not come from that main."
  [shape ref-touched]
  (if (ctk/swapped? shape)
    (set/intersection ref-touched cll/placement-groups)
    ref-touched))

(defn- generate-advance-nesting-level
  [changes file container libraries shape-id]
  (log/trace :msg "  -> advance-nesting-level")
  (let [detached-ids (atom #{})
        unchanged-ids (swapped-descendant-ids (:objects container) shape-id)
        children  (->> (cfh/get-children-with-self (:objects container) shape-id)  ;; TODO: this function should be refactored to be a recursive tree traversal.
                       (remove #(contains? unchanged-ids (:id %))))
        skip-near (fn [changes shape]                                         ;;       this way we could shake the tree more easily when detaching shapes
                    (cll/shape-log :trace (:id shape) container                   ;;       and perhaps even allow to recover nested instances that have been
                                   :msg "     * advancing" :shape-id (:id shape)) ;;       swapped and so we can access the main instance again.
                    (if (contains? @detached-ids (:id shape))
                      (do (log/trace :msg "       (detached)")
                          changes)
                      (let [ref-shape (ctf/find-ref-shape file container libraries shape {:include-deleted? true})]
                        (cond-> changes
                          (some? (:shape-ref ref-shape))
                          (pcb/update-shapes [(:id shape)] #(do (log/trace :msg (str "       (advanced to " (:shape-ref ref-shape) ")"))
                                                                (assoc % :shape-ref (:shape-ref ref-shape))))

                          ;; When advancing level, the touched groups of the ref-shape must be
                          ;; merged into the current shape, because they refer to the new
                          ;; referenced shape.
                          (some? ref-shape)
                          (pcb/update-shapes
                           [(:id shape)]
                           #(do (log/trace :msg "       (merge touched)")
                                (log/trace :msg (str "       (ref-shape: " (:id ref-shape) ")"))
                                (log/trace :msg (str "       (ref touched: " (:touched ref-shape) ")"))
                                (assoc % :touched
                                       (set/union (:touched shape)
                                                  (inherited-touched shape (or (:touched ref-shape) #{}))))))

                          ;; If we can't get the ref-shape (e.g. it's in an external library not linked),
                          ;: we can't do a suitable advance. So it's better to detach the shape and all its
                          ;; children (and add to detached-ids so they are not processed again).
                          (nil? ref-shape)
                          (generate-detach-immediate container (:id shape) detached-ids)))))]

    (reduce skip-near changes children)))

(defn- generate-detach-immediate
  [changes container shape-id detached-ids]
  (let [shape-and-children (cfh/get-children-ids-with-self (:objects container) shape-id)]
    (log/trace :msg "       (cannot advance; detach shape and children)")
    (swap! detached-ids #(into % shape-and-children))
    (pcb/update-shapes changes shape-and-children ctk/detach-shape)))

(defn prepare-restore-component
  ([changes library-data component-id current-page]
   (let [component    (ctkl/get-deleted-component library-data component-id)
         page         (or (ctf/get-component-page library-data component)
                          (when (some #(= (:id current-page) %) (:pages library-data)) ;; If the page doesn't belong to the library, it's not valid
                            current-page)
                          (ctpl/get-last-page library-data))]
     (prepare-restore-component changes library-data component-id page nil nil nil nil)))

  ([changes library-data component-id page position old-id parent-id frame-id]
   (let [library-data      (or (pcb/get-library-data changes) library-data)
         component         (ctkl/get-deleted-component library-data component-id)
         objects           (or (pcb/get-objects changes) (:objects page))
         parent            (get objects parent-id)
         main-inst         (get-in component [:objects (:main-instance-id component)])
         inside-component? (some? (ctn/get-instance-root (:objects page) parent))
         shapes            (cfh/get-children-with-self (:objects component) (:main-instance-id component))

         is-variant?       (ctk/is-variant? component)

         orig-pos          (gpt/point (:x main-inst) (:y main-inst))
         delta             (if position
                             (gpt/subtract position orig-pos)
                             (gpt/point 0 0))
         minusdelta        (gpt/point (- (:x delta)) (- (:y delta)))

         moved-shapes      (map #(gsh/move % delta) shapes)

         first-shape       (cond-> (first moved-shapes)
                             (not (nil? parent-id))
                             (assoc :parent-id parent-id)
                             (not (nil? frame-id))
                             (assoc :frame-id frame-id)
                             (and (nil? frame-id) parent (= :frame (:type parent)))
                             (assoc :frame-id parent-id)
                             (and (nil? frame-id) parent (not= :frame (:type parent)))
                             (assoc :frame-id (:frame-id parent))
                             inside-component?
                             (dissoc :component-root)
                             (not inside-component?)
                             (assoc :component-root true))

         restoring-into-parent (get objects (:parent-id first-shape))

         changes           (-> changes
                               (pcb/with-page page)
                               (pcb/with-objects (:objects page))
                               (pcb/with-library-data library-data))
         changes           (cond-> (pcb/add-object changes first-shape {:ignore-touched true})
                             (some? old-id) (pcb/amend-last-change #(assoc % :old-id old-id))) ; on copy/paste old id is used later to reorder the paster layers
         changes           (reduce #(pcb/add-object %1 %2 {:ignore-touched true})
                                   changes
                                   (rest moved-shapes))
         changes           (cond-> changes
                             ;; Transform variant info into name when restoring into a parent that is not a variant-container,
                             ;; or when restoring into a variant-container that doesn't exists anymore
                             (and is-variant?
                                  (or (and parent (not (ctk/is-variant-container? parent)))
                                      (nil? restoring-into-parent)))
                             (clvp/generate-make-shapes-no-variant [first-shape])
                             ;; Add variant info and rename when restoring into a variant-container
                             (ctk/is-variant-container? restoring-into-parent)
                             (clvp/generate-make-shapes-variant [first-shape] restoring-into-parent))]
     {:changes (pcb/restore-component changes component-id (:id page) minusdelta)
      :shape (first moved-shapes)})))

(defn generate-add-component-changes
  [changes root objects file-id page-id variant-props]
  (let [name (:name root)
        variant-id  (when (ctk/is-variant? root) (:parent-id root))
        props       (when (ctk/is-variant? root) (get variant-props (:component-id root)))

        [path name] (cpn/split-group-name name)

        [root-shape updated-shapes]
        (ctn/convert-shape-in-component root objects file-id)

        changes     (-> changes
                        (pcb/add-component (:id root-shape)
                                           path
                                           name
                                           updated-shapes
                                           (:id root)
                                           page-id
                                           nil
                                           variant-id
                                           props))]
    [root-shape changes]))

(defn generate-add-component
  "If there is exactly one id, and it's a frame, and not already a component,
  use it as root. Otherwise, create a frame that contains all ids. Then, make a
  component with it, and link all shapes to their corresponding one in the component."
  [changes shapes objects page-id file-id prepare-create-board]

  (let [changes      (pcb/with-page-id changes page-id)
        shapes-count (count shapes)
        first-shape  (first shapes)

        from-singe-frame?
        (and (= 1 shapes-count)
             (cfh/frame-shape? first-shape))

        [root changes old-root-ids]
        (if (and (= shapes-count 1)
                 (cfh/frame-shape? first-shape)
                 (not (ctk/instance-head? first-shape)))
          [first-shape
           (-> changes
               (pcb/with-page-id page-id)
               (pcb/with-objects objects))
           (:shapes first-shape)]

          (let [root-name (if (= 1 shapes-count)
                            (:name first-shape)
                            "Component 1")

                shape-ids (into (d/ordered-set) (map :id) shapes)

                [root changes]
                (prepare-create-board changes
                                      (uuid/next)
                                      (:parent-id first-shape)
                                      objects
                                      shape-ids
                                      nil
                                      root-name
                                      true)]

            [root changes shape-ids]))

        changes
        (cond-> changes
          (not from-singe-frame?)
          (pcb/update-shapes
           (:shapes root)
           (fn [shape]
             (assoc shape :constraints-h :scale :constraints-v :scale))))

        objects' (assoc objects (:id root) root)

        [root-shape changes] (generate-add-component-changes changes root objects' file-id page-id nil)

        changes  (pcb/update-shapes changes
                                    old-root-ids
                                    #(dissoc % :component-root)
                                    [:component-root])]

    [root (:id root-shape) changes]))

(defn generate-restore-component
  "Restore a deleted component, with the given id, in the given file library."
  [changes library-data component-id library-id current-page objects]
  (let [{:keys [changes shape]} (prepare-restore-component changes library-data component-id current-page)
        parent-id (:parent-id shape)

        insert-before?
        (and (ctsl/flex-layout? objects parent-id)
             (not (ctsl/reverse? objects parent-id)))

        objects
        (-> objects
            (assoc (:id shape) shape)
            (cond-> (and (some? parent-id) insert-before?)
              (update-in [parent-id :shapes] #(d/concat-vec [(:id shape)] %)))
            (cond-> (and (some? parent-id) (not insert-before?))
              (update-in [parent-id :shapes] conj (:id shape))))

        ;; Adds a resize-parents operation so the groups are updated. We add all the new objects
        new-objects-ids (->> changes :redo-changes (filter #(= (:type %) :add-obj)) (mapv :id))
        changes (-> changes
                    (pcb/with-objects objects)
                    (pcb/resize-parents new-objects-ids)
                    ;; Fix the order of the children inside the parent
                    (cond-> (ctsl/any-layout? objects parent-id)
                      (pcb/reorder-children parent-id (get-in objects [parent-id :shapes]))))]
    (assoc changes :file-id library-id)))

(defn generate-detach-component
  "Generate changes for remove all references to components in the shape,
  with the given id and all its children, at the current page."
  [changes id file page-id libraries]
  (let [container (ctn/get-container file :page page-id)]
    (-> changes
        (pcb/with-container container)
        (pcb/with-objects (:objects container))
        (generate-detach-instance container libraries id))))
