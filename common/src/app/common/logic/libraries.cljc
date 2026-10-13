;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.libraries
  #?(:cljs (:require-macros [app.common.logic.libraries :refer [shape-log container-log]]))
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.common :as gco]
   [app.common.logging :as log]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variant-properties :as clvp]
   [app.common.math :as mth]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.library :as ctl]
   [app.common.types.modifiers :as ctm]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.path.segment :as segment]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.typography :as cty]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

;; Add uuids here to filter logs to only show specific shapes or containers (and all shapes
;; contained in them).
(def log-shape-ids #{})
(def log-container-ids #{})

(def updatable-attrs
  (->> (keys ctk/sync-attrs)
       ;; We don't update the flex-child attrs
       (remove ctk/swap-keep-attrs)
       ;; We don't do automatic update of the `layout-grid-cells` property.
       (remove #(= :layout-grid-cells %))))

;; Flex-child layout attrs synced between main and copy (interactions are only kept on swap)
(def ^:private flex-child-attrs
  (disj ctk/swap-keep-attrs :interactions))

;; Attributes that place a shape in its parent
(def ^:private placement-attrs
  (conj flex-child-attrs :constraints-h :constraints-v :fixed-scroll))

;; Touched groups of the placement attributes and of the position
(def ^:private placement-groups
  (into #{:geometry-group} (map #(ctk/resolve-sync-group :rect %)) placement-attrs))

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
(declare sync-shape-recursive)
(declare direct-sync)
(declare inverse-sync)

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
(declare update-swapped-placement)
(declare orient-like)
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
  ids from all of them. Optionally set the component-file if the file where the
  new component will reside is different than the origin one."
  [component new-component-id new-component-file library-data force-id delta variant-id]
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
            ;; Link the new main to the new component, and re-root it
            ;; to the destination file when duplicating across files.
            ;; Only the outer main matches `(:id component)`, so
            ;; nested main-instances are not touched here.
            (= (:component-id new-shape) (:id component))
            (assoc :component-id new-component-id)

            (and (= (:component-id new-shape) (:id component))
                 (some? new-component-file)
                 (not= new-component-file (:component-file new-shape)))
            (assoc :component-file new-component-file)

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
  [changes library component-id new-component-id & {:keys [new-component-file new-shape-id apply-changes-local-library? delta new-variant-id page-id]}]
  (let [component          (ctkl/get-component (:data library) component-id)
        new-name           (:name component)

        main-instance-page (ctf/get-component-page (:data library) component)
        ;; Since variants, we can duplicate a component into another page
        target-page-id     (or page-id (:id main-instance-page))

        [new-main-instance-shape new-main-instance-shapes]
        (duplicate-component component new-component-id new-component-file (:data library) new-shape-id delta new-variant-id)]

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
              (ctsl/grid-layout? shape)
              (ctsl/assign-cells objects)))
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
(declare generate-rehead-swapped)

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

(defn- swap-content-root-id
  "The id of the main root a swapped head takes its content from."
  [libraries shape]
  (:main-instance-id (ctf/get-component libraries
                                        (:component-file shape)
                                        (:component-id shape)
                                        :include-deleted? true)))

(defn- rehead-swapped
  "Make a swapped head a plain copy of its own component, for when it leaves
  every copy level."
  [shape root-id]
  (-> shape
      (assoc :shape-ref root-id)
      (ctk/remove-swap-group)))

(defn- generate-rehead-swapped
  [changes container libraries shape]
  (if-let [root-id (swap-content-root-id libraries shape)]
    (pcb/update-shapes changes [(:id shape)] #(rehead-swapped % root-id))
    (generate-detach-immediate changes container (:id shape) (atom #{}))))

(defn- swapped-descendant-ids
  "The ids of the shapes below a swapped head in the subtree of the shape.
  They refer into the head's own component, so they do not change when the
  head changes its nesting level."
  [objects shape-id]
  (into #{}
        (comp (filter ctk/swapped?)
              (mapcat #(cfh/get-children-ids objects (:id %))))
        (cfh/get-children-with-self objects shape-id)))

(defn- inherited-touched
  "The touched groups a copy shape takes from the main shape it skips when it
  goes up a nesting level. A swapped head takes only the placement ones,
  since its content does not come from that main."
  [shape ref-touched]
  (if (ctk/swapped? shape)
    (set/intersection ref-touched placement-groups)
    ref-touched))

(defn- generate-advance-nesting-level
  [changes file container libraries shape-id]
  (log/trace :msg "  -> advance-nesting-level")
  (let [detached-ids (atom #{})
        unchanged-ids (swapped-descendant-ids (:objects container) shape-id)
        children  (->> (cfh/get-children-with-self (:objects container) shape-id)  ;; TODO: this function should be refactored to be a recursive tree traversal.
                       (remove #(contains? unchanged-ids (:id %))))
        skip-near (fn [changes shape]                                         ;;       this way we could shake the tree more easily when detaching shapes
                    (shape-log :trace (:id shape) container                   ;;       and perhaps even allow to recover nested instances that have been
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

;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the given file,
  that use assets of the given type in the given library.

  If an asset id is given, only shapes linked to this particular asset will
  be synchronized.

  If early-return? is true, stops as soon as the first change is generated."
  ([changes file-id asset-type asset-id library-id libraries current-file-id]
   (generate-sync-file changes file-id asset-type asset-id library-id libraries current-file-id false))
  ([changes file-id asset-type asset-id library-id libraries current-file-id early-return?]
   (assert (contains? #{:colors :components :typographies} asset-type))
   (assert (or (nil? asset-id) (uuid? asset-id)))
   (assert (uuid? file-id))
   (assert (uuid? library-id))

   (container-log :info asset-id
                  :msg "Sync file with library"
                  :asset-type asset-type
                  :asset-id asset-id
                  :file (pretty-file file-id libraries current-file-id)
                  :library (pretty-file library-id libraries current-file-id))

   (let [file (get-in libraries [file-id :data])]
     (loop [containers (ctf/object-containers-seq file)
            changes    changes]
       (let [container (first containers)]
         (if (or (nil? container)
                 (and early-return? (seq (:redo-changes changes))))
           changes
           (recur (next containers)
                  (pcb/concat-changes ;;TODO Remove concat changes
                   changes
                   (generate-sync-container (pcb/empty-changes nil)
                                            asset-type
                                            asset-id
                                            library-id
                                            container
                                            libraries
                                            current-file-id)))))))))

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
               (let [objects' (pcb/get-objects changes)
                     shape'   (get objects' (:id shape))]
                 ;; An earlier change of this loop may have deleted the shape (a broken
                 ;; swap slot can do it); then it is skipped.
                 (if shape'
                   (generate-sync-shape asset-type
                                        changes
                                        library-id
                                        container
                                        shape'
                                        libraries
                                        current-file-id)
                   changes)))
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
    (cts/uses-library-colors? shape library-id)
    (cts/uses-library-color? shape library-id color-id)))

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
        file      (get libraries current-file-id)]
    (generate-sync-shape-direct changes file libraries container shape-id false)))

(defmethod generate-sync-shape :colors
  [_ changes library-id _ shape libraries _]
  (shape-log :debug (:id shape) nil :msg "Sync colors of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some colors of the library. The value of the
  ;; color in the library is copied to the shape. It is a derived write, so it
  ;; touches no override of a copy.
  (let [library-colors (get-in libraries [library-id :data :colors])]
    (pcb/update-shapes changes
                       [(:id shape)]
                       #(ctl/sync-colors % library-id library-colors)
                       {:ignore-touched true})))

(defmethod generate-sync-shape :typographies
  [_ changes library-id container shape libraries _]
  (shape-log :debug (:id shape) nil :msg "Sync typographies of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some typographies of the library. The text
  ;; styles of the typography are copied to the shape. It is a derived write,
  ;; so it touches no override of a copy.
  (let [typographies  (get-in libraries [library-id :data :typographies])
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (select-keys typography txt/typography-fields))
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
                        :val new-content
                        :ignore-touched true}
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
                        :val old-content
                        :ignore-touched true}
                       {:type :set
                        :attr :position-data
                        :val nil}]})

        changes'    (-> changes
                        (update :redo-changes conj redo-change)
                        (update :undo-changes conj undo-change))]

    (if (= new-content old-content)
      changes
      (-> changes'
          (pcb/apply-changes-local)))))


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
      (if component
        (let [shape-main (if reset?
                           ;; the reset is against the ref-shape, not against the original shape of the component
                           (ctf/find-ref-shape file container libraries shape-inst)
                           (ctf/get-content-ref-shape library component shape-inst))]
          (sync-shape-recursive changes
                                direct-sync
                                {:container     container
                                 :component     component
                                 :library       library
                                 :file          file
                                 :libraries     libraries
                                 :root-inst     shape-inst
                                 :root-main     shape-main
                                 :reset?        reset?
                                 :initial-root? (:component-root shape-inst)}
                                shape-inst
                                shape-main))

        ;; If the component is not found, because the master component has been
        ;; deleted or the library unlinked, do nothing.
        changes)
      changes)))

(defn- find-main-container
  "Find the container that has the main shape.

  When walking up through nested component copies, each intermediate component
  may live in a different library/file. We resolve the right file-data for each
  component via `ctf/find-component-file` so we can locate components that span
  multiple libraries."
  [container-inst shape-inst shape-main library file libraries component]
  (loop [shape-inst' shape-inst
         library'    library
         component'  component]
    (let [container (ctf/get-component-container library' component')]
      (if (some? (ctn/get-shape container (:id shape-main)))
        container
        (let [parent      (ctn/get-shape container-inst (:parent-id shape-inst'))
              shape-inst' (ctn/get-head-shape (:objects container-inst) parent)
              next-file   (some-> shape-inst'
                                  :component-file
                                  (->> (ctf/find-component-file file libraries)))
              next-data   (some-> next-file :data)
              component'  (when next-data
                            (or (ctkl/get-component next-data (:component-id shape-inst'))
                                (ctkl/get-deleted-component next-data (:component-id shape-inst'))))]
          (if (some? component')
            (recur shape-inst' next-data component')
            nil))))))

(defn- sync-shape-recursive
  "Synchronize `shape-inst`, a shape of a copy, with `shape-main`, the main
  shape it refers to, then pair their children (`compare-children`) and walk
  each pair. `direction` says which way the values go and what each case
  writes: `direct-sync` (main to copy, a normal sync or a reset) or
  `inverse-sync` (copy to main). `ctx` holds what stays the same along the
  walk: the copy's `:container`, the `:component`, its `:library`, `:file`
  and `:libraries`, the roots `:root-inst` and `:root-main`, `:reset?` and
  `:initial-root?`.

  Each function of `direction` takes the changes and a step, which is `ctx`
  plus the pair (`:shape-inst`, `:shape-main`), the container of the main
  shape (`:main-container`) and their children (`:children-inst`,
  `:children-main`):
    - `:sync-shape` syncs the pair itself;
    - `:only-inst`, `:only-main`, `:swapped` and `:moved` handle the
      children `compare-children` does not walk (see its callbacks);
    - `:after-children` runs once the children are done.
  `:main-container` (ctx, shape-inst, shape-main -> container) finds the
  container of the main shape, and `:inverse?` goes to `compare-children`."
  [changes direction {:keys [container reset?] :as ctx} shape-inst shape-main]
  (shape-log :debug (:id shape-inst) container
             :msg (if (:inverse? direction) "Sync shape inverse recursive" "Sync shape direct recursive")
             :shape-inst (str (:name shape-inst) " " (pretty-uuid (:id shape-inst)))
             :component (:name (:component ctx)))

  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [main-container ((:main-container direction) ctx shape-inst shape-main)
          step           (assoc ctx
                                :shape-inst     shape-inst
                                :shape-main     shape-main
                                :main-container main-container
                                :children-inst  (vec (ctn/get-direct-children container shape-inst))
                                :children-main  (vec (ctn/get-direct-children main-container shape-main)))
          walk           (fn [changes child-inst child-main]
                           (sync-shape-recursive changes direction ctx child-inst child-main))]
      (as-> changes $
        ((:sync-shape direction) $ step)
        (compare-children $
                          shape-inst
                          (:children-inst step)
                          (:children-main step)
                          container
                          (partial (:only-inst direction) step)
                          (partial (:only-main direction) step)
                          walk
                          (partial (:swapped direction) step)
                          (partial (:moved direction) step)
                          (:inverse? direction)
                          (or (:inverse? direction) reset?))
        ((:after-children direction) $ step)))))

(def ^:private direct-sync
  "Sync from a main into its copy (see `sync-shape-recursive`). A normal sync
  leaves the copy's overrides (touched groups) as they are; a reset
  (`:reset?`) overwrites them and clears them. A reset that starts below the
  copy root sets `:remote-synced`."
  {:inverse?
   false

   :main-container
   (fn [{:keys [container library file libraries component]} shape-inst shape-main]
     (find-main-container container shape-inst shape-main library file libraries component))

   :sync-shape
   (fn [changes {:keys [shape-inst shape-main container library component root-inst root-main
                        reset? initial-root?]}]
     (let [omit-touched? (not reset?)]
       (cond-> changes
         :always
         (update-attrs shape-inst
                       shape-main
                       root-inst
                       root-main
                       container
                       omit-touched?)

         (ctsl/flex-layout? shape-main)
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

         (and initial-root? reset?)
         (change-remote-synced shape-inst container nil)

         (and (not initial-root?) reset?)
         (change-remote-synced shape-inst container true))))

   :only-inst
   (fn [{:keys [shape-inst container reset?]} changes child-inst]
     (shape-log :trace (:id child-inst) container
                :msg "Only inst"
                :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst))))
     (if-not (and (not reset?)
                  (contains? (:touched shape-inst)
                             :shapes-group))
       (remove-shape changes
                     child-inst
                     container
                     (not reset?))
       changes))

   :only-main
   (fn [{:keys [shape-inst container main-container children-main root-inst root-main reset?
                initial-root?]}
        changes child-main]
     (shape-log :trace (:id child-main) main-container
                :msg "Only main"
                :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
     (if-not (and (not reset?)
                  (contains? (:touched shape-inst)
                             :shapes-group))
       (add-shape-to-instance changes
                              child-main
                              (d/index-of children-main
                                          child-main)
                              main-container
                              container
                              root-inst
                              root-main
                              (not reset?)
                              (and (not initial-root?) reset?))
       changes))

   :swapped
   (fn [{:keys [container root-inst root-main]} changes child-inst child-main]
     (shape-log :trace (:id child-inst) container
                :msg "Swapped"
                :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst)))
                :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
     (if (ctk/swapped? child-inst)
       (update-swapped-placement changes
                                 child-inst
                                 child-main
                                 root-inst
                                 root-main
                                 container)
       changes))

   :moved
   (fn [{:keys [container children-inst children-main reset?]} changes child-inst child-main]
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
      (not reset?)))

   :after-children
   (fn [changes {:keys [shape-inst shape-main library component reset?]}]
     (cond-> changes
       (ctsl/grid-layout? shape-inst)
       (update-grid-copy-attrs
        (:id shape-inst)
        shape-main
        library
        component
        (not reset?))))})

(defn generate-rename-component
  "Generate the changes for rename the component with the given id, in the current file library."
  [changes id new-name library-data]
  (let [[path name]   (cpn/split-group-name new-name)]
    (-> changes
        (pcb/with-library-data library-data)
        (pcb/update-component id #(assoc % :path path :name name)))))

(defn generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children."
  [changes file libraries container shape-id]
  (shape-log :debug shape-id container :msg "Sync shape inverse" :shape (str shape-id))
  (let [shape-inst     (ctn/get-shape container shape-id)
        library        (dm/get-in libraries [(:component-file shape-inst) :data])
        component      (ctkl/get-component library (:component-id shape-inst))

        changes        (cond-> changes
                         (and component (contains? (:touched shape-inst) :name-group))
                         (generate-rename-component (:component-id shape-inst) (:name shape-inst) library))]

    (if component
      (sync-shape-recursive changes
                            inverse-sync
                            {:container     container
                             :component     component
                             :library       library
                             :file          file
                             :libraries     libraries
                             :root-inst     shape-inst
                             :root-main     (ctf/get-component-root library component)
                             :initial-root? (:component-root shape-inst)}
                            shape-inst
                            (ctf/find-remote-shape container libraries shape-inst))
      changes)))

(def ^:private inverse-sync
  "Sync from a copy into its main, the update main of a copy (see
  `sync-shape-recursive`). The copy's values, overrides included, go into
  the main; the copy's touched groups move to the main (the copy keeps a
  swap). Started below the copy root, it sets the copy's `:remote-synced`."
  {:inverse?
   true

   :main-container
   (fn [{:keys [library component]} _ _]
     (ctf/get-component-container library component))

   :sync-shape
   (fn [changes {:keys [shape-inst shape-main container main-container root-inst root-main
                        initial-root?]}]
     (cond-> changes
       :always
       (-> (update-attrs shape-main
                         shape-inst
                         root-main
                         root-inst
                         main-container
                         false)
           (change-touched shape-inst
                           shape-main
                           container
                           {:reset-touched? true :keep-swap? true})
           (change-touched shape-main
                           shape-inst
                           main-container
                           {:copy-touched? true}))

       (ctsl/flex-layout? shape-main)
       (update-flex-child-main-attrs shape-main
                                     shape-inst
                                     main-container
                                     container
                                     false)

       (ctsl/grid-layout? shape-main)
       (update-grid-main-attrs shape-main
                               shape-inst
                               main-container
                               container
                               false)

       initial-root?
       (change-remote-synced shape-inst container nil)

       (not initial-root?)
       (change-remote-synced shape-inst container true)))

   :only-inst
   (fn [{:keys [component container main-container children-inst root-inst root-main libraries]}
        changes child-inst]
     (add-shape-to-main changes
                        child-inst
                        (d/index-of children-inst
                                    child-inst)
                        component
                        main-container
                        container
                        root-inst
                        root-main
                        libraries))

   :only-main
   (fn [{:keys [main-container]} changes child-main]
     (remove-shape changes
                   child-main
                   main-container
                   false))

   :swapped
   (fn [{:keys [container]} changes child-inst child-main]
     (shape-log :trace (:id child-inst) container
                :msg "Match slot"
                :child-inst (str (:name child-inst) " " (pretty-uuid (:id child-inst)))
                :child-main (str (:name child-main) " " (pretty-uuid (:id child-main))))
     ;; For now we don't make any sync here.
     changes)

   :moved
   (fn [{:keys [main-container children-inst children-main]} changes child-inst child-main]
     (move-shape
      changes
      child-main
      (d/index-of children-main child-main)
      (d/index-of children-inst child-inst)
      main-container
      false))

   :after-children
   (fn [changes {:keys [shape-inst]}]
     ;; The inverse sync may be made on a component that is inside a
     ;; remote library. We need to separate changes that are from
     ;; local and remote files.
     (let [check-local (fn [change]
                         (cond-> change
                           (= (:id change) (:id shape-inst))
                           (assoc :local-change? true)))]
       (-> changes
           (update :redo-changes (partial mapv check-local))
           (update :undo-changes (partial map check-local)))))})


;; ---- Operation generation helpers ----

(defn- compare-children
  "Pair the children of a copy shape with the children of its main and call
  the callback of each case. A child pairs with the main child it refers to;
  a swapped child pairs that way too, and takes `swapped-cb` in a normal sync,
  `both-cb` in a reset, and does not pair in an inverse sync, so the main
  child it replaces is removed and the swapped child is added in its place."
  [changes shape-inst children-inst children-main container-inst only-inst-cb only-main-cb both-cb swapped-cb moved-cb inverse? reset?]
  (shape-log :trace (:id shape-inst) container-inst :msg "Compare children")
  (let [pair?
        (fn [child-main child-inst]
          (and (ctk/is-main-of? child-main child-inst)
               (not (and inverse? (ctk/swapped? child-inst)))))

        synced-pair?
        (fn [child-main child-inst]
          (and (ctk/is-main-of? child-main child-inst)
               (or reset? (not (ctk/swapped? child-inst)))))]
    (loop [children-inst (seq (or children-inst []))
           children-main (seq (or children-main []))
           changes       changes]
      (let [child-inst (first children-inst)
            child-main (first children-main)]
        (shape-log :trace (:id shape-inst) container-inst
                   :msg "Comparing"
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
          (if (pair? child-main child-inst)
            (recur (next children-inst)
                   (next children-main)
                   (if (synced-pair? child-main child-inst)
                     (both-cb changes child-inst child-main)
                     (swapped-cb changes child-inst child-main)))

            (let [child-inst' (d/seek #(pair? child-main %) children-inst)
                  child-main' (d/seek #(pair? % child-inst) children-main)]
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
                  (let [synced? (synced-pair? child-main' child-inst)]
                    (recur (next children-inst)
                           (remove #(= (:id %) (:id child-main')) children-main)
                           (cond-> changes
                             synced?
                             (both-cb child-inst child-main')
                             (not synced?)
                             (swapped-cb child-inst child-main')
                             :always
                             (moved-cb child-inst child-main'))))
                  (let [synced? (synced-pair? child-main child-inst')]
                    (recur (remove #(= (:id %) (:id child-inst')) children-inst)
                           (next children-main)
                           (cond-> changes
                             synced?
                             (both-cb child-inst' child-main)
                             (not synced?)
                             (swapped-cb child-inst' child-main)
                             :always
                             (moved-cb child-inst' child-main)))))))))))))

(defn- add-shape-to-instance
  [changes component-shape index component-page container root-instance root-main omit-touched? set-remote-synced?]
  (shape-log :info (:id component-shape) component-page
             :msg (str "ADD [P " (pretty-uuid (:id container)) "] "
                       (:name component-shape)
                       " "
                       (pretty-uuid (:id component-shape))))
  (let [component-parent-shape (ctn/get-shape component-page (:parent-id component-shape))
        ;; a swapped root refers to the shape it replaces, not to the main root
        parent-shape           (if (= (:id component-parent-shape) (:id root-main))
                                 root-instance
                                 (d/seek #(ctk/is-main-of? component-parent-shape %)
                                         (cfh/get-children-with-self (:objects container)
                                                                     (:id root-instance))))
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
      (-> changes'
          (pcb/apply-changes-local)))))

(defn- add-shape-to-main
  [changes shape index component component-container page root-instance root-main libraries]
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

        ;; A swapped head pushed to the main replaces the main child it refers to.
        ;; Inside a copy of the main it takes the position of that child;
        ;; elsewhere it is a plain copy of its own component.
        swap-in-copy?    (and (ctk/swapped? shape)
                              (seq (ctn/get-parent-copy-heads (:objects component-container)
                                                              component-parent-shape)))
        replaced         (when swap-in-copy?
                           (ctn/get-shape component-container (:shape-ref shape)))

        update-new-shape (fn [new-shape original-shape]
                           (cond-> (reposition-shape new-shape
                                                     root-instance
                                                     root-main)
                             (and (= (:id original-shape) (:id shape))
                                  (ctk/swapped? shape)
                                  (not swap-in-copy?))
                             (-> (assoc :shape-ref (swap-content-root-id libraries shape))
                                 (ctk/remove-swap-group))))

        ;; the copy now follows the main, swap included
        update-original-shape (fn [original-shape new-shape]
                                (-> original-shape
                                    (assoc :shape-ref (:id new-shape))
                                    (ctk/remove-swap-group)))

        [new-head new-shapes updated-shapes]
        (ctst/clone-shape shape
                          (:id component-parent-shape)
                          (get page :objects)
                          :update-new-shape update-new-shape
                          :update-original-shape update-original-shape
                          :frame-id (if (cfh/frame-shape? component-parent-shape)
                                      (:id component-parent-shape)
                                      (:frame-id component-parent-shape)))

        new-shapes
        (if swap-in-copy?
          (let [objects  (d/index-by :id new-shapes)
                position (ctf/find-ref-shape nil component-container libraries replaced
                                             :include-deleted? true
                                             :with-context? true)]
            (mapv (ctf/take-position nil libraries objects new-head (:shape-ref replaced) position)
                  new-shapes))
          new-shapes)

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

    (-> changes'
        (pcb/apply-changes-local))))

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
      (-> changes'
          (pcb/apply-changes-local)))))

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
                                                  :allow-altering-copies true}))
                     (update :undo-changes conj (make-change
                                                 container
                                                 {:type :mov-objects
                                                  :parent-id (:parent-id shape)
                                                  :shapes [(:id shape)]
                                                  :index index-before
                                                  :ignore-touched true
                                                  :allow-altering-copies true})))]

    (if (and (ctk/touched-group? parent :shapes-group) omit-touched?)
      changes
      (-> changes'
          (pcb/apply-changes-local)))))

(defn change-touched
  "Update the touched groups of the dest shape: clear them (`reset-touched?`,
  keeping the swap with `keep-swap?`), add those of the origin shape
  (`copy-touched?`) or leave them as they are."
  [changes dest-shape origin-shape container
   {:keys [reset-touched? keep-swap? copy-touched?] :as options}]
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
                          (and reset-touched? keep-swap? (ctk/swapped? dest-shape))
                          #{:swap-group}

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
                                           :touched (:touched dest-shape)}]}))
            (pcb/apply-changes-local))))))

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
                                         :remote-synced (:remote-synced shape)}]}))
          (pcb/apply-changes-local)))))

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
                                                     :ignore-touched true}]}))
          (pcb/apply-changes-local)))))

(defn- generate-update-tokens
  [changes container dest-shape origin-shape touched omit-touched? valid-attrs]
  ;; valid-attrs is a set of attrs to consider on the update. If it is nil, it will consider all the attrs
  (let [attrs
        (->> (keys ctk/sync-attrs)
             ;; We don't update the flex-child attrs
             (remove #(= :layout-grid-cells %)))

        ;; the styles of a text live in its content, so its tokens follow
        ;; the text override groups
        text?   (cfh/text-shape? dest-shape)
        touched (cond-> touched
                  text? (-> set (into (txt/text-overrides touched))))

        applied-tokens
        (reduce (fn [applied-tokens attr]
                  (let [sync-group  (or (when text? (txt/style-unit-of attr))
                                        (ctk/resolve-sync-group (:type origin-shape) attr)
                                        (ctk/resolve-sync-group (:type dest-shape) attr))
                        token-attrs (cto/shape-attr->token-attrs attr)]
                    (if  (and (or (not omit-touched?) (not (touched sync-group)))
                              (or (empty? valid-attrs) (contains? valid-attrs attr)))
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
                                        :shapes all-parents})])
        (pcb/apply-changes-local))))


(defn- text-content-with-overrides
  "The content of a text that takes the overrides `groups` (text override
   groups, see `txt/text-overrides`) from `own-content` and everything else
   from `source-content`."
  [own-content source-content groups]
  (if (empty? groups)
    source-content
    (txt/merge-overrides own-content source-content groups)))

(defn- add-update-attr-operations
  [attr dest-shape roperations uoperations attr-val]
  (let [roperation {:type :set
                    :attr attr
                    :val attr-val
                    :ignore-touched true}
        uoperation {:type :set
                    :attr attr
                    :val (get dest-shape attr)
                    :ignore-touched true}]
    [(conj roperations roperation)
     (conj uoperations uoperation)]))

(defn- check-detached-main
  [changes dest-shape origin-shape]
  ;; Only for direct updates (from main to copy). Check if the main shape
  ;; has been detached. If so, the copy shape must be unheaded (i.e. converted
  ;; into a normal copy and not a nested instance).
  (if (and (= (:shape-ref dest-shape) (:id origin-shape))
           (ctk/subcopy-head? dest-shape)
           (not (ctk/instance-head? origin-shape)))
    (pcb/update-shapes changes [(:id dest-shape)] ctk/unhead-shape {:ignore-touched true})
    changes))

(defn- check-swapped-main
  [changes dest-shape origin-shape]
  ;; Only for direct updates (from main to copy). Check if the main shape
  ;; has been swapped. If so, the new component-id and component-file must
  ;; be put into the copy.
  (if (and (= (:shape-ref dest-shape) (:id origin-shape))
           (ctk/instance-head? dest-shape)
           (ctk/instance-head? origin-shape)
           (or (not= (:component-id dest-shape) (:component-id origin-shape))
               (not= (:component-file dest-shape) (:component-file origin-shape))))
    (pcb/update-shapes changes [(:id dest-shape)]
                       #(assoc %
                               :component-id (:component-id origin-shape)
                               :component-file (:component-file origin-shape))
                       {:ignore-touched true})
    changes))

(defn- attr-operations
  "The redo and undo `:set` operations, as `[roperations uoperations]`, that
   copy attributes into `dest-shape`. For each of `updatable-attrs`,
   `attr-ops` gives the `[attr value]` pairs to set, in order: none to
   leave it alone, and possibly some derived attributes before it."
  [dest-shape attr-ops]
  (reduce (fn [operations attr]
            (reduce (fn [[roperations uoperations] [attr value]]
                      (add-update-attr-operations attr dest-shape roperations uoperations value))
                    operations
                    (attr-ops attr)))
          [[] '()]
          updatable-attrs))

(defn- sync-attr-operations
  "The `[attr value]` pairs that sync `attr` from `origin-shape`, a main
   shape placed like `dest-shape`, into `dest-shape`, its copy. With
   `omit-touched?`, an attribute whose group is in `touched` (the copy's
   overrides) is left as it is; a text's content then takes the main's
   letters and styles the copy did not override."
  [dest-shape origin-shape touched omit-touched? attr]
  (let [sync-group
        (or (ctk/resolve-sync-group (:type origin-shape) attr)
            (ctk/resolve-sync-group (:type dest-shape) attr))

        ;; position-data is a special case because can be affected by
        ;; :geometry-group and :content-group so, if the
        ;; position-data changes but the geometry is touched
        ;; we need to reset the position-data so it's
        ;; calculated again
        reset-pos-data?
        (and (cfh/text-shape? origin-shape)
             (= attr :position-data)
             (not= (:position-data origin-shape) (:position-data dest-shape))
             (touched :geometry-group))

        ;; On texts, when we want to omit the touched attrs, both text (the actual letters)
        ;; and attrs (bold, font, etc) are in the same attr :content.
        ;; If only one of them is touched, we want to address this case and
        ;; only update the untouched one
        text-content-change?
        (and omit-touched?
             (cfh/text-shape? origin-shape)
             (= :content attr)
             (touched sync-group))

        skip?
        (or (= (get origin-shape attr) (get dest-shape attr))
            (and (touched sync-group)
                 omit-touched?
                 ;; When it is a text-partial-change, we should generate operations
                 ;; even when omit-touched? is true, but updating only the text or
                 ;; the attributes, omiting the other part
                 (not text-content-change?)))

        attr-val
        (when-not skip?
          (cond
            ;; If position data changes and the geometry group is touched
            ;; we need to put to nil so we can regenerate it
            reset-pos-data?
            nil

            text-content-change?
            (text-content-with-overrides (:content dest-shape)
                                         (:content origin-shape)
                                         (txt/text-overrides touched))

            :else
            (get origin-shape attr)))]

    (cond
      ;; If the final attr-value is the actual value, skip
      (or skip? (= attr-val (get dest-shape attr)))
      []

      ;; On a text-partial-change, we want to force a position-data reset
      ;; so it's calculated again
      text-content-change?
      [[:position-data nil] [attr attr-val]]

      :else
      [[attr attr-val]])))

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
        touched      (get dest-shape :touched #{})

        [roperations uoperations]
        (attr-operations dest-shape
                         #(sync-attr-operations dest-shape origin-shape touched omit-touched? %))]

    (cond-> changes
      (seq roperations)
      (add-update-attr-changes dest-shape container roperations uoperations)
      :always
      (check-detached-main dest-shape origin-shape)
      :always
      (check-swapped-main dest-shape origin-shape)
      :always
      (generate-update-tokens container dest-shape origin-shape touched omit-touched? nil))))

(defn- set-path-new-values
  [current-shape prev-shape transform]
  (let [new-content   (segment/transform-content
                       (:content current-shape)
                       (gmt/transform-in (gpt/point 0 0) transform))
        new-points    (-> (segment/content->selrect new-content)
                          (grc/rect->points))
        points-center (gco/points->center new-points)
        new-selrect   (gsh/calculate-selrect new-points points-center)
        shape         (assoc current-shape
                             :content new-content
                             :points new-points
                             :selrect new-selrect)

        prev-center   (segment/content-center (:content prev-shape))
        delta         (gpt/subtract points-center (first new-points))
        new-pos       (gpt/subtract prev-center delta)]
    (gsh/absolute-move shape new-pos)))

(defn- switch-path-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   ref-shape            ; The referenced shape on the main component
                        ; before the switch
   attr]
  (let [old-width (-> ref-shape :selrect :width)
        new-width (-> prev-shape :selrect :width)

        old-height (-> ref-shape :selrect :height)
        new-height (-> prev-shape :selrect :height)

        transform (-> (gpt/point (/ new-width old-width)
                                 (/ new-height old-height))
                      (gmt/scale-matrix))

        shape     (set-path-new-values current-shape prev-shape transform)]
    (get shape attr)))


(defn- switch-fixed-layout-geom-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   origin-shape         ; The original shape
   attr]
  ;; When there is a layout with fixed h or v sizing, we need
  ;; to keep the width/height (and recalculate selrect and points)
  (let [prev-width     (-> prev-shape :selrect :width)
        current-width  (-> current-shape :selrect :width)
        origin-width   (-> origin-shape :selrect :width)

        prev-height    (-> prev-shape :selrect :height)
        current-height (-> current-shape :selrect :height)
        origin-height  (-> origin-shape :selrect :height)

        x              (-> current-shape :selrect :x)
        y              (-> current-shape :selrect :y)


        h-sizing       (:layout-item-h-sizing prev-shape)
        v-sizing       (:layout-item-v-sizing prev-shape)

        final-width    (if (= :fix h-sizing)
                         current-width
                         (if (= origin-width current-width)
                           prev-width       ;; same-size: preserve override
                           current-width))  ;; different-size: use new component's

        final-height   (if (= :fix v-sizing)
                         current-height
                         (if (= origin-height current-height)
                           prev-height      ;; same-size: preserve override
                           current-height)) ;; different-size: use new component's

        selrect        (assoc (:selrect current-shape)
                              :width final-width
                              :height final-height
                              :x x
                              :y y
                              :x1 x
                              :y1 y
                              :x2 (+ x final-width)
                              :y2 (+ y final-height))]

    (case attr
      :width
      final-width

      :height
      final-height

      :selrect
      selrect

      :points
      (-> selrect
          (grc/rect->points)
          (gsh/transform-points
           (grc/rect->center selrect)
           (or (:transform current-shape) (gmt/matrix)))))))


(defn- switch-geom-change-value
  [prev-shape current-shape attr]
  ;; Composite geometry stores absolute coordinates. When preserving a size
  ;; override across variants, keep the target variant's position and only carry
  ;; the previous dimensions; otherwise :x/:y can disagree with :selrect/:points.
  (let [prev-selrect (:selrect prev-shape)
        current-selrect (:selrect current-shape)
        final-width (:width prev-selrect)
        final-height (:height prev-selrect)
        x (:x current-selrect)
        y (:y current-selrect)
        selrect (assoc current-selrect
                       :width final-width
                       :height final-height
                       :x x
                       :y y
                       :x1 x
                       :y1 y
                       :x2 (+ x final-width)
                       :y2 (+ y final-height))]
    (case attr
      :selrect
      selrect

      :points
      (-> selrect
          (grc/rect->points)
          (gsh/transform-points
           (grc/rect->center selrect)
           (or (:transform current-shape) (gmt/matrix)))))))


(defn- equal-geometry?
  "Returns true when the value of `attr` in `shape` is considered equal
   to the corresponding value in `origin-shape`, ignoring positional
   displacement (x/y).
   For :selrect we compare width/height only;
   for :points we normalise each vector so the first point is the
   origin before comparing.
   For :content on path shapes we compare the bounding-box width/height
   of the path segments, again ignoring absolute position.

   Comparisons use `mth/close?` (and `gpt/close?` for points) rather than
   exact `=` because `previous-shape` here may carry sub-pixel drift from
   interactive transform modifiers (e.g. an alt-drag duplicate of a
   variant whose children are component instances). Without tolerance
   this guard would miss equivalent geometries and let the `:else` branch
   in `update-attrs-on-switch` carry stale `:selrect`/`:points` from the
   pre-switch shape onto the freshly instantiated target."
  [shape origin-shape attr]
  (or (and (= attr :selrect)
           (mth/close? (-> shape :selrect :width)  (-> origin-shape :selrect :width))
           (mth/close? (-> shape :selrect :height) (-> origin-shape :selrect :height)))
      (and (= attr :points)
           (let [normalize-pts (fn [pts]
                                 (when (seq pts)
                                   (let [f (first pts)]
                                     (mapv #(gpt/subtract % f) pts))))
                 a (normalize-pts (get shape :points))
                 b (normalize-pts (get origin-shape :points))]
             (and (= (count a) (count b))
                  (every? identity (map gpt/close? a b)))))
      (and (= attr :content)
           (cfh/path-shape? shape)
           (let [ca (:content shape)
                 cb (:content origin-shape)]
             (and (some? ca) (some? cb)
                  (let [selrect-a (segment/content->selrect ca)
                        selrect-b (segment/content->selrect cb)]
                    (and (some? selrect-a) (some? selrect-b)
                         (mth/close? (:width selrect-a) (:width selrect-b))
                         (mth/close? (:height selrect-a) (:height selrect-b)))))))))

(defn- switch-attr-operations
  "The `[attr value]` pairs that carry `attr` over a variant switch, from
   `previous-shape`, the copy shape before the switch placed like
   `current-shape`, into `current-shape`, its clean copy of the new
   variant. Only an override (a group in `touched`) is carried, and only
   where `origin-ref-shape`, the main shape before the switch, and the new
   copy agree on the attribute (decision (a) of the component contract);
   geometry carries the override's size at the new position."
  [current-shape previous-shape origin-ref-shape touched text-auto? attr]
  (let [sync-group
        (ctk/resolve-sync-group (:type previous-shape) attr)

        skip?
        (or
         ;; For auto text, avoid copying geometry-driven attrs on switch.
         (and text-auto?
              (contains? #{:points :selrect :width :height :position-data} attr))

         ;; If the attribute is not valid for the destiny, don't copy it
         (not (cts/is-allowed-switch-keep-attr? attr (:type current-shape)))

         ;; If the values are already equal, don't copy them
         (= (get previous-shape attr) (get current-shape attr))

         ;; If :selrect/:points values are already equal ignoring displacement,
         ;; don't copy them
         (equal-geometry? previous-shape origin-ref-shape attr)

         ;; If the attr is not touched, don't copy it
         (not (touched sync-group))

         ;; If both variants (origin and destiny) don't have the same value
         ;; for that attribute, don't copy it.
         ;; Exceptions: :points :selrect and :content can be different
         ;;
         ;; Sample:
         ;; 1. We have a variant with C1 (bg red) and C2 (bg blue).
         ;; 2. We make a copy of C1 called Copy.
         ;; 3. We set Copy’s bg to green (so it it has an override on the bg).
         ;; 4. We switch Copy to use C2 as base.
         ;; 5. The bg of Copy now is blue (we ignore the override)
         (and
          (not (contains? #{:points :selrect :content} attr))
          (not= (get origin-ref-shape attr) (get current-shape attr)))

         ;; The :content attr cant't be copied to elements of different type
         (and (= attr :content) (not= (:type previous-shape) (:type current-shape))))

        ;; On texts, both text (the actual letters)
        ;; and attrs (bold, font, etc) are in the same attr :content.
        ;; If only one of them is touched, we want to address this case and
        ;; only update the untouched one
        text-change?
        (and (not skip?)
             (cfh/text-shape? current-shape)
             (cfh/text-shape? previous-shape)
             (= :content attr)
             (touched sync-group))

        path-change?
        (and (= :path (:type current-shape))
             (contains? #{:points :selrect :content} attr))

        ;; position-data is a special case because can be affected by :geometry-group and :content-group
        ;; so, if the position-data changes but the geometry is touched we need to reset the position-data
        ;; so it's calculated again
        reset-pos-data?
        (and (not skip?)
             (cfh/text-shape? previous-shape)
             (or (= attr :position-data) (= attr :selrect))
             (not= (:position-data previous-shape) (:position-data current-shape))
             (touched :geometry-group))

        skip?
        (or skip?
            ;; If we are going to reset the position data, skip the selrect attr
            (and reset-pos-data? (= attr :selrect))
            ;; Avoid copying composite geometry attrs (:selrect/:points) when the
            ;; variant dimensions differ but neither sizing is :fix. Without this,
            ;; :width/:height are correctly skipped by the check above
            ;; but :selrect/:points would still carry the old override dimensions,
            ;; leaving the shape in an inconsistent state. When :fix sizing is
            ;; present, switch-fixed-layout-geom-change-value handles the composite
            ;; attrs and must NOT be bypassed. Path shapes are also handled
            ;; separately via switch-path-change-value.
            (and (contains? #{:selrect :points} attr)
                 (not path-change?)
                 (not (or (= :fix (:layout-item-h-sizing previous-shape))
                          (= :fix (:layout-item-v-sizing previous-shape))))
                 (or (not= (get origin-ref-shape :width) (get current-shape :width))
                     (not= (get origin-ref-shape :height) (get current-shape :height)))))

        attr-val
        (when-not skip?
          (cond
            ;; If position data changes and the geometry group is touched
            ;; we need to put to nil so we can regenerate it
            reset-pos-data?
            nil

            ;; Each text override that the old and the new main agree on is
            ;; carried over the new main's content
            text-change?
            (text-content-with-overrides (:content previous-shape)
                                         (:content current-shape)
                                         (set/difference (txt/text-overrides touched)
                                                         (txt/override-groups (:content origin-ref-shape)
                                                                              (:content current-shape))))

            path-change?
            (switch-path-change-value previous-shape
                                      current-shape
                                      origin-ref-shape
                                      attr)

            (and (or (= :fix (:layout-item-h-sizing previous-shape))
                     (= :fix (:layout-item-v-sizing previous-shape)))
                 (contains? #{:points :selrect :width :height} attr))
            (switch-fixed-layout-geom-change-value previous-shape current-shape origin-ref-shape attr)

            (and (contains? #{:points :selrect} attr)
                 (not path-change?))
            (switch-geom-change-value previous-shape current-shape attr)

            :else
            (get previous-shape attr)))]

    ;; If the final attr-value is the actual value, skip
    (if (or skip? (= attr-val (get current-shape attr)))
      []
      (cond-> []
        ;; On a text-change, we want to force a position-data reset
        ;; so it's calculated again
        text-change?
        (conj [:position-data nil])

        ;; On a rotation operation we need to keep also the transformation matrixes
        (= attr :rotation)
        (conj [:transform (:transform previous-shape)]
              [:transform-inverse (:transform-inverse previous-shape)])

        :always
        (conj [attr attr-val])))))

(defn update-attrs-on-switch
  "Copy attributes that have changed in the shape previous to the switch
   to the current shape (post switch). Used only on variants switch"
  ;; NOTE: This function have similitudes but is very different to
  ;; update-attrs:
  ;; In components (update-attrs), the source shape is "clean", and the destination
  ;; shape may have touched elements that shouldn't be overwritten.
  ;; In variants (update-attrs-on-switch), the destination shape is "clean",
  ;; and it's the source shape that may have touched elements, and we only want
  ;; to copy those touched elements.
  [changes current-shape previous-shape current-root prev-root origin-ref-shape container]
  (let [;; We need to sync only the position relative to the origin of the component.
        ;; (see update-attrs for a full explanation)
        previous-shape (reposition-shape previous-shape prev-root current-root)
        touched        (get previous-shape :touched #{})
        text-auto?     (and (cfh/text-shape? current-shape)
                            (contains? #{:auto-height :auto-width} (:grow-type current-shape)))

        [roperations uoperations]
        (attr-operations current-shape
                         #(switch-attr-operations current-shape previous-shape origin-ref-shape
                                                  touched text-auto? %))

        updated-attrs (into #{} (comp (filter #(= :set (:type %)))
                                      (map :attr))
                            roperations)
        updated-sync-groups (into #{}
                                  (keep #(ctk/resolve-sync-group (:type previous-shape) %))
                                  updated-attrs)
        new-content (some #(when (= (:attr %) :content) (:val %)) roperations)
        new-touched (set/union (or (:touched current-shape) #{})
                               updated-sync-groups
                               (when (and (cfh/text-shape? current-shape) (some? new-content))
                                 (txt/override-groups (:content current-shape) new-content)))
        roperations (into [{:type :set-touched :touched new-touched}] roperations)
        uoperations (into (list {:type :set-touched :touched (:touched current-shape)}) uoperations)]
    (cond-> changes
      (> (count roperations) 1)
      (-> (add-update-attr-changes current-shape container roperations uoperations)
          (generate-update-tokens container current-shape previous-shape touched false updated-attrs)))))

(defn- propagate-attrs
  "Helper that puts the origin attributes (attrs) into dest but only if
  not touched the group or if omit-touched? flag is true"
  [dest origin attrs omit-touched?]
  (let [touched (get dest :touched #{})]
    (->> attrs
         (reduce
          (fn [dest attr]
            (let [sync-group
                  (or (ctk/resolve-sync-group (:type origin) attr)
                      (ctk/resolve-sync-group (:type dest) attr))]
              (cond-> dest
                (or (not (touched sync-group))
                    (not omit-touched?))
                (assoc attr (get origin attr)))))
          dest))))

(defn generate-take-placement
  "Give the swapped copy head `shape-inst` the placement of `placed`, the
  shape whose position it takes, expressed in the same coordinates: the
  attributes that place it in its parent (position, rotation, flips,
  constraints and layout item attributes) that the user did not change. Its
  content comes from its own component. With `layout-placed?`, the position
  is left to the layout.
  `objects` holds the subtree of `shape-inst`, which moves with it."
  [changes shape-inst placed objects layout-placed?]
  (let [touched    (get shape-inst :touched #{})
        untouched? (fn [attr]
                     (not (touched (ctk/resolve-sync-group (:type shape-inst) attr))))

        placement  (reduce (fn [attrs attr]
                             (let [value (get placed attr)]
                               (if (and (untouched? attr)
                                        (not= value (get shape-inst attr)))
                                 (assoc attrs attr value)
                                 attrs)))
                           {}
                           placement-attrs)

        position   (fn [shape]
                     (gpt/point (dm/get-in shape [:selrect :x])
                                (dm/get-in shape [:selrect :y])))
        delta      (gpt/subtract (position placed) (position shape-inst))
        move?      (and (untouched? :x)
                        (not layout-placed?)
                        (not (gpt/almost-zero? delta)))
        orient     (when (untouched? :rotation)
                     (orient-like shape-inst placed (gsh/shape->center shape-inst)))]
    (cond-> changes
      (seq placement)
      (pcb/update-shapes [(:id shape-inst)]
                         (fn [shape]
                           (reduce-kv (fn [shape attr value]
                                        (if (nil? value)
                                          (dissoc shape attr)
                                          (assoc shape attr value)))
                                      shape
                                      placement))
                         {:ignore-touched true})

      (or move? (some? orient))
      (pcb/update-shapes (cfh/get-children-ids-with-self objects (:id shape-inst))
                         #(cond-> %
                            (some? orient) (orient)
                            move?          (gsh/move delta))
                         {:ignore-touched true}))))

(defn- update-swapped-placement
  "Synchronizes a swapped copy head with the main shape it replaces, in the
  attributes that place it in its parent."
  [changes shape-inst shape-main root-inst root-main container]
  (let [objects (:objects container)]
    (generate-take-placement changes
                             shape-inst
                             (reposition-shape shape-main root-main root-inst)
                             objects
                             (and (ctsl/any-layout-immediate-child? objects shape-inst)
                                  (not (ctsl/position-absolute? shape-inst))))))

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

                 (contains? #{:auto :fix} (:layout-item-v-sizing shape-main))
                 (propagate-attrs shape-main #{:layout-item-v-sizing} omit-touched?)))
             {:ignore-touched true})

            ;; Update the child flex properties from the parent
            (pcb/update-shapes
             (:shapes shape-copy)
             (fn [child-copy]
               (let [child-main (ctf/get-ref-shape main-container main-component child-copy)]
                 (-> child-copy
                     (propagate-attrs child-main flex-child-attrs omit-touched?))))
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

                 (contains? #{:auto :fix} (:layout-item-v-sizing shape-copy))
                 (propagate-attrs shape-copy #{:layout-item-v-sizing} omit-touched?)))
             {:ignore-touched true})

            ;; Updates the children properties from the parent
            (pcb/update-shapes
             (:shapes shape-main)
             (fn [child-main]
               (let [child-copy (ctf/get-shape-in-copy copy-container child-main shape-copy)]
                 (-> child-main
                     (propagate-attrs child-copy flex-child-attrs omit-touched?))))
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
         (let [ids-map
               (into {}
                     (comp
                      (map #(get objects %))
                      (keep
                       (fn [copy-shape]
                         (let [main-shape (ctf/get-ref-shape main-container main-component copy-shape)]
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
               main-cells (-> shape-main (ctsl/remap-grid-cells ids-map) :layout-grid-cells)]
           (-> shape-copy
               (assoc :layout-grid-cells
                      (ctsl/merge-cells main-cells copy-cells omit-touched?))
               (ctsl/assign-cells objects))))
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
               (let [new-cells (-> (ctsl/remap-grid-cells shape-copy ids-map) :layout-grid-cells)]
                 (assoc shape-main :layout-grid-cells new-cells)))
             {:ignore-touched true}))]
    (pcb/concat-changes changes new-changes)))

(defn- reposition-shape
  "Expresses the shape (belonging to the origin-root instance) in the frame of the
  dest-root instance, making the geometry of both instances directly comparable —
  and copyable.

  If the dest root's geometry is NOT overridden (touched), the instance follows
  the origin's transformation verbatim (including rotation and flips), so a
  translation by the roots' (untransformed) position delta suffices — position is
  free per-instance placement.

  If the dest root's geometry IS overridden (e.g. the user rotated the copy as a
  whole), the instance keeps its own placement transform, so the origin shape is
  additionally transformed by the roots' relative transformation (rotation /
  flips) around the dest root center — geometric changes then land expressed in
  the dest instance's own frame instead of wiping its placement."
  [shape origin-root dest-root]
  (let [shape-pos        (fn [shape]
                           (gpt/point (dm/get-in shape [:selrect :x])
                                      (dm/get-in shape [:selrect :y])))

        origin-root-pos  (shape-pos origin-root)
        dest-root-pos    (shape-pos dest-root)
        delta            (gpt/subtract dest-root-pos origin-root-pos)

        shape            (gsh/move shape delta)]
    (if-not (ctk/touched-group? dest-root :geometry-group)
      shape
      (let [origin-transform (d/nilv (:transform origin-root) (gmt/matrix))
            dest-transform   (d/nilv (:transform dest-root) (gmt/matrix))
            rel-transform    (gmt/multiply dest-transform (gmt/inverse origin-transform))]
        (if ^boolean (gmt/unit? rel-transform)
          shape
          ;; The roots differ in rotation/flips: rotate the whole (already moved)
          ;; shape around the dest root center by the roots' relative transform.
          ;; The :rotation attribute delta is fed through the :modifiers path so
          ;; apply-transform keeps it consistent with the resulting matrix.
          (let [center       (grc/rect->center (:selrect dest-root))
                rel-rotation (mod (- (d/nilv (:rotation dest-root) 0)
                                     (d/nilv (:rotation origin-root) 0))
                                  360)]
            (-> shape
                (assoc-in [:modifiers :rotation] rel-rotation)
                (gsh/apply-transform (gmt/transform-in center rel-transform))
                (dissoc :modifiers))))))))

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

(defn- orient-like
  "A function that turns each shape of the subtree of `head` around `center`,
  so that `head` takes the flips and then the rotation of `target`; nil when
  they already agree."
  [head target center]
  (let [flip-x? (not= (true? (:flip-x head)) (true? (:flip-x target)))
        flip-y? (not= (true? (:flip-y head)) (true? (:flip-y target)))
        flip    (when (or flip-x? flip-y?)
                  (ctm/resize-modifiers (gpt/point (if flip-x? -1 1) (if flip-y? -1 1)) center))
        head'   (cond-> head (some? flip) (gsh/transform-shape flip))
        angle   (- (d/nilv (:rotation target) 0) (d/nilv (:rotation head') 0))
        rotate? (not (mth/close? (mod angle 360) 0 0.01))
        angle   (if (> angle 180) (- angle 360) angle)]
    (when (or (some? flip) rotate?)
      (fn [shape]
        (cond-> shape
          (some? flip)
          (gsh/transform-shape flip)

          rotate?
          (as-> shape (gsh/transform-shape shape (ctm/rotation-modifiers shape center angle))))))))

(defn- generate-keep-orientation
  "Give the new head of a swap, and its subtree, the flips and the rotation
  of the shape it replaces, around the head's own center. They come from
  its parent, as when the parent is rotated, and are not overrides."
  [changes new-shape shape]
  (if-let [orient (orient-like new-shape shape (gsh/shape->center new-shape))]
    (pcb/update-shapes changes
                       (cfh/get-children-ids-with-self (pcb/get-objects changes) (:id new-shape))
                       orient
                       {:ignore-touched true})
    changes))

(defn- generate-swap-in-copy
  "A swap inside a copy keeps the position: the new head takes the place of
  the main shape the old one referred to."
  [changes new-shape shape file page libraries]
  (let [file*    {:id (:id file) :data file}
        objects  (pcb/get-objects changes)
        position (ctf/find-ref-shape file* page libraries shape
                                     :include-deleted? true
                                     :with-context? true)]
    (pcb/update-shapes changes
                       (cfh/get-children-ids-with-self objects (:id new-shape))
                       (ctf/take-position file* libraries objects new-shape (:shape-ref shape) position)
                       {:ignore-touched true})))

(defn generate-new-shape-for-swap
  [changes shape file page libraries id-new-component index target-cell keep-props-values]
  (let [objects      (:objects page)
        position     (gpt/point (:x shape) (:y shape))
        changes      (-> changes
                         (pcb/with-objects objects))
        position     (-> position (with-meta {:cell target-cell}))
        parent       (get objects (:parent-id shape))
        inside-copy? (ctk/in-component-copy? parent)

        [new-shape changes]
        ;; When we make a swap of an item, there can be copies which refer to that item,
        ;; so we want to assign the item id to the new instanciated copy, to mantain that reference
        (generate-instantiate-component changes
                                        objects
                                        (:id file)
                                        id-new-component
                                        position
                                        page
                                        libraries
                                        (:id shape)
                                        (:parent-id shape)
                                        (:frame-id shape)
                                        {(:id shape) (:id shape)} ;; keep the id of the original shape
                                        {:force-frame? true})

        changes
        (cond-> changes
          inside-copy?
          (generate-swap-in-copy new-shape shape file page libraries))

        new-shape
        (get (pcb/get-objects changes) (:id new-shape))

        changes
        (generate-keep-orientation changes new-shape shape)]

    [new-shape (-> changes
                   ;; Restore the properties
                   (pcb/update-shapes [(:id new-shape)] #(d/patch-object % keep-props-values))

                   ;; We need to set the same index as the original shape
                   (pcb/change-parent (:parent-id shape) [new-shape] index {:allow-altering-copies true
                                                                            :ignore-touched true})
                   (change-touched new-shape
                                   shape
                                   (ctn/make-container page :page)
                                   {}))]))

(defn generate-component-swap
  [changes objects shape file page libraries id-new-component
   index target-cell keep-props-values ignore-swapped?]
  (let [;; When we keep the touched properties, we can't delete the
        ;; swapped children (we will keep them too)
        ignore-swapped-fn
        (if ignore-swapped?
          #(ctk/swapped? (get objects %))
          (constantly false))

        ;; In the cases where the swapped shape was the first element of the masked group it would make the group to loose the
        ;; mask property as part of the sanitization check on generate-delete-shapes, passing "ignore-mask" to prevent this
        [all-parents changes]
        (-> changes
            (cls/generate-delete-shapes
             file page objects (d/ordered-set (:id shape))
             {:allow-altering-copies true :ignore-children-fn ignore-swapped-fn :ignore-mask true :ignore-flows-for #{(:id shape)}}))
        [new-shape changes]
        (-> changes
            (generate-new-shape-for-swap shape file page libraries id-new-component index target-cell keep-props-values))]
    [new-shape all-parents changes]))

(defn- maybe-sync
  [c enabled? done? f]
  (if (and enabled? (not (done? c)))
    (f c)
    c))

(defn generate-sync-file-changes
  ([changes undo-group asset-type file-id asset-id library-id libraries current-file-id]
   (generate-sync-file-changes changes undo-group asset-type file-id asset-id library-id libraries current-file-id false))
  ([changes undo-group asset-type file-id asset-id library-id libraries current-file-id early-return?]
   (let [sync-components?   (or (nil? asset-type) (= asset-type :components))
         sync-colors?       (or (nil? asset-type) (= asset-type :colors))
         sync-typographies? (or (nil? asset-type) (= asset-type :typographies))
         done?              (fn [c] (and early-return? (seq (:redo-changes c))))]
     (-> (pcb/set-undo-group changes undo-group)
         (maybe-sync sync-components? done? #(generate-sync-file % file-id :components asset-id library-id libraries current-file-id early-return?))
         (maybe-sync sync-colors? done? #(generate-sync-file % file-id :colors asset-id library-id libraries current-file-id early-return?))
         (maybe-sync sync-typographies? done? #(generate-sync-file % file-id :typographies asset-id library-id libraries current-file-id early-return?))))))


(defn generate-reset-component
  "Reset the copy head to its near main. A swapped head turns back into the
  component of the main shape it refers to, keeping its id."
  [changes file-full libraries container id]
  (-> changes
      (pcb/with-container container)
      (pcb/with-objects (:objects container))
      (generate-sync-shape-direct file-full libraries container id true)))

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
  [changes library component base-pos parent page-id into-new-variant? new-component-file]
  (let [component-page   (ctpl/get-page (:data library) (:main-instance-page component))
        objects          (:objects component-page)
        component-shape  (get objects (:main-instance-id component))
        orig-pos         (gpt/point (:x component-shape) (:y component-shape))
        delta            (gpt/subtract base-pos orig-pos)
        new-component-id (uuid/next)
        [shape changes]  (generate-duplicate-component changes
                                                       library
                                                       (:id component)
                                                       new-component-id
                                                       {:apply-changes-local-library? true
                                                        :delta delta
                                                        :new-variant-id (if into-new-variant? nil (:id parent))
                                                        :page-id page-id
                                                        :new-component-file new-component-file})
        value             (when into-new-variant?
                            (str ctv/value-prefix
                                 (-> (cfv/extract-properties-values (:data library) objects (:id parent))
                                     last
                                     :value
                                     count
                                     inc)))]

    [shape
     (cond-> changes
       into-new-variant?
       (clvp/generate-make-shapes-variant [shape] parent)

       ;; If it has the same parent, update the value of the last property
       (and into-new-variant? (= (:variant-id component) (:id parent)))
       (clvp/generate-update-property-value new-component-id (-> component :variant-properties count dec) value)

       :always
       (pcb/change-parent (:id parent) [shape] 0))]))


(defn generate-duplicate-component-change
  [changes objects page main parent-id frame-id delta libraries library-data ids-map & {:keys [new-component-file]}]
  (let [main-id        (:id main)
        component-id   (:component-id main)
        ;; Source library file id (where the component was originally
        ;; defined). Renamed from `file-id` to make the contrast with
        ;; `new-component-file` explicit when duplicating across files.
        source-file-id (:component-file main)
        component      (ctf/get-component libraries source-file-id component-id)
        pos            (as-> (gsh/move main delta) $
                         (gpt/point (:x $) (:y $)))

        parent         (get objects parent-id)


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
        (cond
          (nil? component)
          (restore-component)

          (and (ctk/is-variant? main) in-variant-container?)
          (duplicate-variant changes
                             (get libraries source-file-id)
                             component
                             pos
                             parent
                             (:id page)
                             false
                             new-component-file)

          (ctk/is-variant-container? parent)
          (duplicate-variant changes
                             (get libraries source-file-id)
                             component
                             pos
                             parent
                             (:id page)
                             true
                             new-component-file)
          :else
          (generate-instantiate-component changes
                                          objects
                                          source-file-id
                                          component-id
                                          pos
                                          page
                                          libraries
                                          main-id
                                          parent-id
                                          frame-id
                                          ids-map
                                          {}))]
    changes))

(defn generate-duplicate-shape-change
  ([changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id]
   (generate-duplicate-shape-change changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id (:frame-id obj) (:parent-id obj) false false nil))
  ([changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id variant-props]
   (generate-duplicate-shape-change changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id (:frame-id obj) (:parent-id obj) false false variant-props))

  ([changes objects page unames update-unames! ids ids-map obj delta level-delta libraries library-data file-id frame-id parent-id duplicating-component? child? variant-props]
   (cond
     (nil? obj)
     changes

     (ctf/is-main-of-known-component? obj libraries)
     (generate-duplicate-component-change changes objects page obj parent-id frame-id delta libraries library-data ids-map {:new-component-file file-id})

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

           into-component?        (and duplicating-component?
                                       (ctn/in-any-component? objects parent))

           level-delta            (if (some? level-delta)
                                    level-delta
                                    (ctn/get-nesting-level-delta objects obj parent))

           ;; a swapped head that leaves every copy level
           rehead?                (and (ctk/swapped? obj)
                                       (pos? level-delta)
                                       (>= level-delta
                                           (->> (ctn/get-parent-copy-heads objects obj)
                                                (remove #(= (:id %) (:id obj)))
                                                (count))))
           new-shape-ref          (if rehead?
                                    (swap-content-root-id libraries obj)
                                    (ctf/advance-shape-ref nil page libraries obj level-delta {:include-deleted? true}))

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

               (cond-> rehead?
                 (ctk/remove-swap-group))

               (gsh/move delta)
               (d/update-when :interactions #(ctsi/remap-interactions % ids-map objects))

               (cond-> (ctsl/grid-layout? obj)
                 (ctsl/remap-grid-cells ids-map))

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
                       (cond-> (ctsl/grid-layout? objects (:parent-id obj))
                         (-> (pcb/update-shapes [(:parent-id obj)] ctsl/assign-cells {:with-objects? true})
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
                                                  ;; the children of a swapped head refer into its own component
                                                  (if (ctk/swapped? obj) 0 level-delta)
                                                  libraries
                                                  library-data
                                                  file-id
                                                  (if frame? new-id frame-id)
                                                  new-id
                                                  duplicating-component?
                                                  true
                                                  variant-props))
               changes
               (map (d/getf objects) (:shapes obj)))))))

(defn advance-copied-objects
  "The clipboard's `objects` (`selected` and their children, copied from
   `page` of `file`) with the copies nested in other copies advanced: each
   copy head that the copy takes out of `n` copy levels, and its children,
   get the shape ref `n` levels up the ref chain and the touched groups of
   the chain they skip, so they keep the values the skipped mains gave them."
  [file page libraries objects selected]
  (let [container     (ctn/make-container page :page)
        advance-shape (fn [level-delta objects shape]
                        (let [new-shape-ref (ctf/advance-shape-ref file page libraries shape level-delta
                                                                   {:include-deleted? true})]
                          (cond-> objects
                            (and (some? new-shape-ref) (not= new-shape-ref (:shape-ref shape)))
                            (-> (assoc-in [(:id shape) :shape-ref] new-shape-ref)
                                (assoc-in [(:id shape) :touched]
                                          (set/union
                                           (or (:touched shape) #{})
                                           (inherited-touched
                                            shape
                                            (ctf/get-touched-from-ref-chain-until-target-ref
                                             container libraries shape new-shape-ref))))))))
        detach-copy   (fn [objects shape]
                        (reduce #(update %1 %2 ctk/detach-shape)
                                objects
                                (cfh/get-children-ids-with-self objects (:id shape))))
        advance-copy  (fn [objects shape]
                        (if (and (ctk/instance-head? shape) (not (ctk/main-instance? shape)))
                          (let [level-delta (ctn/get-nesting-level-delta (:objects page) shape (get-in page [:objects uuid/zero]))]
                            (cond
                              (not (pos? level-delta))
                              objects

                              ;; the copy leaves every copy level
                              (ctk/swapped? shape)
                              (if-let [root-id (swap-content-root-id libraries shape)]
                                (update objects (:id shape) rehead-swapped root-id)
                                (detach-copy objects shape))

                              :else
                              (let [unchanged-ids (swapped-descendant-ids objects (:id shape))]
                                (reduce (partial advance-shape level-delta)
                                        objects
                                        (->> (cfh/get-children-with-self objects (:id shape))
                                             (remove #(contains? unchanged-ids (:id %))))))))
                          objects))]
    (reduce advance-copy
            objects
            (mapcat #(ctn/get-child-heads objects %) selected))))

(defn generate-duplicate-changes
  "Prepare objects to duplicate: generate new id, give them unique names,
  move to the desired position, and recalculate parents and frames as needed."
  [changes all-objects page ids delta libraries library-data file-id & {:keys [variant-props alt-duplication?]}]
  (let [shapes         (map (d/getf all-objects) ids)
        unames         (volatile! (cfh/get-used-names (:objects page)))
        update-unames! (fn [new-name] (vswap! unames conj new-name))
        all-ids        (reduce #(into %1 (cons %2 (cfh/get-children-ids all-objects %2))) (d/ordered-set) ids)

        ;; We need ids-map for remapping the grid layout. But when duplicating the guides
        ;; we calculate a new one because the components will have created new shapes.
        ids-map        (into {} (map #(vector % (uuid/next))) all-ids)


        ;; If there is an alt-duplication we change to root
        ;; For variants so the copy is made as a child of root
        ;; This is because inside a variant-container can't be a copy
        ;; For other shape this way the layout won't be changed when duplicated
        ;; and if you move outside the layout will not change
        shapes  (map (fn [shape]
                       (cond-> shape
                         alt-duplication?
                         (assoc :parent-id uuid/zero :frame-id uuid/zero)))
                     shapes)


        changes (-> changes
                    (pcb/with-page page)
                    (pcb/with-objects all-objects)
                    (pcb/with-library-data library-data))

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
