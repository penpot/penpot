;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.duplicate
  "Duplicate components and the shapes that hold mains and copies, with the
   flows and guides of their frames."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.instances :as cli]
   [app.common.logic.libraries :as cll]
   [app.common.logic.variant-properties :as clvp]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

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
               (cli/prepare-restore-component changes
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
          (cli/generate-instantiate-component changes
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
                                    (cll/swap-content-root-id libraries obj)
                                    (ctf/advance-shape-ref nil page libraries obj level-delta {:include-deleted? true}))

           regenerate-component
           (fn [changes shape]
             (let [[_ changes] (cli/generate-add-component-changes changes shape objects file-id (:id page) variant-props)]
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
                                           (cli/inherited-touched
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
                              (if-let [root-id (cll/swap-content-root-id libraries shape)]
                                (update objects (:id shape) cli/rehead-swapped root-id)
                                (detach-copy objects shape))

                              :else
                              (let [unchanged-ids (cli/swapped-descendant-ids objects (:id shape))]
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
