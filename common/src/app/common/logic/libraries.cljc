;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.logic.libraries
  #?(:cljs (:require-macros [app.common.logic.libraries :refer [shape-log container-log]]))
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variant-properties :as clvp]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.token :as cto]
   [app.common.types.typography :as cty]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

;; Add uuids here to filter logs to only show specific shapes or containers (and all shapes
;; contained in them).
(def log-shape-ids #{})
(def log-container-ids #{})

(def updatable-attrs (->> (seq (keys ctk/sync-attrs))
                           ;; We don't update the flex-child attrs
                          (remove ctk/swap-keep-attrs)
                           ;; We don't do automatic update of the `layout-grid-cells` property.
                          (remove #(= :layout-grid-cells %))))

(defn enabled-shape?
  [id container]
  (or (empty? log-shape-ids)
      (nil? id)
      (let [ids (if container
                  (into #{} (cfh/get-parent-ids-seq-with-self (:objects container) id))
                  #{id})]
        (seq (set/intersection log-shape-ids ids)))))

(defmacro shape-log
  [level id container & params]
  `(when (enabled-shape? ~id ~container)
     (log/log ~level ~@params)))

(defn enabled-container?
  [id]
  (or (empty? log-container-ids)
      (log-container-ids id)))

(defmacro container-log
  [level id & params]
  `(when (enabled-container? ~id)
     (log/log ~level ~@params)))

(declare generate-sync-container)
(declare generate-sync-shape)
(declare generate-sync-text-shape)
(declare uses-assets?)

(declare generate-sync-shape-direct)
(declare generate-sync-shape-direct-recursive)
(declare generate-sync-shape-inverse-recursive)

(declare compare-children)
(declare add-shape-to-instance)
(declare add-shape-to-main)
(declare remove-shape)
(declare move-shape)
(declare change-touched)
(declare change-remote-synced)
(declare update-attrs)
(declare update-grid-main-attrs)
(declare update-grid-copy-attrs)
(declare update-flex-child-main-attrs)
(declare update-flex-child-copy-attrs)
(declare reposition-shape)
(declare make-change)

(defn pretty-file
  [file-id libraries current-file-id]
  (if (= file-id current-file-id)
    "<local>"
    (str "<" (get-in libraries [file-id :name]) ">")))

(defn pretty-uuid
  [uuid]
  (let [uuid-str (str uuid)]
    (subs uuid-str (- (count uuid-str) 6))))

;; ---- Components and instances creation ----

(defn- duplicate-component
  "Clone the root shape of the component and all children. Generate new
  ids from all of them."
  [component new-component-id library-data force-id delta variant-id]
  (let [main-instance-page  (ctf/get-component-page library-data component)
        main-instance-shape (ctf/get-component-root library-data component)
        delta               (or delta (gpt/point (+ (:width main-instance-shape) 50) 0))

        ids-map             (volatile! {})
        inverted-ids-map    (volatile! {})
        nested-main-heads   (volatile! #{})

        update-original-shape
        (fn [original-shape new-shape]
              ; Save some ids for later
          (vswap! ids-map assoc (:id original-shape) (:id new-shape))
          (vswap! inverted-ids-map assoc (:id new-shape) (:id original-shape))
          (when (and (ctk/main-instance? original-shape)
                     (not= (:component-id original-shape) (:id component)))
            (vswap! nested-main-heads conj (:id original-shape)))
          original-shape)

        update-new-shape
        (fn [new-shape _]
          (cond-> new-shape
            ; Link the new main to the new component
            (= (:component-id new-shape) (:id component))
            (assoc :component-id new-component-id)

            ; If it is the instance root, add it the variant-id
            (and (ctk/instance-root? new-shape) (some? variant-id))
            (assoc :variant-id variant-id)

            :always
            (gsh/move delta)))

        [new-instance-shape new-instance-shapes _]
        (ctst/clone-shape main-instance-shape
                          (:parent-id main-instance-shape)
                          (:objects main-instance-page)
                          :update-new-shape update-new-shape
                          :update-original-shape update-original-shape
                          :force-id force-id)
        remap-frame
        (fn [shape]
              ; Remap all frame-ids internal to the component to the new shapes
          (update shape :frame-id
                  #(get @ids-map % (:frame-id shape))))

        convert-nested-main
        (fn [shape]
              ; If there is some nested main instance, convert it into a copy of
              ; main nested in the original component.
          (let [origin-shape-id (get @inverted-ids-map (:id shape))
                objects         (:objects main-instance-page)
                parent-ids      (cfh/get-parent-ids-seq-with-self objects origin-shape-id)]
            (cond-> shape
              (@nested-main-heads origin-shape-id)
              (dissoc :main-instance)

              (some @nested-main-heads parent-ids)
              (assoc :shape-ref origin-shape-id))))

        xf-shape (comp (map remap-frame)
                       (map convert-nested-main))

        new-instance-shapes (into [] xf-shape new-instance-shapes)]

    [new-instance-shape new-instance-shapes]))

(defn generate-duplicate-component
  "Create a new component copied from the one with the given id."
  [changes library component-id new-component-id & {:keys [new-shape-id apply-changes-local-library? delta new-variant-id page-id]}]
  (let [component          (ctkl/get-component (:data library) component-id)
        new-name           (:name component)

        main-instance-page (ctf/get-component-page (:data library) component)
        ;; Since variants, we can duplicate a component into another page
        target-page-id     (or page-id (:id main-instance-page))

        [new-main-instance-shape new-main-instance-shapes]
        (duplicate-component component new-component-id (:data library) new-shape-id delta new-variant-id)]

    [new-main-instance-shape
     (-> changes
         (pcb/with-page-id target-page-id)
         (pcb/with-objects (:objects main-instance-page))
         (pcb/add-objects new-main-instance-shapes {:ignore-touched true})
         (pcb/add-component new-component-id
                            (:path component)
                            new-name
                            []
                            (:id new-main-instance-shape)
                            target-page-id
                            (:annotation component)
                            (or new-variant-id (:variant-id component))
                            (:variant-properties component)
                            {:apply-changes-local-library? apply-changes-local-library?})
         ;; Update grid layout if the new main instance is inside
         (pcb/update-shapes
          [(:frame-id new-main-instance-shape)]
          (fn [shape objects]
            (cond-> shape
              (ctl/grid-layout? shape)
              (ctl/assign-cells objects)))
          {:with-objects? true}))]))


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
         (if (and (ctl/grid-layout? objects (:parent-id first-shape)) (not duplicated-parent?))
           (let [target-cell (-> position meta :cell)

                 [row column]
                 (when (some? target-cell)
                   [(:row target-cell) (:column target-cell)])]
             (-> changes
                 (pcb/update-shapes
                  [(:parent-id first-shape)]
                  (fn [shape objects]
                    (-> shape
                        (ctl/assign-cells objects)
                        (cond-> (and (some? row) (some? column))
                          (-> (ctl/push-into-cell [(:id first-shape)] row column)
                              (ctl/assign-cells objects)))))
                  {:with-objects? true})
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

(defn generate-detach-instance
  "Generate changes to remove the links between a shape and all its children
  with a component."
  [changes container libraries shape-id]
  (let [shape (ctn/get-shape container shape-id)]
    (shape-log :debug shape-id container
               :msg "Detach instance" :shape-id shape-id :container (:id container))
    (generate-detach-recursive changes container libraries shape-id true (true? (:component-root shape)))))

(defn- generate-detach-recursive
  [changes container libraries shape-id first component-root?]
  (let [shape (ctn/get-shape container shape-id)]
    (shape-log :trace shape-id container
               :msg "  Processing" :shape-id shape-id)
    (if (and (ctk/instance-head? shape) (not first))
      ; Subinstances are not detached
      (cond-> changes
        component-root?
        ; If the initial shape was component-root, first level subinstances are converted in top instances
        (pcb/update-shapes [shape-id] #(do (log/trace :msg "  -> promote to root")
                                           (assoc % :component-root true)))

        :always
        ; First level subinstances of a detached component can't have swap-slot
        (pcb/update-shapes [shape-id] #(do (log/trace :msg "  -> remove swap-slot")
                                           (ctk/remove-swap-slot %)))

        (nil? (ctk/get-swap-slot shape))
        ; Near shape-ref of shape and children need to be advanced one level
        ; (except if the head is already swapped)
        (generate-advance-nesting-level nil container libraries (:id shape)))

      ;; Otherwise, detach the shape and all children
      (let [children-ids (:shapes shape)]
        (log/trace :msg "  -> detach")
        (reduce #(generate-detach-recursive %1 container libraries %2 false component-root?)
                (pcb/update-shapes changes [(:id shape)] ctk/detach-shape)
                children-ids)))))

(defn- generate-advance-nesting-level
  [changes file container libraries shape-id]
  (log/trace :msg "  -> advance-nesting-level")
  (let [detached-ids (atom #{})
        children  (cfh/get-children-with-self (:objects container) shape-id)  ;; TODO: this function should be refactored to be a recursive tree traversal.
        skip-near (fn [changes shape]                                         ;;       this way we could shake the tree more easily when detaching shapes
                    (shape-log :trace (:id shape) container                   ;;       and perhaps even allow to recover nested instances that have been
                               :msg "     * advancing" :shape-id (:id shape)) ;;       swapped and so we can access the main instance again.
                    (if (contains? @detached-ids (:id shape))
                      (do (log/trace :msg "       (detached)")
                          changes)
                      (let [ref-shape (ctf/find-ref-shape file container libraries shape {:include-deleted? true})]
                        (cond-> changes
                          (some? (:shape-ref ref-shape))
                          (pcb/update-shapes [(:id shape)] #(do (log/trace :msg "       (advanced)")
                                                                (assoc % :shape-ref (:shape-ref ref-shape))))

                          ;; When advancing level, the normal touched groups (not swap slots) of the
                          ;; ref-shape must be merged into the current shape, because they refer to
                          ;; the new referenced shape.
                          (some? ref-shape)
                          (pcb/update-shapes
                           [(:id shape)]
                           #(do (log/trace :msg "       (merge touched)")
                                (assoc % :touched
                                       (clojure.set/union (:touched shape)
                                                          (ctk/normal-touched-groups ref-shape)))))

                          ;; Swap slot must also be copied if the current shape has not any,
                          ;; except if this is the first level subcopy.
                          (and (some? (ctk/get-swap-slot ref-shape))
                               (nil? (ctk/get-swap-slot shape))
                               (not= (:id shape) shape-id))
                          (pcb/update-shapes [(:id shape)] #(do (log/trace :msg "       (got swap-slot)")
                                                                (ctk/set-swap-slot % (ctk/get-swap-slot ref-shape))))

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

;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the given file,
  that use assets of the given type in the given library.

  If an asset id is given, only shapes linked to this particular asset will
  be synchronized."
  [changes file-id asset-type asset-id library-id libraries current-file-id]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) asset-id)
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid library-id)

  (container-log :info asset-id
                 :msg "Sync file with library"
                 :asset-type asset-type
                 :asset-id asset-id
                 :file (pretty-file file-id libraries current-file-id)
                 :library (pretty-file library-id libraries current-file-id))

  (let [file          (get-in libraries [file-id :data])]
    (loop [containers (ctf/object-containers-seq file)
           changes    changes]
      (if-let [container (first containers)]
        (do
          (recur (next containers)
                 (pcb/concat-changes ;;TODO Remove concat changes
                  changes
                  (generate-sync-container (pcb/empty-changes nil)
                                           asset-type
                                           asset-id
                                           library-id
                                           container
                                           libraries
                                           current-file-id))))
        changes))))

(defn generate-sync-library
  "Generate changes to synchronize all shapes in all components of the
  local library of the given file, that use assets of the given type in
  the given library.

  If an asset id is given, only shapes linked to this particular asset will
  be synchronized."
  [changes file-id asset-type asset-id library-id libraries current-file-id]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) asset-id)
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid library-id)

  (container-log :info asset-id
                 :msg "Sync local components with library"
                 :asset-type asset-type
                 :asset-id asset-id
                 :file (pretty-file file-id libraries current-file-id)
                 :library (pretty-file library-id libraries current-file-id))

  (let [file          (get-in libraries [file-id :data])]
    (loop [local-components (ctkl/components-seq file)
           changes changes]
      (if-let [local-component (first local-components)]
        (recur (next local-components)
               (pcb/concat-changes ;;TODO Remove concat changes
                changes
                (generate-sync-container  (pcb/empty-changes nil)
                                          asset-type
                                          asset-id
                                          library-id
                                          (cfh/make-container local-component :component)
                                          libraries
                                          current-file-id)))
        changes))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container (a page
  or a component) that use assets of the given type in the given library."
  [changes asset-type asset-id library-id container libraries current-file-id]

  (if (cfh/page? container)
    (container-log :debug (:id container) :msg "Sync page in local file" :page-id (:id container))
    (container-log :debug (:id container) :msg "Sync component in local library" :component-id (:id container)))

  (let [linked-shapes (->> (vals (:objects container))
                           (filter #(uses-assets? asset-type asset-id % library-id)))]
    (loop [shapes (seq linked-shapes)
           changes (-> changes
                       (pcb/with-container container)
                       (pcb/with-objects (:objects container)))]
      (if-let [shape (first shapes)]
        (recur (next shapes)
               (generate-sync-shape asset-type
                                    changes
                                    library-id
                                    container
                                    shape
                                    libraries
                                    current-file-id))
        changes))))

(defmulti uses-assets?
  "Checks if a shape uses some asset of the given type in the given library."
  (fn [asset-type _ _ _] asset-type))

(defmethod uses-assets? :components
  [_ component-id shape library-id]
  (if (nil? component-id)
    (ctk/uses-library-components? shape library-id)
    (ctk/instance-of? shape library-id component-id)))

(defmethod uses-assets? :colors
  [_ color-id shape library-id]
  (if (nil? color-id)
    (ctc/uses-library-colors? shape library-id)
    (ctc/uses-library-color? shape library-id color-id)))

(defmethod uses-assets? :typographies
  [_ typography-id shape library-id]
  (if (nil? typography-id)
    (cty/uses-library-typographies? shape library-id)
    (cty/uses-library-typography? shape library-id typography-id)))

(defmulti generate-sync-shape
  "Generate changes to synchronize one shape from all assets of the given type
  that is using, in the given library."
  (fn [asset-type _changes _library-id _container _shape _libraries _current-file-id] asset-type))

(defmethod generate-sync-shape :components
  [_ changes _library-id container shape libraries current-file-id]
  (let [shape-id  (:id shape)
        file      (get current-file-id libraries)]
    (generate-sync-shape-direct changes file libraries container shape-id false)))

(defmethod generate-sync-shape :colors
  [_ changes library-id _ shape libraries _]
  (shape-log :debug (:id shape) nil :msg "Sync colors of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some colors of the library. The value of the
  ;; color in the library is copied to the shape.
  (let [library-colors (get-in libraries [library-id :data :colors])]
    (pcb/update-shapes changes
                       [(:id shape)]
                       #(ctc/sync-shape-colors % library-id library-colors))))

(defmethod generate-sync-shape :typographies
  [_ changes library-id container shape libraries _]
  (shape-log :debug (:id shape) nil :msg "Sync typographies of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some typographies of the library. The attributes
  ;; of the typography are copied to the shape."
  (let [typographies  (get-in libraries [library-id :data :typographies])
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (dissoc typography :name :id))
                        (dissoc node :typography-ref-id
                                :typography-ref-file)))]
    (generate-sync-text-shape changes shape container update-node)))

(defn- generate-sync-text-shape
  [changes shape container update-node]
  (let [old-content (:content shape)
        new-content (txt/transform-nodes update-node old-content)

        redo-change
        (make-change
         container
         {:type :mod-obj
          :id (:id shape)
          :operations [{:type :set
                        :attr :content
                        :val new-content}
                       {:type :set
                        :attr :position-data
                        :val nil}]})

        undo-change
        (make-change
         container
         {:type :mod-obj
          :id (:id shape)
          :operations [{:type :set
                        :attr :content
                        :val old-content}
                       {:type :set
                        :attr :position-data
                        :val nil}]})

        changes'    (-> changes
                        (update :redo-changes conj redo-change)
                        (update :undo-changes conj undo-change))]

    (if (= new-content old-content)
      changes
      changes')))


;; ---- Component synchronization helpers ----

;; Three sources of component synchronization:
;;
;;  - NORMAL SYNC: when a component is updated, any shape that use it,
;;    must be synchronized. All attributes that have changed in the
;;    component and whose attr group has not been "touched" in the dest
;;    shape are copied.
;;
;;      generate-sync-shape-direct (reset = false)
;;
;;  - FORCED SYNC: when the "reset" command is applied to some shape,
;;    all attributes that have changed in the component are copied, and
;;    the "touched" flags are cleared.
;;
;;      generate-sync-shape-direct (reset = true)
;;
;;  - INVERSE SYNC: when the "update component" command is used in some
;;    shape, all the attributes that have changed in the shape are copied
;;    into the linked component. The "touched" flags are also cleared in
;;    the origin shape.
;;
;;      generate-sync-shape-inverse
;;
;;  The initial shape is always a group (a root instance), so all the
;;  children are recursively synced, too. A root instance is a group shape
;;  that has the "component-id" attribute and also "component-root?" is true.
;;
;;  The children lists of the instance and the component shapes are compared
;;  side-by-side. Any new, deleted or moved child modifies (and "touches")
;;  the parent shape.
;;
;;  When a shape inside a component is in turn an instance of another
;;  component, the synchronization is more complex:
;;
;;    [Page]
;;    Instance-2         #--> Component-2          (#--> = root instance)
;;      IShape-2-1        -->   Shape-2-1          (@--> = nested instance)
;;      Subinstance-2-2  @-->   Component-1        ( --> = shape ref)
;;        IShape-2-2-1    -->     Shape-1-1
;;
;;    [Component-1]
;;    Component-1
;;      Shape-1-1
;;
;;    [Component-2]
;;    Component-2
;;      Shape-2-1
;;      Subcomponent-2-2 @--> Component-1
;;        Shape-2-2-1     -->   Shape-1-1
;;
;;   * A SUBINSTANCE ACTUALLY HAS TWO MAINS. For example IShape-2-2-1
;;     depends on Shape-2-2-1 (in the "near" component) but also on
;;     Shape-1-1-1 (in the "remote" component). The "shape-ref" attribute
;;     always refer to the remote shape, and it's guaranteed that it's
;;     always a final shape, not an instance. The relationship between the
;;     shape and the near shape is that both point to the same remote.
;;
;;   * THE INITIAL VALUE of IShape-2-2-1 comes from the near component
;;     Shape-2-2-1 (although the shape-ref attribute points to the direct
;;     component Shape-1-1). The touched flags of IShape-2-2-1 start
;;     cleared at first, and activate on any attribute change onwards.
;;
;;   * IN A NORMAL SYNC, the sync process starts in the root instance and
;;     continues recursively with the children of the root instance and
;;     the component. Therefore, IShape-2-2-1 is synced with Shape-2-2-1.
;;
;;   * IN A FORCED SYNC, IF THE INITIAL SHAPE IS THE ROOT INSTANCE, the
;;     order is the same, and IShape-2-2-1 is reset from Shape-2-2-1 and
;;     marked as not touched.
;;
;;   * IF THE INITIAL SHAPE IS THE SUBINSTANCE, the sync is done against
;;     the remote component. Therefore, IShape-2-2-1 is synched with
;;     Shape-1-1. Then the "touched" flags are reset, and the
;;     "remote-synced" flag is set (it will be set until the shape is
;;     touched again or it's synced forced normal or inverse with the
;;     near component).
;;
;;   * IN AN INVERSE SYNC, IF THE INITIAL SHAPE IS THE ROOT INSTANCE, the
;;     order is the same as in the normal sync. Therefore, IShape-2-2-1
;;     values are copied into Shape-2-2-1, and then its touched flags are
;;     cleared. Then, the "touched" flags THAT ARE TRUE are copied to
;;     Shape-2-2-1. This may cause that Shape-2-2-1 is now touched respect
;;     to Shape-1-1, and so, some attributes are not copied in a subsequent
;;     normal sync. Or, if "remote-synced" flag is set in IShape-2-2-1,
;;     all touched flags are cleared in Shape-2-2-1 and "remote-synced"
;;     is removed.
;;
;;   * IN AN INVERSE SYNC INITIATED IN THE SUBINSTANCE, the update is done
;;     to the remote component. E.g. IShape-2-2-1 attributes are copied into
;;     Shape-1-1, and then touched cleared and "remote-synced" flag set.
;;
;;     #### WARNING: there are two conditions that are invisible to user:
;;       - When the near shape (Shape-2-2-1) is touched respect the remote
;;         one (Shape-1-1), there is no asterisk displayed anywhere.
;;       - When the instance shape (IShape-2-2-1) is synced with the remote
;;         shape (remote-synced = true), the user will see that this shape
;;         is different than the one in the near component (Shape-2-2-1)
;;         but it's not touched.

(defn- redirect-shaperef ;;Set the :shape-ref of a shape pointing to the :id of its remote-shape
  ([container libraries shape]
   (redirect-shaperef nil nil shape (ctf/find-remote-shape container libraries shape)))
  ([_ _ shape remote-shape]
   (if (some? (:shape-ref shape))
     (assoc shape :shape-ref (:id remote-shape))
     shape)))

(defn generate-sync-shape-direct
  "Generate changes to synchronize one shape that is the root of a component
  instance, and all its children, from the given component."
  [changes file libraries container shape-id reset?]
  (shape-log :debug shape-id container
             :msg "Sync shape direct" :shape-inst (str shape-id) :reset? reset?)
  (let [shape-inst (ctn/get-shape container shape-id)
        library    (dm/get-in libraries [(:component-file shape-inst) :data])
        component  (ctkl/get-component library (:component-id shape-inst) true)]
    (if (and (ctk/in-component-copy? shape-inst)
             (or (ctf/direct-copy? shape-inst component container nil libraries) reset?)) ; In a normal sync, we don't want to sync remote mains, only direct/near
      (let [redirect-shaperef (partial redirect-shaperef container libraries)

            shape-main (when component
                         (if reset?
                           ;; the reset is against the ref-shape, not against the original shape of the component
                           (ctf/find-ref-shape file container libraries shape-inst)
                           (ctf/get-ref-shape library component shape-inst)))

            shape-inst (if reset?
                         (redirect-shaperef shape-inst shape-main)
                         shape-inst)

            initial-root?  (:component-root shape-inst)

            root-inst      shape-inst
            root-main      shape-main]

        (if component
          (generate-sync-shape-direct-recursive changes
                                                container
                                                shape-inst
                                                component
                                                library
                                                file
                                                libraries
                                                shape-main
                                                root-inst
                                                root-main
                                                reset?
                                                initial-root?
                                                redirect-shaperef)
          ;; If the component is not found, because the master component has been
          ;; deleted or the library unlinked, do nothing.
          changes))
      changes)))

(defn- find-main-container
  "Find the container that has the main shape."
  [container-inst shape-inst shape-main library component]
  (loop [shape-inst' shape-inst
         component' component]
    (let [container (ctf/get-component-container library component')] ; TODO: this won't work if some intermediate component is in a different library
      (if (some? (ctn/get-shape container (:id shape-main)))          ;       for this to work we need to have access to the libraries list here
        container
        (let [parent (ctn/get-shape container-inst (:parent-id shape-inst'))
              shape-inst' (ctn/get-head-shape (:objects container-inst) parent)
              component' (or (ctkl/get-component library (:component-id shape-inst'))
                             (ctkl/get-deleted-component library (:component-id shape-inst')))]
          (if (some? component)
            (recur shape-inst'
                   component')
            nil))))))

(defn- generate-sync-shape-direct-recursive
  [changes container shape-inst component library file libraries shape-main root-inst root-main reset? initial-root? redirect-shaperef]
  (shape-log :debug (:id shape-inst) container
             :msg "Sync shape direct recursive"
             :shape-inst (str (:name shape-inst) " " (pretty-uuid (:id shape-inst)))
             :component (:name component))

  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [omit-touched?        (not reset?)
          clear-remote-synced? (and initial-root? reset?)
          set-remote-synced?   (and (not initial-root?) reset?)
          changes
          (cond-> changes
            :always
            (update-attrs shape-inst
                          shape-main
                          root-inst
                          root-main
                          container
                          omit-touched?)

            (ctl/flex-layout? shape-main)
            (update-flex-child-copy-attrs shape-main
                                          shape-inst
                                          library
                                          component
                                          container
                                          omit-touched?)

            reset?
            (change-touched shape-inst
                            shape-main
                            container
                            {:reset-touched? true})

            clear-remote-synced?
            (change-remote-synced shape-inst container nil)

            set-remote-synced?
            (change-remote-synced shape-inst container true))

          component-container (find-main-container container shape-inst shape-main library component)

          children-inst       (vec (ctn/get-direct-children container shape-inst))
          children-main       (vec (ctn/get-direct-children component-container shape-main))

          children-inst (if reset?
                          (map #(redirect-shaperef %) children-inst) children-inst)

          only-inst (fn [changes child-inst]
                      (shape-log :trace (:id child-inst) container
                                 :msg "Only inst"
                                 :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst))))
                      (if-not (and omit-touched?
                                   (contains? (:touched shape-inst)
                                              :shapes-group))
                        (remove-shape changes
                                      child-inst
                                      container
                                      omit-touched?)
                        changes))

          only-main (fn [changes child-main]
                      (shape-log :trace (:id child-main) component-container
                                 :msg "Only main"
                                 :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
                      (if-not (and omit-touched?
                                   (contains? (:touched shape-inst)
                                              :shapes-group))
                        (add-shape-to-instance changes
                                               child-main
                                               (d/index-of children-main
                                                           child-main)
                                               component-container
                                               container
                                               root-inst
                                               root-main
                                               omit-touched?
                                               set-remote-synced?)
                        changes))

          both (fn [changes child-inst child-main]
                 (shape-log :trace (:id child-inst) container
                            :msg "Both"
                            :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst)))
                            :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
                 (generate-sync-shape-direct-recursive changes
                                                       container
                                                       child-inst
                                                       component
                                                       library
                                                       file
                                                       libraries
                                                       child-main
                                                       root-inst
                                                       root-main
                                                       reset?
                                                       initial-root?
                                                       redirect-shaperef))

          swapped (fn [changes child-inst child-main]
                    (shape-log :trace (:id child-inst) container
                               :msg "Match slot"
                               :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst)))
                               :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
                    ;; For now we don't make any sync here.
                    changes)

          moved (fn [changes child-inst child-main]
                  (shape-log :trace (:id child-inst) container
                             :msg "Move"
                             :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst)))
                             :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
                  (move-shape
                   changes
                   child-inst
                   (d/index-of children-inst child-inst)
                   (d/index-of children-main child-main)
                   container
                   omit-touched?))

          changes
          (compare-children changes
                            shape-inst
                            children-inst
                            children-main
                            container
                            component-container
                            file
                            libraries
                            only-inst
                            only-main
                            both
                            swapped
                            moved
                            false
                            reset?)

          changes
          (cond-> changes
            (ctl/grid-layout? shape-inst)
            (update-grid-copy-attrs
             (:id shape-inst)
             shape-main
             library
             component
             omit-touched?))]

      changes)))

(defn generate-rename-component
  "Generate the changes for rename the component with the given id, in the current file library."
  [changes id new-name library-data]
  (let [[path name]   (cfh/parse-path-name new-name)]
    (-> changes
        (pcb/with-library-data library-data)
        (pcb/update-component id #(assoc % :path path :name name)))))

(defn generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children."
  [changes file libraries container shape-id]
  (shape-log :debug shape-id container :msg "Sync shape inverse" :shape (str shape-id))
  (let [redirect-shaperef (partial redirect-shaperef container libraries)
        shape-inst     (ctn/get-shape container shape-id)
        library        (dm/get-in libraries [(:component-file shape-inst) :data])
        component      (ctkl/get-component library (:component-id shape-inst))

        shape-main     (when component
                         (ctf/find-remote-shape container libraries shape-inst))

        shape-inst     (redirect-shaperef shape-inst shape-main)

        initial-root?  (:component-root shape-inst)

        root-inst      shape-inst
        root-main      (ctf/get-component-root library component)

        changes        (cond-> changes
                         (and component (contains? (:touched shape-inst) :name-group))
                         (generate-rename-component (:component-id shape-inst) (:name shape-inst) library))]

    (if component
      (generate-sync-shape-inverse-recursive changes
                                             container
                                             shape-inst
                                             component
                                             library
                                             file
                                             libraries
                                             shape-main
                                             root-inst
                                             root-main
                                             initial-root?
                                             redirect-shaperef)
      changes)))

(defn- generate-sync-shape-inverse-recursive
  [changes container shape-inst component library file libraries shape-main root-inst root-main initial-root? redirect-shaperef]
  (shape-log :trace (:id shape-inst) container
             :msg "Sync shape inverse recursive"
             :shape (str (:name shape-inst))
             :component (:name component))

  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [component-container  (ctf/get-component-container library component)

          omit-touched?        false
          set-remote-synced?   (not initial-root?)
          clear-remote-synced? initial-root?

          changes (cond-> changes
                    :always
                    (-> (update-attrs shape-main
                                      shape-inst
                                      root-main
                                      root-inst
                                      component-container
                                      omit-touched?)
                        (change-touched shape-inst
                                        shape-main
                                        container
                                        {:reset-touched? true})
                        (change-touched shape-main
                                        shape-inst
                                        component-container
                                        {:copy-touched? true}))

                    (ctl/flex-layout? shape-main)
                    (update-flex-child-main-attrs shape-main
                                                  shape-inst
                                                  component-container
                                                  container
                                                  omit-touched?)

                    (ctl/grid-layout? shape-main)
                    (update-grid-main-attrs shape-main
                                            shape-inst
                                            component-container
                                            container
                                            omit-touched?)

                    clear-remote-synced?
                    (change-remote-synced shape-inst container nil)

                    set-remote-synced?
                    (change-remote-synced shape-inst container true))

          children-inst   (mapv #(ctn/get-shape container %)
                                (:shapes shape-inst))
          children-main   (mapv #(ctn/get-shape component-container %)
                                (:shapes shape-main))

          children-inst (map #(redirect-shaperef %) children-inst)

          only-inst (fn [changes child-inst]
                      (add-shape-to-main changes
                                         child-inst
                                         (d/index-of children-inst
                                                     child-inst)
                                         component
                                         component-container
                                         container
                                         root-inst
                                         root-main))

          only-main (fn [changes child-main]
                      (remove-shape changes
                                    child-main
                                    component-container
                                    false))

          both (fn [changes child-inst child-main]
                 (generate-sync-shape-inverse-recursive changes
                                                        container
                                                        child-inst
                                                        component
                                                        library
                                                        file
                                                        libraries
                                                        child-main
                                                        root-inst
                                                        root-main
                                                        initial-root?
                                                        redirect-shaperef))

          swapped (fn [changes child-inst child-main]
                    (shape-log :trace (:id child-inst) container
                               :msg "Match slot"
                               :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst)))
                               :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
                    ;; For now we don't make any sync here.
                    changes)

          moved (fn [changes child-inst child-main]
                  (move-shape
                   changes
                   child-main
                   (d/index-of children-main child-main)
                   (d/index-of children-inst child-inst)
                   component-container
                   false))

          changes
          (compare-children changes
                            shape-inst
                            children-inst
                            children-main
                            container
                            component-container
                            file
                            libraries
                            only-inst
                            only-main
                            both
                            swapped
                            moved
                            true
                            true)

          ;; The inverse sync may be made on a component that is inside a
          ;; remote library. We need to separate changes that are from
          ;; local and remote files.
          check-local (fn [change]
                        (cond-> change
                          (= (:id change) (:id shape-inst))
                          (assoc :local-change? true)))]

      (-> changes
          (update :redo-changes (partial mapv check-local))
          (update :undo-changes (partial map check-local))))))


;; ---- Operation generation helpers ----

(defn- compare-children
  [changes shape-inst children-inst children-main container-inst container-main file libraries only-inst-cb only-main-cb both-cb swapped-cb moved-cb inverse? reset?]
  (shape-log :trace (:id shape-inst) container-inst :msg "Compare children")
  (loop [children-inst (seq (or children-inst []))
         children-main (seq (or children-main []))
         changes       changes]
    (let [child-inst (first children-inst)
          child-main (first children-main)]
      (shape-log :trace (:id shape-inst) container-inst
                 :main (str (:name child-main) " " (pretty-uuid (:id child-main)))
                 :inst (str (:name child-inst) " " (pretty-uuid (:id child-inst))))
      (cond
        (and (nil? child-inst) (nil? child-main))
        changes

        (nil? child-inst)
        (reduce only-main-cb changes children-main)

        (nil? child-main)
        (reduce only-inst-cb changes children-inst)

        :else
        (if (or (ctk/is-main-of? child-main child-inst)
                (and (ctf/match-swap-slot? child-main child-inst container-inst container-main file libraries) (not reset?)))
          (recur (next children-inst)
                 (next children-main)
                 (if (ctk/is-main-of? child-main child-inst)
                   (both-cb changes child-inst child-main)
                   (swapped-cb changes child-inst child-main)))

          (let [child-inst' (d/seek #(or (ctk/is-main-of? child-main %)
                                         (and (ctf/match-swap-slot? child-main % container-inst container-main file libraries) (not reset?)))
                                    children-inst)
                child-main' (d/seek #(or (ctk/is-main-of? % child-inst)
                                         (and (ctf/match-swap-slot? % child-inst container-inst container-main file libraries) (not reset?)))
                                    children-main)]
            (cond
              (nil? child-inst')
              (recur children-inst
                     (next children-main)
                     (only-main-cb changes child-main))

              (nil? child-main')
              (recur (next children-inst)
                     children-main
                     (only-inst-cb changes child-inst))

              :else
              (if inverse?
                (let [is-main? (ctk/is-main-of? child-inst child-main')]
                  (recur (next children-inst)
                         (remove #(= (:id %) (:id child-main')) children-main)
                         (cond-> changes
                           is-main?
                           (both-cb child-inst child-main')
                           (not is-main?)
                           (swapped-cb child-inst child-main')
                           :always
                           (moved-cb child-inst child-main'))))
                (let [is-main? (ctk/is-main-of? child-inst' child-main)]
                  (recur (remove #(= (:id %) (:id child-inst')) children-inst)
                         (next children-main)
                         (cond-> changes
                           is-main?
                           (both-cb child-inst' child-main)
                           (not is-main?)
                           (swapped-cb child-inst' child-main)
                           :always
                           (moved-cb child-inst' child-main))))))))))))

(defn- add-shape-to-instance
  [changes component-shape index component-page container root-instance root-main omit-touched? set-remote-synced?]
  (shape-log :info (:id component-shape) component-page
             :msg (str "ADD [P " (pretty-uuid (:id container)) "] "
                       (:name component-shape)
                       " "
                       (pretty-uuid (:id component-shape))))
  (let [component-parent-shape (ctn/get-shape component-page (:parent-id component-shape))
        parent-shape           (d/seek #(ctk/is-main-of? component-parent-shape %)
                                       (cfh/get-children-with-self (:objects container)
                                                                   (:id root-instance)))
        all-parents            (into [(:id parent-shape)]
                                     (cfh/get-parent-ids (:objects container)
                                                         (:id parent-shape)))

        update-new-shape (fn [new-shape original-shape]
                           (let [new-shape (reposition-shape new-shape
                                                             root-main
                                                             root-instance)]
                             (cond-> new-shape
                               (= (:id original-shape) (:id component-shape))
                               (assoc :frame-id (if (= (:type parent-shape) :frame)
                                                  (:id parent-shape)
                                                  (:frame-id parent-shape)))

                               set-remote-synced?
                               (assoc :remote-synced true)

                               :always
                               (-> (assoc :shape-ref (:id original-shape))
                                   (dissoc :touched))))) ; New shape, by definition, is synced to the main shape

        update-original-shape (fn [original-shape _new-shape]
                                original-shape)

        [_ new-shapes _]
        (ctst/clone-shape component-shape
                          (:id parent-shape)
                          (get component-page :objects)
                          :update-new-shape update-new-shape
                          :update-original-shape update-original-shape
                          :dest-objects (get container :objects))

        add-obj-change (fn [changes shape']
                         (update changes :redo-changes conj
                                 (make-change
                                  container
                                  (as-> {:type :add-obj
                                         :id (:id shape')
                                         :parent-id (:parent-id shape')
                                         :index index
                                         :ignore-touched true
                                         :obj shape'} $
                                    (cond-> $
                                      (:frame-id shape')
                                      (assoc :frame-id (:frame-id shape')))))))

        del-obj-change (fn [changes shape']
                         (update changes :undo-changes conj
                                 (make-change
                                  container
                                  {:type :del-obj
                                   :id (:id shape')
                                   :ignore-touched true})))

        changes' (reduce add-obj-change changes new-shapes)
        changes' (update changes' :redo-changes conj (make-change
                                                      container
                                                      {:type :reg-objects
                                                       :shapes all-parents}))
        changes' (reduce del-obj-change changes' new-shapes)]

    (if (and (ctk/touched-group? parent-shape :shapes-group) omit-touched?)
      changes
      changes')))

(defn- add-shape-to-main
  [changes shape index component component-container page root-instance root-main]
  (shape-log :info (:id shape) page
             :msg (str "ADD [C " (pretty-uuid (:id component-container)) "] "
                       (:name shape)
                       " "
                       (pretty-uuid (:id shape))))
  (let [parent-shape           (ctn/get-shape page (:parent-id shape))
        component-parent-shape (d/seek #(ctk/is-main-of? % parent-shape)
                                       (cfh/get-children-with-self (:objects component-container)
                                                                   (:id root-main)))
        all-parents  (into [(:id component-parent-shape)]
                           (cfh/get-parent-ids (:objects component-container)
                                               (:id component-parent-shape)))

        update-new-shape (fn [new-shape _original-shape]
                           (reposition-shape new-shape
                                             root-instance
                                             root-main))

        update-original-shape (fn [original-shape new-shape]
                                (assoc original-shape
                                       :shape-ref (:id new-shape)))

        [_new-shape new-shapes updated-shapes]
        (ctst/clone-shape shape
                          (:id component-parent-shape)
                          (get page :objects)
                          :update-new-shape update-new-shape
                          :update-original-shape update-original-shape
                          :frame-id (if (cfh/frame-shape? component-parent-shape)
                                      (:id component-parent-shape)
                                      (:frame-id component-parent-shape)))

        add-obj-change (fn [changes shape']
                         (update changes :redo-changes conj
                                 (cond-> (make-change
                                          component-container
                                          {:type :add-obj
                                           :id (:id shape')
                                           :parent-id (:parent-id shape')
                                           :frame-id (:frame-id shape')
                                           :index index
                                           :ignore-touched true
                                           :obj shape'}))))

        mod-obj-change (fn [changes shape']
                         (let [shape-original (ctn/get-shape page (:id shape'))]
                           (-> changes
                               (update :redo-changes conj
                                       {:type :mod-obj
                                        :page-id (:id page)
                                        :id (:id shape')
                                        :operations [{:type :set
                                                      :attr :component-id
                                                      :val (:component-id shape')}
                                                     {:type :set
                                                      :attr :component-file
                                                      :val (:component-file shape')}
                                                     {:type :set
                                                      :attr :component-root
                                                      :val (:component-root shape')}
                                                     {:type :set
                                                      :attr :shape-ref
                                                      :val (:shape-ref shape')}
                                                     {:type :set
                                                      :attr :touched
                                                      :val (:touched shape')}]})
                               (update :undo-changes conj
                                       {:type :mod-obj
                                        :page-id (:id page)
                                        :id (:id shape-original)
                                        :operations [{:type :set
                                                      :attr :component-id
                                                      :val (:component-id shape-original)}
                                                     {:type :set
                                                      :attr :component-file
                                                      :val (:component-file shape-original)}
                                                     {:type :set
                                                      :attr :component-root
                                                      :val (:component-root shape-original)}
                                                     {:type :set
                                                      :attr :shape-ref
                                                      :val (:shape-ref shape-original)}
                                                     {:type :set
                                                      :attr :touched
                                                      :val (:touched shape-original)}]}))))

        del-obj-change (fn [changes shape']
                         (update changes :undo-changes conj
                                 {:type :del-obj
                                  :id (:id shape')
                                  :page-id (:id page)
                                  :ignore-touched true}))

        changes' (reduce add-obj-change changes new-shapes)
        changes' (update changes' :redo-changes conj {:type :reg-objects
                                                      :component-id (:id component)
                                                      :shapes all-parents})
        changes' (reduce mod-obj-change changes' updated-shapes)
        changes' (reduce del-obj-change changes' new-shapes)]

    changes'))

(defn- remove-shape
  [changes shape container omit-touched?]
  (shape-log :info (:id shape) container
             :msg (str "REMOVE-SHAPE "
                       (if (cfh/page? container) "[P " "[C ")
                       (pretty-uuid (:id container)) "] "
                       (:name shape)
                       " "
                       (pretty-uuid (:id shape))))
  (let [objects    (get container :objects)
        parents    (cfh/get-parent-ids objects (:id shape))
        parent     (first parents)
        children   (cfh/get-children-ids objects (:id shape))
        ids        (-> (into [(:id shape)] children)
                       (reverse)) ;; Remove from bottom to top

        add-redo-change (fn [changes id]
                          (update changes :redo-changes conj
                                  (make-change
                                   container
                                   {:type :del-obj
                                    :id id
                                    :ignore-touched true})))

        add-undo-change (fn [changes id]
                          (let [shape' (get objects id)]
                            (update changes :undo-changes conj
                                    (make-change
                                     container
                                     (as-> {:type :add-obj
                                            :id id
                                            :index (cfh/get-position-on-parent objects id)
                                            :parent-id (:parent-id shape')
                                            :ignore-touched true
                                            :obj shape'} $
                                       (cond-> $
                                         (:frame-id shape')
                                         (assoc :frame-id (:frame-id shape'))))))))

        changes' (-> (reduce add-redo-change changes ids)
                     (update :redo-changes conj (make-change
                                                 container
                                                 {:type :reg-objects
                                                  :shapes (vec parents)})))

        changes' (reduce add-undo-change
                         changes'
                         ids)]

    (if (and (ctk/touched-group? parent :shapes-group) omit-touched?)
      changes
      changes')))

(defn- move-shape
  [changes shape index-before index-after container omit-touched?]
  (shape-log :info (:id shape) container
             :msg (str "MOVE "
                       (if (cfh/page? container) "[P " "[C ")
                       (pretty-uuid (:id container)) "] "
                       (:name shape)
                       " "
                       (pretty-uuid (:id shape))
                       " "
                       index-before
                       " -> "
                       index-after))
  (let [parent (ctn/get-shape container (:parent-id shape))

        changes' (-> changes
                     (update :redo-changes conj (make-change
                                                 container
                                                 {:type :mov-objects
                                                  :parent-id (:parent-id shape)
                                                  :shapes [(:id shape)]
                                                  :index index-after
                                                  :ignore-touched true
                                                  :syncing true}))
                     (update :undo-changes conj (make-change
                                                 container
                                                 {:type :mov-objects
                                                  :parent-id (:parent-id shape)
                                                  :shapes [(:id shape)]
                                                  :index index-before
                                                  :ignore-touched true
                                                  :syncing true})))]

    (if (and (ctk/touched-group? parent :shapes-group) omit-touched?)
      changes
      changes')))

(defn change-touched
  [changes dest-shape origin-shape container
   {:keys [reset-touched? copy-touched?] :as options}]
  (if (nil? (:shape-ref dest-shape))
    changes
    (do
      (shape-log :info (:id dest-shape) container
                 :msg (str "CHANGE-TOUCHED "
                           (if (cfh/page? container) "[P " "[C ")
                           (pretty-uuid (:id container)) "] "
                           (:name dest-shape)
                           " "
                           (pretty-uuid (:id dest-shape)))
                 :options options)
      (let [new-touched (cond
                          reset-touched?
                          nil

                          copy-touched?
                          (if (:remote-synced origin-shape)
                            nil
                            (set/union
                             (:touched dest-shape)
                             (:touched origin-shape)))

                          :else
                          (:touched dest-shape))]

        (-> changes
            (update :redo-changes conj (make-change
                                        container
                                        {:type :mod-obj
                                         :id (:id dest-shape)
                                         :operations
                                         [{:type :set-touched
                                           :touched new-touched}]}))
            (update :undo-changes conj (make-change
                                        container
                                        {:type :mod-obj
                                         :id (:id dest-shape)
                                         :operations
                                         [{:type :set-touched
                                           :touched (:touched dest-shape)}]})))))))

(defn- change-remote-synced
  [changes shape container remote-synced?]
  (if (nil? (:shape-ref shape))
    changes
    (do
      (shape-log :info (:id shape) container
                 :msg (str "CHANGE-REMOTE-SYNCED? "
                           (if (cfh/page? container) "[P " "[C ")
                           (pretty-uuid (:id container)) "] "
                           (:name shape)
                           " "
                           (pretty-uuid (:id shape)))
                 :remote-synced remote-synced?)
      (-> changes
          (update :redo-changes conj (make-change
                                      container
                                      {:type :mod-obj
                                       :id (:id shape)
                                       :operations
                                       [{:type :set-remote-synced
                                         :remote-synced remote-synced?}]}))
          (update :undo-changes conj (make-change
                                      container
                                      {:type :mod-obj
                                       :id (:id shape)
                                       :operations
                                       [{:type :set-remote-synced
                                         :remote-synced (:remote-synced shape)}]}))))))

(defn- update-tokens
  "Token synchronization algorithm. Copy the applied tokens that have changed
   in the origin shape to the dest shape (applying or removing as necessary).

   Only the given token attributes are synced."
  [changes container dest-shape orig-shape token-attrs]
  (let [orig-tokens  (get orig-shape :applied-tokens {})
        dest-tokens  (get dest-shape :applied-tokens {})
        dest-tokens' (reduce (fn [dest-tokens' token-attr]
                               (let [orig-token (get orig-tokens token-attr)
                                     dest-token (get dest-tokens token-attr)]
                                 (if (= orig-token dest-token)
                                   dest-tokens'
                                   (if (nil? orig-token)
                                     (dissoc dest-tokens' token-attr)
                                     (assoc dest-tokens' token-attr orig-token)))))
                             dest-tokens
                             token-attrs)]
    (if (= dest-tokens dest-tokens')
      changes
      (-> changes
          (update :redo-changes conj (make-change
                                      container
                                      {:type :mod-obj
                                       :id (:id dest-shape)
                                       :operations [{:type :set
                                                     :attr :applied-tokens
                                                     :val dest-tokens'
                                                     :ignore-touched true}]}))
          (update :undo-changes conj (make-change
                                      container
                                      {:type :mod-obj
                                       :id (:id dest-shape)
                                       :operations [{:type :set
                                                     :attr :applied-tokens
                                                     :val dest-tokens
                                                     :ignore-touched true}]}))))))

(defn- generate-update-tokens
  [changes container dest-shape origin-shape touched omit-touched?]
  (let [attrs (->> (seq (keys ctk/sync-attrs))
                   ;; We don't update the flex-child attrs
                   (remove #(= :layout-grid-cells %)))

        applied-tokens (reduce (fn [applied-tokens attr]
                                 (let [attr-group (get ctk/sync-attrs attr)
                                       token-attrs (cto/shape-attr->token-attrs attr)]
                                   (if (not (and (touched attr-group)
                                                 omit-touched?))
                                     (into applied-tokens token-attrs)
                                     applied-tokens)))
                               #{}
                               attrs)]
    (cond-> changes
      (seq applied-tokens)
      (update-tokens container dest-shape origin-shape applied-tokens))))

(defn- add-update-attr-changes
  [changes dest-shape container roperations uoperations]
  (let [all-parents (cfh/get-parent-ids (:objects container)
                                        (:id dest-shape))]
    (-> changes
        (update :redo-changes conj (make-change
                                    container
                                    {:type :mod-obj
                                     :id (:id dest-shape)
                                     :operations roperations}))
        (update :redo-changes conj (make-change
                                    container
                                    {:type :reg-objects
                                     :shapes all-parents}))
        (update :undo-changes conj (make-change
                                    container
                                    {:type :mod-obj
                                     :id (:id dest-shape)
                                     :operations (vec uoperations)}))
        (update :undo-changes concat [(make-change
                                       container
                                       {:type :reg-objects
                                        :shapes all-parents})]))))


(defn- add-update-attr-operations
  [attr dest-shape origin-shape roperations uoperations touched]
  (let [orig-value (get origin-shape attr)
        dest-value (get dest-shape attr)
        ;; position-data is a special case because can be affected by :geometry-group and :content-group
        ;; so, if the position-data changes but the geometry is touched we need to reset the position-data
        ;; so it's calculated again
        reset-pos-data?
        (and (cfh/text-shape? origin-shape)
             (= attr :position-data)
             (not= orig-value dest-value)
             (touched :geometry-group))

        val (cond
              ;; If position data changes and the geometry group is touched
              ;; we need to put to nil so we can regenerate it
              reset-pos-data? nil
              :else orig-value)

        roperation {:type :set
                    :attr attr
                    :val val
                    :ignore-touched true}
        uoperation {:type :set
                    :attr attr
                    :val (get dest-shape attr)
                    :ignore-touched true}]
    [(conj roperations roperation)
     (conj uoperations uoperation)]))

(defn- update-attrs
  "The main function that implements the attribute sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.

  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will not be copied."
  [changes dest-shape origin-shape dest-root origin-root container omit-touched?]

  (shape-log :info (:id dest-shape) container
             :msg (str "SYNC "
                       (:name origin-shape)
                       " "
                       (pretty-uuid (:id origin-shape))
                       " -> "
                       (if (cfh/page? container) "[P " "[C ")
                       (pretty-uuid (:id container)) "] "
                       (:name dest-shape)
                       " "
                       (pretty-uuid (:id dest-shape))))

  (let [;; To synchronize geometry attributes we need to make a prior
        ;; operation, because coordinates are absolute, but we need to
        ;; sync only the position relative to the origin of the component.
        ;; We solve this by moving the origin shape so it is aligned with
        ;; the dest root before syncing.
        ;; In case of subinstances, the comparison is always done with the
        ;; near component, because this is that we are syncing with.
        origin-shape (reposition-shape origin-shape origin-root dest-root)
        touched      (get dest-shape :touched #{})]

    (loop [attrs updatable-attrs
           roperations []
           uoperations '()]

      (let [attr (first attrs)]
        (if (nil? attr)
          (cond-> changes
            (seq roperations)
            (add-update-attr-changes dest-shape container roperations uoperations)
            :always
            (generate-update-tokens container dest-shape origin-shape touched omit-touched?))

          (let [attr-group        (get ctk/sync-attrs attr)
                skip-operations? (or (= (get origin-shape attr) (get dest-shape attr))
                                     (and (touched attr-group)
                                          omit-touched?))

                [roperations' uoperations']
                (if skip-operations?
                  [roperations uoperations]
                  (add-update-attr-operations attr dest-shape origin-shape roperations uoperations touched))]
            (recur (next attrs)
                   roperations'
                   uoperations')))))))

(defn update-attrs-on-switch
  "Copy attributes that have changed in the origin shape to the dest shape. Used on variants switch"
  [changes dest-shape origin-shape dest-root origin-root origin-ref-shape container]
  (let [;; We need to sync only the position relative to the origin of the component.
        ;; (see update-attrs for a full explanation)
        origin-shape   (reposition-shape origin-shape origin-root dest-root)
        touched        (get dest-shape :touched #{})
        touched-origin (get origin-shape :touched #{})]

    (loop [attrs       updatable-attrs
           roperations [{:type :set-touched :touched (:touched origin-shape)}]
           uoperations (list {:type :set-touched :touched (:touched dest-shape)})]
      (if-let [attr (first attrs)]
        (let [attr-group (get ctk/sync-attrs attr)
              [roperations' uoperations']
              (if (or
                   ;; If the attribute is not valid for the destiny, don't copy it
                   (not (cts/is-allowed-attr? attr (:type dest-shape)))
                   ;; If the values are already equal, don't copy it
                   (= (get origin-shape attr) (get dest-shape attr))
                   ;; If the referenced shape on the original component doesn't have the same value, don't copy it
                   ;; Exceptions: :points :selrect and :content can be different
                   (and
                    (not (contains? #{:points :selrect :content} attr))
                    (not= (get origin-ref-shape attr) (get dest-shape attr)))
                   ;; The :content attr cant't be copied to elements of different type
                   (and (= attr :content) (not= (:type origin-shape) (:type dest-shape)))
                   ;; If the attr is not touched in the origin shape, don't copy it
                   (not (touched-origin attr-group)))
                [roperations uoperations]
                (add-update-attr-operations attr dest-shape origin-shape roperations uoperations touched))]
          (recur (next attrs)
                 roperations'
                 uoperations'))
        (cond-> changes
          (> (count roperations) 1)
          (add-update-attr-changes dest-shape container roperations uoperations)

          :always
          (generate-update-tokens container dest-shape origin-shape touched false))))))

(defn- propagate-attrs
  "Helper that puts the origin attributes (attrs) into dest but only if
  not touched the group or if omit-touched? flag is true"
  [dest origin attrs omit-touched?]
  (let [touched (get dest :touched #{})]
    (->> attrs
         (reduce
          (fn [dest attr]
            (let [attr-group (get ctk/sync-attrs attr)]
              (cond-> dest
                (or (not (touched attr-group)) (not omit-touched?))
                (assoc attr (get origin attr)))))
          dest))))

(defn- update-flex-child-copy-attrs
  "Synchronizes the attributes inside the flex-child items (main->copy)"
  [changes shape-main shape-copy main-container main-component copy-container omit-touched?]

  (let [new-changes
        (-> (pcb/empty-changes)
            (pcb/with-container copy-container)
            (pcb/with-objects (:objects copy-container))

            ;; The layout-item-sizing needs to be update when the parent is auto or fix
            (pcb/update-shapes
             [(:id shape-copy)]
             (fn [shape-copy]
               (cond-> shape-copy
                 (contains? #{:auto :fix} (:layout-item-h-sizing shape-main))
                 (propagate-attrs shape-main #{:layout-item-h-sizing} omit-touched?)

                 (contains? #{:auto :fix} (:layout-item-h-sizing shape-main))
                 (propagate-attrs shape-main #{:layout-item-v-sizing} omit-touched?)))
             {:ignore-touched true})

            ;; Update the child flex properties from the parent
            (pcb/update-shapes
             (:shapes shape-copy)
             (fn [child-copy]
               (let [child-main (ctf/get-ref-shape main-container main-component child-copy)]
                 (-> child-copy
                     (propagate-attrs child-main ctk/swap-keep-attrs omit-touched?))))
             {:ignore-touched true}))]
    (pcb/concat-changes changes new-changes)))

(defn- update-flex-child-main-attrs
  "Synchronizes the attributes inside the flex-child items (copy->main)"
  [changes shape-main shape-copy main-container copy-container omit-touched?]
  (let [new-changes
        (-> (pcb/empty-changes)
            (pcb/with-page main-container)
            (pcb/with-objects (:objects main-container))

            ;; The layout-item-sizing needs to be update when the parent is auto or fix
            (pcb/update-shapes
             [(:id shape-main)]
             (fn [shape-main]
               (cond-> shape-main
                 (contains? #{:auto :fix} (:layout-item-h-sizing shape-copy))
                 (propagate-attrs shape-copy #{:layout-item-h-sizing} omit-touched?)

                 (contains? #{:auto :fix} (:layout-item-h-sizing shape-copy))
                 (propagate-attrs shape-copy #{:layout-item-v-sizing} omit-touched?)))
             {:ignore-touched true})

            ;; Updates the children properties from the parent
            (pcb/update-shapes
             (:shapes shape-main)
             (fn [child-main]
               (let [child-copy (ctf/get-shape-in-copy copy-container child-main shape-copy)]
                 (-> child-main
                     (propagate-attrs child-copy ctk/swap-keep-attrs omit-touched?))))
             {:ignore-touched true}))]
    (pcb/concat-changes changes new-changes)))

(defn- update-grid-copy-attrs
  "Synchronizes the `layout-grid-cells` property from the main shape to the copies"
  [changes shape-copy-id shape-main main-container main-component omit-touched?]
  (-> changes
      (pcb/apply-changes-local)
      (pcb/update-shapes
       [shape-copy-id]
       (fn [shape-copy objects]
         (let [component-page
               (ctf/get-component-page main-container main-component)

               component-swap-children
               (->> shape-main
                    :shapes
                    (map #(get (:objects component-page) %))
                    (filter #(some? (ctk/get-swap-slot %)))
                    (group-by ctk/get-swap-slot))

               ids-map
               (into {}
                     (comp
                      (map #(get objects %))
                      (keep
                       (fn [copy-shape]
                         (let [main-shape
                               (if (some? (ctk/get-swap-slot copy-shape))
                                 (first (get component-swap-children (ctk/get-swap-slot copy-shape)))
                                 (ctf/get-ref-shape main-container main-component copy-shape))]
                           [(:id main-shape) (:id copy-shape)]))))
                     (:shapes shape-copy))

               remove-orphan-cells
               (fn [cells {:keys [shapes]}]
                 (let [child? (set shapes)]
                   (-> cells
                       (update-vals
                        (fn [cell]
                          (update cell :shapes #(filterv child? %)))))))
               ;; Take cells from main and remap the shapes to assign it to the copy
               copy-cells (-> shape-copy :layout-grid-cells (remove-orphan-cells shape-copy))
               main-cells (-> shape-main (ctl/remap-grid-cells ids-map) :layout-grid-cells)]
           (-> shape-copy
               (assoc :layout-grid-cells
                      (ctl/merge-cells main-cells copy-cells omit-touched?))
               (ctl/assign-cells objects))))
       {:ignore-touched true :with-objects? true})))

(defn- update-grid-main-attrs
  "Synchronizes the `layout-grid-cells` property from the copy to the main shape"
  [changes shape-main shape-copy main-container copy-container _omit-touched?]
  (let [ids-map
        (into {}
              (comp
               (map #(dm/get-in main-container [:objects %]))
               (keep
                (fn [main-shape]
                  (let [copy-shape (ctf/get-shape-in-copy copy-container main-shape shape-copy)]
                    [(:id copy-shape) (:id main-shape)]))))
              (:shapes shape-main))

        new-changes
        (-> (pcb/empty-changes)
            (pcb/with-page main-container)
            (pcb/with-objects (:objects main-container))
            (pcb/update-shapes
             [(:id shape-main)]
             (fn [shape-main]
               ;; Take cells from copy and remap the shapes to assign it to the copy
               (let [new-cells (-> (ctl/remap-grid-cells shape-copy ids-map) :layout-grid-cells)]
                 (assoc shape-main :layout-grid-cells new-cells)))
             {:ignore-touched true}))]
    (pcb/concat-changes changes new-changes)))

(defn- reposition-shape
  [shape origin-root dest-root]
  (let [shape-pos (fn [shape]
                    (gpt/point (get-in shape [:selrect :x])
                               (get-in shape [:selrect :y])))

        origin-root-pos (shape-pos origin-root)
        dest-root-pos   (shape-pos dest-root)
        delta           (gpt/subtract dest-root-pos origin-root-pos)]
    (gsh/move shape delta)))

(defn- make-change
  [container change]
  (if (cfh/page? container)
    (assoc change :page-id (:id container))
    (assoc change :component-id (:id container))))

(defn generate-add-component-changes
  [changes root objects file-id page-id variant-props]
  (let [name (:name root)
        variant-id  (when (ctk/is-variant? root) (:parent-id root))
        props       (when (ctk/is-variant? root) (get variant-props (:component-id root)))

        [path name] (cfh/parse-path-name name)

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
        (and (ctl/flex-layout? objects parent-id)
             (not (ctl/reverse? objects parent-id)))

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
                    (cond-> (ctl/any-layout? objects parent-id)
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

(defn generate-new-shape-for-swap
  [changes shape file page libraries id-new-component index target-cell keep-props-values]
  (let [objects      (:objects page)
        position     (gpt/point (:x shape) (:y shape))
        changes      (-> changes
                         (pcb/with-objects objects))
        position     (-> position (with-meta {:cell target-cell}))
        parent       (get objects (:parent-id shape))
        inside-comp? (ctn/in-any-component? objects parent)

        [new-shape changes]
        (generate-instantiate-component changes
                                        objects
                                        (:id file)
                                        id-new-component
                                        position
                                        page
                                        libraries
                                        nil
                                        (:parent-id shape)
                                        (:frame-id shape)
                                        {:force-frame? true})

        new-shape (cond-> new-shape
                    ;; if the shape isn't inside a main component, it shouldn't have a swap slot
                    (and (nil? (ctk/get-swap-slot new-shape))
                         inside-comp?)
                    (ctk/set-swap-slot (ctf/find-swap-slot shape
                                                           page
                                                           {:id (:id file)
                                                            :data file}
                                                           libraries)))]

    [new-shape (-> changes
                   ;; Restore the properties
                   (pcb/update-shapes [(:id new-shape)] #(d/patch-object % keep-props-values))

                   ;; We need to set the same index as the original shape
                   (pcb/change-parent (:parent-id shape) [new-shape] index {:component-swap true
                                                                            :ignore-touched true})
                   (change-touched new-shape
                                   shape
                                   (ctn/make-container page :page)
                                   {}))]))

(defn generate-component-swap
  [changes objects shape file page libraries id-new-component index target-cell keep-props-values]
  (let [[all-parents changes]
        (-> changes
            (cls/generate-delete-shapes file page objects (d/ordered-set (:id shape)) {:component-swap true}))
        [new-shape changes]
        (-> changes
            (generate-new-shape-for-swap shape file page libraries id-new-component index target-cell keep-props-values))]
    [new-shape all-parents changes]))

(defn generate-sync-file-changes
  [changes undo-group asset-type file-id asset-id library-id libraries current-file-id]
  (let [sync-components?   (or (nil? asset-type) (= asset-type :components))
        sync-colors?       (or (nil? asset-type) (= asset-type :colors))
        sync-typographies? (or (nil? asset-type) (= asset-type :typographies))]
    (cond-> changes
      :always
      (pcb/set-undo-group undo-group)
      ;; library-changes
      sync-components?
      (generate-sync-library file-id :components asset-id library-id libraries current-file-id)
      sync-colors?
      (generate-sync-library file-id :colors asset-id library-id libraries current-file-id)
      sync-typographies?
      (generate-sync-library file-id :typographies asset-id library-id libraries current-file-id)

      ;; file-changes
      sync-components?
      (generate-sync-file file-id :components asset-id library-id libraries current-file-id)
      sync-colors?
      (generate-sync-file file-id :colors asset-id library-id libraries current-file-id)
      sync-typographies?
      (generate-sync-file file-id :typographies asset-id library-id libraries current-file-id))))

(defn generate-sync-head
  [changes file-full libraries container id reset?]
  (let [shape-inst (ctn/get-shape container id)
        objects    (:objects container)
        parent     (get objects (:parent-id shape-inst))
        head       (ctn/get-component-shape container parent)
        changes
        (-> changes
            (pcb/with-container container)
            (pcb/with-objects (:objects container))
            (generate-sync-shape-direct file-full libraries container (:id head) reset?))]
    changes))

(defn generate-reset-component
  [changes file-full libraries container id]
  (let [objects    (:objects container)
        swap-slot  (-> (ctn/get-shape container id)
                       (ctk/get-swap-slot))
        changes
        (-> changes
            (pcb/with-container container)
            (pcb/with-objects objects)
            (generate-sync-shape-direct file-full libraries container id true))]

    (cond-> changes
      (some? swap-slot)
      (generate-sync-head file-full libraries container id true))))

(defn generate-duplicate-flows
  [changes shapes page ids-map]
  (let [flows            (get page :flows)
        unames           (volatile! (cfh/get-used-names (vals flows)))
        has-flow?        (partial ctp/get-frame-flow flows)]

    (reduce (fn [changes frame-id]
              (let [name     (cfh/generate-unique-name "Flow" @unames :immediate-suffix? true)
                    frame-id (get ids-map frame-id)
                    flow-id  (uuid/next)
                    new-flow {:id flow-id
                              :name name
                              :starting-frame frame-id}]

                (vswap! unames conj name)
                (-> changes
                    (pcb/with-page page)
                    (pcb/set-flow flow-id new-flow))))

            changes
            (->> shapes
                 (filter cfh/frame-shape?)
                 (map :id)
                 (filter has-flow?)))))

(defn generate-duplicate-guides
  [changes shapes page ids-map delta]
  (let [guides (get page :guides)
        frames (filter cfh/frame-shape? shapes)]

    ;; FIXME: this can be implemented efficiently just indexing guides
    ;; by frame-id instead of iterate over all guides all the time

    (reduce (fn [changes frame]
              (let [new-id     (get ids-map (:id frame))
                    new-frame  (gsh/move frame delta)]

                (reduce-kv (fn [changes _ guide]
                             (if (= (:id frame) (:frame-id guide))
                               (let [guide-id (uuid/next)
                                     position (if (= (:axis guide) :x)
                                                (+ (:position guide) (- (:x new-frame) (:x frame)))
                                                (+ (:position guide) (- (:y new-frame) (:y frame))))
                                     guide    {:id guide-id
                                               :frame-id new-id
                                               :position position
                                               :axis (:axis guide)}]
                                 (pcb/set-guide changes guide-id guide))
                               changes))
                           changes
                           guides)))
            (pcb/with-page changes page)
            frames)))

(defn- duplicate-variant
  [changes library component base-pos parent-id page-id]
  (let [component-page   (ctpl/get-page (:data library) (:main-instance-page component))
        component-shape  (dm/get-in component-page [:objects (:main-instance-id component)])
        orig-pos         (gpt/point (:x component-shape) (:y component-shape))
        delta            (gpt/subtract base-pos orig-pos)
        new-component-id (uuid/next)
        [shape changes]  (generate-duplicate-component changes
                                                       library
                                                       (:id component)
                                                       new-component-id
                                                       {:apply-changes-local-library? true
                                                        :delta delta
                                                        :new-variant-id parent-id
                                                        :page-id page-id})]
    [shape
     (-> changes
         (pcb/change-parent parent-id [shape]))]))


(defn generate-duplicate-component-change
  [changes objects page main parent-id frame-id delta libraries library-data ids-map]
  (let [main-id      (:id main)
        component-id (:component-id main)
        file-id      (:component-file main)
        component    (ctf/get-component libraries file-id component-id)
        pos          (as-> (gsh/move main delta) $
                       (gpt/point (:x $) (:y $)))

        ;; When we duplicate a variant alone, we will instanciate it
        ;; When we duplicate a variant along with its variant-container, we will duplicate it
        in-variant-container? (contains? ids-map (:variant-id main))


        restore-component
        #(let [{:keys [shape changes]}
               (prepare-restore-component changes
                                          library-data
                                          component-id
                                          page
                                          pos
                                          main-id
                                          parent-id
                                          frame-id)]
           [shape changes])

        [_shape changes]
        (if (nil? component)
          (restore-component)
          (if (and (ctk/is-variant? main) in-variant-container?)
            (duplicate-variant changes
                               (get libraries file-id)
                               component
                               pos
                               parent-id
                               (:id page))

            (generate-instantiate-component changes
                                            objects
                                            file-id
                                            component-id
                                            pos
                                            page
                                            libraries
                                            main-id
                                            parent-id
                                            frame-id
                                            ids-map
                                            {})))]
    changes))

(defn generate-duplicate-shape-change
  ([changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id]
   (generate-duplicate-shape-change changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id (:frame-id obj) (:parent-id obj) false false true nil))
  ([changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id variant-props]
   (generate-duplicate-shape-change changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id (:frame-id obj) (:parent-id obj) false false true variant-props))

  ([changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id frame-id parent-id duplicating-component? child? remove-swap-slot? variant-props]
   (cond
     (nil? obj)
     changes

     (ctf/is-main-of-known-component? obj libraries)
     (generate-duplicate-component-change changes objects page obj parent-id frame-id delta libraries library-data ids-map)

     :else
     (let [frame?      (cfh/frame-shape? obj)
           group?      (cfh/group-shape? obj)
           bool?       (cfh/bool-shape? obj)
           new-id      (ids-map (:id obj))
           parent-id   (or parent-id frame-id)
           parent      (get objects parent-id)
           name        (:name obj)

           is-component-root?     (or (:saved-component-root obj)
                                      ;; Backward compatibility
                                      (:saved-component-root? obj)
                                      (ctk/instance-root? obj))
           duplicating-component? (or duplicating-component? (ctk/instance-head? obj))
           is-component-main?     (ctk/main-instance? obj)
           subinstance-head?      (ctk/subinstance-head? obj)
           instance-root?         (ctk/instance-root? obj)

           into-component?        (and duplicating-component?
                                       (ctn/in-any-component? objects parent))

           level-delta            (if (some? level-delta)
                                    level-delta
                                    (ctn/get-nesting-level-delta objects obj parent))
           new-shape-ref          (ctf/advance-shape-ref nil page libraries obj level-delta {:include-deleted? true})

           regenerate-component
           (fn [changes shape]
             (let [[_ changes] (generate-add-component-changes changes shape objects file-id (:id page) variant-props)]
               changes))

           new-obj
           (-> obj
               (assoc :id new-id
                      :name name
                      :parent-id parent-id
                      :frame-id frame-id)

               (cond-> (and (not instance-root?)
                            subinstance-head?
                            remove-swap-slot?)
                 (ctk/remove-swap-slot))

               (dissoc :shapes
                       :use-for-thumbnail)

               (cond-> (not is-component-root?)
                 (dissoc :main-instance))

               (cond-> into-component?
                 (dissoc :component-root))

               (cond-> (and (ctk/instance-head? obj)
                            (not into-component?))
                 (assoc :component-root true))

               (cond-> (or frame? group? bool?)
                 (assoc :shapes []))

               (cond-> (and (some? new-shape-ref)
                            (not= new-shape-ref (:shape-ref obj)))
                 (assoc :shape-ref new-shape-ref))

               (gsh/move delta)
               (d/update-when :interactions #(ctsi/remap-interactions % ids-map objects))

               (cond-> (ctl/grid-layout? obj)
                 (ctl/remap-grid-cells ids-map))

               (cond-> (ctk/is-variant-container? parent)
                 (assoc :variant-id parent-id))

               (cond-> (and (ctk/is-variant? obj) (not (ctk/is-variant-container? parent)))
                 (-> (assoc :name (ctv/variant-name-to-name obj))
                     (dissoc :variant-id))))

           new-obj (cond-> new-obj
                     (not duplicating-component?)
                     (ctk/detach-shape))

           ;; We want the first added object to touch it's parent, but not subsequent children
           changes (-> (pcb/add-object changes new-obj {:ignore-touched (and duplicating-component? child?)})
                       (pcb/amend-last-change #(assoc % :old-id (:id obj)))
                       (cond-> (ctl/grid-layout? objects (:parent-id obj))
                         (-> (pcb/update-shapes [(:parent-id obj)] ctl/assign-cells {:with-objects? true})
                             (pcb/reorder-grid-children [(:parent-id obj)]))))

           changes (cond-> changes
                     (and is-component-root? is-component-main?)
                     (regenerate-component new-obj))

           ;; This is needed for the recursive call to find the new object as parent
           page' (ctst/add-shape (:id new-obj)
                                 new-obj
                                 {:objects objects}
                                 (:frame-id new-obj)
                                 (:parent-id new-obj)
                                 nil
                                 true)]

       (reduce (fn [changes child]
                 (generate-duplicate-shape-change changes
                                                  (:objects page')
                                                  page
                                                  unames
                                                  update-unames!
                                                  ids
                                                  ids-map
                                                  child
                                                  delta
                                                  level-delta
                                                  libraries
                                                  library-data
                                                  file-id
                                                  (if frame? new-id frame-id)
                                                  new-id
                                                  duplicating-component?
                                                  true
                                                  (and remove-swap-slot?
                                                               ;; only remove swap slot of children when the current shape
                                                               ;; is not a subinstance head nor a instance root
                                                       (not subinstance-head?)
                                                       (not instance-root?))
                                                  variant-props))
               changes
               (map (d/getf objects) (:shapes obj)))))))

(defn generate-duplicate-changes
  "Prepare objects to duplicate: generate new id, give them unique names,
  move to the desired position, and recalculate parents and frames as needed."
  [changes all-objects page ids delta libraries library-data file-id & {:keys [variant-props]}]
  (let [shapes         (map (d/getf all-objects) ids)
        unames         (volatile! (cfh/get-used-names (:objects page)))
        update-unames! (fn [new-name] (vswap! unames conj new-name))
        all-ids        (reduce #(into %1 (cons %2 (cfh/get-children-ids all-objects %2))) (d/ordered-set) ids)

        ;; We need ids-map for remapping the grid layout. But when duplicating the guides
        ;; we calculate a new one because the components will have created new shapes.
        ids-map        (into {} (map #(vector % (uuid/next))) all-ids)

        changes (-> changes
                    (pcb/with-page page)
                    (pcb/with-objects all-objects))
        changes
        (->> shapes
             (reduce #(generate-duplicate-shape-change %1
                                                       all-objects
                                                       page
                                                       unames
                                                       update-unames!
                                                       ids
                                                       ids-map
                                                       %2
                                                       delta
                                                       nil
                                                       libraries
                                                       library-data
                                                       file-id
                                                       variant-props)
                     changes))

         ;; We need to check the changes to get the ids-map
        ids-map
        (into {}
              (comp
               (filter #(= :add-obj (:type %)))
               (map #(vector (:old-id %) (-> % :obj :id))))
              (:redo-changes changes))]

    (-> changes
        (generate-duplicate-flows shapes page ids-map)
        (generate-duplicate-guides shapes page ids-map delta))))

(defn generate-duplicate-changes-update-indices
  "Updates the changes to correctly set the indexes of the duplicated objects,
  depending on the index of the original object respect their parent."
  [changes objects ids]
  (let [;; index-map is a map that goes from parent-id => vector([id index-in-parent])
        index-map (reduce (fn [index-map id]
                            (let [parent-id    (get-in objects [id :parent-id])
                                  parent-index (cfh/get-position-on-parent objects id)]
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
