(ns app.common.logic.variants
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variant-properties :as clvp]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]))

(defn generate-rename-variant
  "Rename a variant set in one change vector.

   The builder already has the mains' page objects and library data.
   The stored name is `clean-path` of `name`, or \"(empty)\" when that
   path is blank. The board and every direct child take that name.
   Each component's path and name come from `split-group-name`."
  [changes variant-id name]
  (let [objects     (pcb/get-objects changes)
        data        (pcb/get-library-data changes)
        clean-name  (cpn/clean-path name)
        stored-name (if (empty? clean-name) "(empty)" clean-name)
        shape-ids   (cons variant-id (:shapes (get objects variant-id)))
        components  (cfv/find-variant-components data objects variant-id)]
    (reduce (fn [changes component]
              (let [[path item-name] (cpn/split-group-name stored-name)]
                (pcb/update-component changes
                                      (:id component)
                                      #(assoc % :path path :name item-name))))
            (pcb/update-shapes changes shape-ids #(assoc % :name stored-name))
            components)))

(defn generate-define-variant-container
  "One change vector. `name` is the string stored on the board.
   The component path and name are `split-group-name` of that string.
   Library data is refreshed before the first property is added."
  [changes variant-id main-id component-id name]
  (let [[path item-name] (cpn/split-group-name name)]
    (-> changes
        (pcb/update-shapes [variant-id]
                           #(assoc % :is-variant-container true :name name))
        (pcb/update-shapes [main-id]
                           #(assoc % :name name :variant-id variant-id))
        (pcb/update-component component-id
                              #(assoc % :variant-id variant-id
                                      :path path
                                      :name item-name)
                              {:apply-changes-local-library? true})
        (clvp/generate-add-new-property variant-id {:fill-values? true}))))

(defn generate-add-new-variant
  [changes shape variant-id new-component-id new-shape-id prop-num]
  (let [data                (pcb/get-library-data changes)
        objects             (pcb/get-objects changes)
        component-id        (:component-id shape)
        value               (str ctv/value-prefix
                                 (-> (cfv/extract-properties-values data objects variant-id)
                                     last
                                     :value
                                     count
                                     inc))

        [new-shape changes] (-> changes
                                (cll/generate-duplicate-component
                                 {:data data}
                                 component-id
                                 new-component-id
                                 {:new-shape-id new-shape-id :apply-changes-local-library? true}))]
    (cond-> changes
      (>= prop-num 0)
      (clvp/generate-update-property-value new-component-id prop-num value)
      :always
      (pcb/change-parent (:parent-id shape) [new-shape] 0))))

(defn- generate-path
  [path objects base-id shape]
  (let [get-type #(case %
                    :frame :container
                    :group :container
                    :rect  :shape
                    :circle :shape
                    :bool :shape
                    :path :shape
                    %)]
    (if (= base-id (:id shape))
      path
      (generate-path (str path " " (:name shape) (get-type (:type shape))) objects base-id (get objects (:parent-id shape))))))

(defn- add-unique-path
  "Adds a new property :shape-path to the shape, with the path of the shape.
   Suffixes like -1, -2, etc. are added to ensure uniqueness."
  [shapes objects base-id]
  (letfn [(unique-path [shape counts]
            (let [path (generate-path "" objects base-id shape)
                  num  (get counts path 1)]
              [(str path "-" num) (update counts path (fnil inc 1))]))]
    (first
     (reduce
      (fn [[result counts] shape]
        (let [[shape-path counts'] (unique-path shape counts)]
          [(conj result (assoc shape :shape-path shape-path)) counts']))
      [[] {}]
      shapes))))

(defn- move-to-root-first
  "Adds, before all the `changes` of a switch, a move of `shape` to the root,
   so it is not deleted with the original copy (its undo, run last, puts it
   back once the original copy is restored)."
  [changes page shape]
  (-> (pcb/empty-changes)
      (pcb/with-page page)
      (pcb/with-objects (:objects page))
      (pcb/change-parent uuid/zero [shape] 0 {:allow-altering-copies true})
      (pcb/concat-changes changes)
      ;; Keep the other keys of the switch changes, as :origin or :undo-group
      (merge (dissoc changes :redo-changes :undo-changes))))

(defn- drop-swapped-item
  "As part of the keep-touched process on a switch, delete a child on the
   original copy that was swapped (orig-swapped-child) and has no related
   shape on the new copy."
  [changes orig-swapped-child ldata page]
  (let [changes (move-to-root-first changes page orig-swapped-child)]
    ;; These are the page objects with the child moved to the root. The
    ;; delete replays all the changes on them, so the working objects end
    ;; as the ones after the switch, for the next steps.
    (-> (cls/generate-delete-shapes changes ldata page (pcb/get-objects changes)
                                    (d/ordered-set (:id orig-swapped-child))
                                    {:allow-altering-copies true})
        second)))

(defn- keep-swapped-item
  "As part of the keep-touched process on a switch, given a child on the original
   copy that was swapped (orig-swapped-child), and its related shape on the new copy
   (related-shape-in-new), move the orig-swapped-child into the parent of
   related-shape-in-new, fix its swap-slot if needed, and then delete
   related-shape-in-new.

   orig-swapped-child follows its parent through its constraints and, if the
   user didn't move it, takes the place of related-shape-in-new."
  [changes related-shape-in-new orig-swapped-child ldata page swap-ref-id]
  (let [objects         (pcb/get-objects changes)
        page-objects    (:objects page)
        prev-swap-slot  (ctk/get-swap-slot orig-swapped-child)
        current-parent  (get objects (:parent-id related-shape-in-new))
        pos             (d/index-of (:shapes current-parent) (:id related-shape-in-new))

        orig-child      (get page-objects (:id orig-swapped-child))
        ;; If the user didn't move it, it goes to the place of related-shape-in-new
        delta-fn        (when-not (ctk/touched-group? orig-child :geometry-group)
                          #(gpt/subtract (grc/rect->point (:selrect related-shape-in-new))
                                         (grc/rect->point (:selrect %))))]

    (-> (move-to-root-first changes page orig-swapped-child)

        ;; Move the previous shape to the new parent
        (pcb/change-parent (:parent-id related-shape-in-new) [orig-swapped-child] pos {:allow-altering-copies true})

        (cll/generate-follow-parent (ctn/make-container page :page)
                                    orig-child
                                    (get page-objects (:parent-id orig-child))
                                    current-parent
                                    delta-fn)

        ;; We need to update the swap slot only when it pointed
        ;; to the swap-ref-id. Oterwise this is a swapped item
        ;; inside a nested copy, so we need to keep it.
        (cond->
         (= prev-swap-slot swap-ref-id)
          (pcb/update-shapes
           [(:id orig-swapped-child)]
           #(-> %
                (ctk/remove-swap-slot)
                (ctk/set-swap-slot (:shape-ref related-shape-in-new)))))

        ;; Delete new non-swapped item
        (cls/generate-delete-shapes ldata page objects (d/ordered-set (:id related-shape-in-new)) {:allow-altering-copies true})
        second)))

(defn- child-of-swapped?
  "Check if any ancestor of a shape (between base-parent-id and shape) was swapped,
   in the copy itself or in an outer main (`outer-swaps`, by head id)"
  [shape objects base-parent-id outer-swaps]
  (let [ancestors (->> (ctn/get-parent-heads objects shape)
                       ;; Ignore ancestors ahead of base-parent
                       (drop-while #(not= base-parent-id (:id %)))
                       seq)
        num-ancestors (count ancestors)
        ;; Ignore first and last (base-parent and shape)
        ancestors (when (and ancestors (<= 3 num-ancestors))
                    (subvec (vec ancestors) 1 (dec num-ancestors)))]
    (some #(or (ctk/get-swap-slot %) (contains? outer-swaps (:id %))) ancestors)))

(defn- repoint-outer-swapped
  "A head swapped in an outer main (`orig-head`, `level` levels up its ref chain)
   has been moved into the new copy, in the place of `related-shape-in-new`.
   Make it and its children point to the swapped component instead of the
   outer main, and give the head the slot of `related-shape-in-new`."
  [changes page libraries orig-head level related-shape-in-new]
  (let [refs (ctf/get-advanced-refs nil (ctn/make-container page :page) libraries
                                    (:id orig-head) level orig-head)]
    ;; The working objects of `changes` lost the head when the original copy
    ;; was deleted, so take the shapes from the page
    (->> (-> (pcb/empty-changes)
             (pcb/with-page page)
             (pcb/with-objects (:objects page))
             (pcb/update-shapes (keys refs)
                                (fn [shape]
                                  (let [{:keys [shape-ref touched]} (get refs (:id shape))]
                                    (cond-> (assoc shape :shape-ref shape-ref :touched touched)
                                      (= (:id shape) (:id orig-head))
                                      (ctk/set-swap-slot (:shape-ref related-shape-in-new)))))
                                {:ignore-touched true}))
         (pcb/concat-changes changes))))

(defn- find-shape-ref-child-of
  "Get the shape referenced by the shape-ref of the near main of the shape,
   recursively repeated until find a shape-ref with parent-id as ancestor.
   It will return the shape or nil if it doesn't found any"
  [container libraries shape parent-id]
  (let [ref-shape             (ctf/find-ref-shape nil container libraries shape
                                                  :include-deleted? true
                                                  :with-context? true)

        ref-shape-container   (when ref-shape (:container (meta ref-shape)))
        ref-shape-parents-set (when ref-shape
                                (->> (cfh/get-parents-with-self (:objects ref-shape-container) (:id ref-shape))
                                     (into #{} d/xf:map-id)))]

    (if (or (nil? ref-shape) (contains? ref-shape-parents-set parent-id))
      ref-shape
      (find-shape-ref-child-of ref-shape-container libraries ref-shape parent-id))))

(defn- add-touched-from-ref-chain
  "Adds to the :touched attr of a shape the content of
   the :touched of all its chain of ref shapes"
  [container libraries shape]
  (let [new-touched (ctf/get-touched-from-ref-chain-until-target-ref container libraries shape nil)]
    (assoc shape :touched new-touched)))

(defn generate-keep-touched
  "This is used as part of the switch process, when you switch from
   an original-shape to a new-shape. It generate changes to
   copy the touched attributes on the shapes children of the original-shape
   into the related children of the new-shape.
   This relation is tricky. The shapes are related if:
   * On the main components, both have the same name (the name on the copies are ignored)
   * Both has the same type of ancestors, on the same order (see generate-path for the
     translation of the types)"
  [changes new-shape original-shape original-shapes page libraries ldata]
  (let [objects            (pcb/get-objects changes)
        container          (ctn/make-container page :page)
        page-objects       (:objects page)

        ;; The original-shape is in a copy. For the relation rules, we need the referenced
        ;; shape on the main component
        orig-base-ref-shape (ctf/find-remote-shape container libraries original-shape {:with-context? true})
        orig-ref-objects    (:objects (:container (meta orig-base-ref-shape)))

        ;; Heads swapped in an outer main (when the original-shape is nested
        ;; in another copy), with no swap of their own, by id: [level slot]
        outer-swaps        (ctf/get-outer-swaps container libraries original-shape)

        ;; Get the touched children of the original-shape, and the ones
        ;; swapped in an outer main.
        ;; Ignore children of swapped items, because
        ;; they will be moved without change when
        ;; managing their swapped ancestor
        orig-touched       (->> original-shapes
                                ;; Add to each shape also the touched of its ref chain
                                (map #(add-touched-from-ref-chain container libraries %))
                                (filter #(or (seq (:touched %)) (contains? outer-swaps (:id %))))
                                (remove
                                 #(child-of-swapped? %
                                                     page-objects
                                                     (:id original-shape)
                                                     outer-swaps)))

        ;; Adds a :shape-path attribute to the children of the new-shape,
        ;; that contains the type of its ancestors and its name
        new-shapes-w-path  (add-unique-path
                            (reverse (cfh/get-children-with-self objects (:id new-shape)))
                            objects
                            (:id new-shape))
        ;; Creates a map to quickly find a child of the new-shape by its shape-path
        new-shapes-map     (into {} (map (juxt :shape-path identity)) new-shapes-w-path)

        ;; Adds a :shape-path attribute to the children of the orig-ref-shape,
        ;; that contains the type of its ancestors and its name
        o-ref-shapes-wp    (add-unique-path
                            (reverse (cfh/get-children-with-self orig-ref-objects (:id orig-base-ref-shape)))
                            orig-ref-objects
                            (:id orig-base-ref-shape))

        ;; Creates a map to quickly find a child of the orig-ref-shape by its shape-path
        o-ref-shapes-p-map  (into {} (map (juxt :id :shape-path)) o-ref-shapes-wp)

        ;; Process each touched children of the original-shape
        [changes parents-of-swapped]
        (reduce
         (fn [[changes parent-of-swapped] orig-child-touched]
           (let [;; If the orig-child-touched was swapped, get its swap-slot.
                 ;; If it was swapped in an outer main, outer-swap is [level slot]
                 outer-swap     (get outer-swaps (:id orig-child-touched))
                 swap-slot      (or (ctk/get-swap-slot orig-child-touched) (second outer-swap))

                 ;; orig-child-touched is in a copy. Get the referenced shape on the main component
                 ;; If there is a swap slot, we will get the referenced shape in another way
                 orig-ref-shape (when-not swap-slot
                                  (find-shape-ref-child-of container libraries orig-child-touched (:id orig-base-ref-shape)))

                 orig-ref-id    (if swap-slot
                                  ;; If there is a swap slot, find the referenced shape id,
                                  ;; looking from the match of its parent on the main component
                                  (let [parent      (get page-objects (:parent-id orig-child-touched))
                                        parent-head (ctn/get-head-shape page-objects parent)
                                        parent-ref  (find-shape-ref-child-of container libraries parent-head
                                                                             (:id orig-base-ref-shape))]
                                    (when parent-ref
                                      (ctf/find-next-related-swap-shape-id parent-ref swap-slot libraries)))
                                  ;; If there is not a swap slot, get the id from the orig-ref-shape
                                  (:id orig-ref-shape))

                 ;; Get the shape path of the referenced main
                 shape-path     (get o-ref-shapes-p-map orig-ref-id)

                 ;; Get its related shape in the children of new-shape: the one that
                 ;; has the same shape-path
                 related-shape-in-new  (get new-shapes-map shape-path)

                 parents-of-swapped (if related-shape-in-new
                                      (conj parent-of-swapped (:parent-id related-shape-in-new))
                                      parent-of-swapped)
                 ;; If there is a related shape, keep its data
                 changes
                 (cond
                   (and swap-slot
                        (nil? related-shape-in-new)
                        (not= (:id orig-child-touched) (:id original-shape)))
                   ;; The swapped child has no place on the new copy
                   (drop-swapped-item changes orig-child-touched ldata page)

                   related-shape-in-new
                   (if swap-slot
                     ;; If the orig-child-touched was swapped, keep it
                     (cond-> (keep-swapped-item changes related-shape-in-new orig-child-touched
                                                ldata page orig-ref-id)
                       ;; If it was swapped in an outer main, point it to the swapped component
                       (some? outer-swap)
                       (repoint-outer-swapped page libraries
                                              (get page-objects (:id orig-child-touched))
                                              (first outer-swap) related-shape-in-new))
                     ;; If the orig-child-touched wasn't swapped, copy
                     ;; the touched attributes into it
                     (cll/update-attrs-on-switch
                      changes related-shape-in-new orig-child-touched
                      new-shape original-shape orig-ref-shape container))

                   :else
                   changes)]
             [changes parents-of-swapped]))
         [changes []]
         orig-touched)]
    [changes parents-of-swapped]))

