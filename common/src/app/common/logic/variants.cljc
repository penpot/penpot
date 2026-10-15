(ns app.common.logic.variants
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.swap :as clsw]
   [app.common.logic.variant-properties :as clvp]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]))

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
                                (cld/generate-duplicate-component
                                 {:data data}
                                 component-id
                                 new-component-id
                                 {:new-shape-id new-shape-id :apply-changes-local-library? true}))]
    (cond-> changes
      (>= prop-num 0)
      (clvp/generate-update-property-value new-component-id prop-num value)
      :always
      (pcb/change-parent (:parent-id shape) [new-shape] 0))))

;; The board of a variant: a frame with rounded corners and a dashed purple
;; border, which lays its members out in a row when it has a layout.
(def ^:private variant-container-attrs
  {:is-variant-container true
   :r1 20
   :r2 20
   :r3 20
   :r4 20
   :layout-item-absolute true})

(def ^:private variant-container-layout-attrs
  {:layout-item-h-sizing :auto
   :layout-item-v-sizing :auto
   :layout-padding {:p1 30 :p2 30 :p3 30 :p4 30}
   :layout-gap     {:row-gap 0 :column-gap 20}})

(def ^:private variant-container-stroke
  {:stroke-alignment :inner
   :stroke-style :solid
   :stroke-color "#bb97d8"
   :stroke-opacity 1
   :stroke-width 2})

(defn generate-transform-in-variant
  "Turn the component of the main `main-id` into the first member of the new
  variant `variant-id`: a board around the main, `delta` from it (by default
  30 px up and left, or none in a layout), named after the first item of the
  component's path, with one property per other item of the path, each item
  its value (`Property 1`, with value `Value 1`, when the path has one item).
  `prefix`, the first items of the path, counts as one item, and `wrapper?`
  puts the path under `Component`. With `layout?` the board lays its members
  out in a row and hugs them, and with `new-shape-id` a second member,
  `new-component-id`, is added."
  [changes main-id variant-id {:keys [delta prefix wrapper? layout? new-component-id new-shape-id]}]
  (let [objects      (pcb/get-objects changes)
        main         (get objects main-id)
        component-id (:component-id main)
        path         (cond-> (cpn/split-path (cond->> (:name main) wrapper? (str "Component/")))
                       (seq prefix)
                       (->> (drop (count prefix))
                            (cons (cpn/join-path prefix))
                            vec))
        name         (first path)
        num-props    (max 1 (dec (count path)))
        delta        (or delta
                         (if (ctsl/any-layout? (get objects (:parent-id main)))
                           (gpt/point 0 0)
                           (gpt/point -30 -30)))

        update-shapes
        (fn [changes ids update-fn & {:as options}]
          (cls/generate-update-shapes changes ids update-fn (pcb/get-objects changes) options))

        ;; the member is a duplicate of the main as the changes above left it,
        ;; so the page of the library data takes their objects
        add-member
        (fn [changes]
          (let [objects   (pcb/get-objects changes)
                data      (-> (pcb/get-library-data changes)
                              (assoc-in [:pages-index (pcb/get-page-id changes) :objects] objects))
                component (ctkl/get-component data component-id)]
            (-> changes
                (pcb/with-library-data data)
                (generate-add-new-variant (get objects main-id) variant-id new-component-id new-shape-id
                                          (dec (count (:variant-properties component)))))))]
    (as-> changes $
      (cond-> $
        (not= name (:name main))
        ;; applied to the library data, so the updates below keep it
        (pcb/update-component component-id
                              #(let [[path name] (cpn/split-group-name name)]
                                 (assoc % :path path :name name))
                              {:apply-changes-local-library? true}))
      (second (cfsh/prepare-create-artboard-from-selection $ variant-id nil (pcb/get-objects $)
                                                           [main-id] nil nil false nil delta))
      (update-shapes $ [variant-id] #(assoc % :fills (types.fills/create)))
      (cond-> $
        layout?
        (-> (update-shapes [variant-id] (cls/layout-initializer :flex false true) :with-objects? true)
            (update-shapes [main-id] #(dissoc % :constraints-h :constraints-v))))
      (update-shapes $ [variant-id] #(merge % variant-container-attrs {:name name}
                                            (when layout? variant-container-layout-attrs)))
      (update-shapes $ [main-id] #(assoc % :name name :variant-id variant-id))
      (update-shapes $ [variant-id] #(update % :strokes (fn [strokes] (into [variant-container-stroke] strokes)))
                     :attrs [:strokes])
      (pcb/update-component $ component-id #(assoc % :variant-id variant-id)
                            {:apply-changes-local-library? true})
      (reduce (fn [changes _] (clvp/generate-add-new-property changes variant-id {:fill-values? true}))
              $
              (range num-props))
      ;; the other items of the path are the values of the properties
      (reduce (fn [changes pos] (clvp/generate-update-property-value changes component-id pos (nth path (inc pos))))
              $
              (if (> (count path) 1) (range num-props) []))
      (cond-> $ (some? new-shape-id) (add-member))
      (update-shapes $ [variant-id] #(dissoc % :layout-item-absolute)))))

(defn- common-prefix
  "The items the sequences `xs` start with in common."
  [xs]
  (->> (apply map vector xs)
       (take-while #(apply = %))
       (map first)
       vec))

(defn- generate-move-into
  "Move the shapes `ids` into `parent-id`, at `index`, as the layers panel
  does: leaving out those whose parent moves too, and `parent-id` itself or
  any shape it is inside."
  [changes ids parent-id index]
  (let [objects (pcb/get-objects changes)
        ids     (->> (cfh/clean-loops objects ids)
                     (remove #(cfh/is-parent? objects parent-id %)))]
    (cls/generate-relocate changes parent-id index ids)))

(defn generate-combine-as-variants
  "Combine the components of the mains `ids` into the new variant
  `variant-id`: the first one becomes a variant (`generate-transform-in-variant`)
  whose board takes in the others, in the order of `ids`, each where it was,
  pinned to the board's top left. The board goes to the end of the shape
  the mains have in common, and is named with the path items their names
  share, or `Component` when they share none. Its size is left to the
  caller: the rect of the mains, 30 px larger on each side."
  [changes ids variant-id]
  (let [objects       (pcb/get-objects changes)
        shapes        (mapv objects ids)
        rect          (grc/join-rects (map :selrect shapes))
        prefix        (common-prefix (map #(cpn/split-path (:name %)) shapes))
        first-shape   (first shapes)
        common-parent (->> ids
                           (map #(reverse (cfh/get-parent-ids objects %)))
                           (common-prefix)
                           (last))
        index         (-> (get objects common-parent) :shapes count inc)]
    (as-> changes $
      (generate-transform-in-variant $ (first ids) variant-id
                                     {:delta    (gpt/point (- (:x rect) (:x first-shape) 30)
                                                           (- (:y rect) (:y first-shape) 30))
                                      :prefix   prefix
                                      :wrapper? (empty? prefix)})
      (generate-move-into $ (set (rest ids)) variant-id 0)
      ;; the members in the order of `ids`: the board's children go the other
      ;; way, and a change of parent at index 0 reverses the shapes it takes
      (let [children (set (get-in (pcb/get-objects $) [variant-id :shapes]))
            members  (into [] (comp (filter children) (map (pcb/get-objects $))) ids)]
        (cond-> $
          (> (count members) 1)
          (pcb/change-parent variant-id members 0)))
      (cls/generate-update-shapes $ ids
                                  #(assoc % :constraints-h :left :constraints-v :top :fixed-scroll false)
                                  (pcb/get-objects $) {})
      (generate-move-into $ #{variant-id} common-parent index))))

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

(defn- keep-swapped-item
  "As part of the keep-touched process on a switch, given a child on the original
   copy that was swapped (orig-swapped-child), and its related shape on the new copy
   (related-shape-in-new), move the orig-swapped-child into the parent of
   related-shape-in-new, make it take the place of related-shape-in-new, and
   then delete related-shape-in-new"
  [changes related-shape-in-new orig-swapped-child ldata page libraries]
  (let [;; Before to the swap, temporary move the previous
        ;; shape to the root panel to avoid problems when
        ;; the previous parent is deleted.
        before-changes (-> (pcb/empty-changes)
                           (pcb/with-page page)
                           (pcb/with-objects (:objects page))
                           (pcb/change-parent uuid/zero [orig-swapped-child] 0 {:allow-altering-copies true}))

        ;; the child at the root, where the undo of the next move takes it
        child-at-root  (get (pcb/get-objects before-changes) (:id orig-swapped-child))

        current-parent (get (pcb/get-objects changes) (:parent-id related-shape-in-new))
        pos            (d/index-of (:shapes current-parent) (:id related-shape-in-new))

        ;; Move the previous shape to the new parent
        changes        (-> (pcb/concat-changes before-changes changes)
                           (pcb/change-parent (:parent-id related-shape-in-new) [child-at-root] pos
                                              {:allow-altering-copies true}))

        objects        (pcb/get-objects changes)
        child-id       (:id orig-swapped-child)
        file           {:id (:id ldata) :data ldata}
        position       (ctf/find-ref-shape file (assoc page :objects objects) libraries related-shape-in-new
                                           :include-deleted? true
                                           :with-context? true)]

    (-> changes
        ;; Take the place of the related shape
        (pcb/update-shapes
         (cfh/get-children-ids-with-self objects child-id)
         (ctf/take-position file libraries objects (get objects child-id)
                            (:shape-ref related-shape-in-new) position)
         {:ignore-touched true})

        ;; and its placement, as sync would give it
        (cll/generate-take-placement (get objects child-id)
                                     related-shape-in-new
                                     objects
                                     (and (ctsl/any-layout-immediate-child? objects related-shape-in-new)
                                          (not (ctsl/position-absolute? orig-swapped-child))))

        ;; Delete new non-swapped item
        (cls/generate-delete-shapes ldata page objects (d/ordered-set (:id related-shape-in-new)) {:allow-altering-copies true})
        second)))

(defn- child-of-swapped?
  "Check if any head between base-parent-id and the shape, both left out,
  was swapped"
  [shape objects base-parent-id]
  (->> (ctn/get-parent-heads objects shape)
       (drop-while #(not= base-parent-id (:id %)))
       (rest)
       (remove #(= (:id shape) (:id %)))
       (some ctk/swapped?)))

(defn- find-shape-ref-child-of
  "Get the shape referenced by the shape-ref of the near main of the shape,
   recursively repeated until find a shape-ref with parent-id as ancestor.
   It will return the shape or nil if it doesn't found any"
  [container libraries shape parent-id]
  (let [ref-shape             (ctf/find-ref-shape nil container libraries shape
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
   the :touched of all its chain of ref shapes. A swap made in a main of the
   chain is part of that main, so only the shape's own swap counts, and a
   swapped shape keeps only its own groups."
  [container libraries shape]
  (if (ctk/swapped? shape)
    ;; its content comes from its own component's main, which has no overrides
    shape
    (let [new-touched (ctf/get-touched-from-ref-chain-until-target-ref container libraries shape nil)]
      (assoc shape :touched (disj new-touched :swap-group)))))

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

        ;; Get the touched children of the original-shape
        ;; Ignore children of swapped items, because
        ;; they will be moved without change when
        ;; managing their swapped ancestor
        orig-touched       (->> original-shapes
                                ;; Add to each shape also the touched of its ref chain
                                (map #(add-touched-from-ref-chain container libraries %))
                                (filter (comp seq :touched))
                                (remove
                                 #(child-of-swapped? %
                                                     page-objects
                                                     (:id original-shape))))

        ;; Adds a :shape-path attribute to the children of the new-shape,
        ;; that contains the type of its ancestors and its name
        new-shapes-w-path  (add-unique-path
                            (reverse (cfh/get-children-with-self objects (:id new-shape)))
                            objects
                            (:id new-shape))
        ;; Creates a map to quickly find a child of the new-shape by its shape-path
        new-shapes-map     (into {} (map (juxt :shape-path identity)) new-shapes-w-path)

        ;; The original-shape is in a copy. For the relation rules, we need the referenced
        ;; shape on the main component
        orig-base-ref-shape (ctf/find-remote-shape container libraries original-shape {:with-context? true})
        orig-ref-objects    (:objects (:container (meta orig-base-ref-shape)))

        ;; Adds a :shape-path attribute to the children of the orig-ref-shape,
        ;; that contains the type of its ancestors and its name
        o-ref-shapes-wp    (add-unique-path
                            (reverse (cfh/get-children-with-self orig-ref-objects (:id orig-base-ref-shape)))
                            orig-ref-objects
                            (:id orig-base-ref-shape))

        ;; Creates a map to quickly find a child of the orig-ref-shape by its shape-path
        o-ref-shapes-p-map  (into {} (map (juxt :id :shape-path)) o-ref-shapes-wp)

        ;; Each touched child of the original-shape, with the shape it refers
        ;; to in the main and the child of the new-shape that has the same
        ;; shape-path; the switched shape itself relates to the new root
        pairs
        (keep (fn [old]
                (let [root?   (= (:id old) (:id original-shape))
                      old-ref (if root?
                                orig-base-ref-shape
                                (find-shape-ref-child-of container libraries old (:id orig-base-ref-shape)))
                      new     (get new-shapes-map (get o-ref-shapes-p-map (:id old-ref)))]
                  (when new
                    {:old old :old-ref old-ref :new new
                     :swapped? (and (not root?) (ctk/swapped? old))})))
              orig-touched)

        ;; A swapped child is kept as it is, and the others carry their
        ;; touched attributes into their new child
        changes
        (reduce (fn [changes {:keys [old old-ref new swapped?]}]
                  (if swapped?
                    (keep-swapped-item changes new old ldata page libraries)
                    (clsw/update-attrs-on-switch changes new old new-shape original-shape old-ref container)))
                changes
                pairs)]
    [changes (mapv (comp :parent-id :new) pairs)]))

(defn- shape-index
  "Position of `shape-id` among the children of `parent-id`; 0 when it is not
   among them, nil when the parent does not exist."
  [objects parent-id shape-id]
  (when-let [parent (get objects parent-id)]
    (or (d/index-of (:shapes parent) shape-id) 0)))

(defn generate-component-swap-in-place
  "Swap the copy head `shape` of `page` for a copy of the component
   `component-id` of library data `ldata`. The new head takes the old one's
   place: its index in the parent, its grid cell and its `swap-keep-attrs`.
   With `keep-touched?`, the old head's overrides are carried over (the
   variant switch flavour). Returns `[new-shape changes updated-parent-ids]`,
   where the parent ids need a layout update."
  [changes page libraries ldata shape component-id keep-touched?]
  (let [objects     (:objects page)
        parent      (get objects (:parent-id shape))
        orig-shapes (when keep-touched?
                      (cfh/get-children-with-self objects (:id shape)))
        target-cell (when (ctsl/grid-layout? parent)
                      (ctsl/get-cell-by-shape-id parent (:id shape)))
        index       (shape-index objects (:parent-id shape) (:id shape))
        keep-props  (select-keys shape ctk/swap-keep-attrs)

        [new-shape all-parents changes]
        (clsw/generate-component-swap changes objects shape ldata page libraries component-id
                                      index target-cell keep-props keep-touched?)

        [changes parents-of-swapped]
        (if keep-touched?
          (generate-keep-touched changes new-shape shape orig-shapes page libraries ldata)
          [changes []])]
    [new-shape changes (concat all-parents parents-of-swapped)]))

(defn find-switch-target
  "The variant component that the variant copy head `shape` switches to when
   its property at position `pos` takes the value `val`: among the other
   variants with that value, the one whose properties are nearest to the
   current ones. Nil when the property already has that value or no variant
   has it."
  [libraries shape pos val]
  (let [component-id (:component-id shape)
        component    (ctf/get-component libraries (:component-file shape) component-id
                                        :include-deleted? false)]
    (when (not= val (get-in component [:variant-properties pos :value]))
      (let [ldata         (get-in libraries [(:component-file shape) :data])
            objects       (-> (ctf/get-component-page ldata component) :objects)
            target-props  (-> (:variant-properties component)
                              (update pos assoc :value val))
            candidates    (->> (cfv/find-variant-components ldata objects (:variant-id component))
                               (remove #(= (:id %) component-id))
                               (filter #(= (get-in % [:variant-properties pos :value]) val))
                               (reverse))]
        (when (seq candidates)
          (apply min-key #(ctv/distance target-props (:variant-properties %)) candidates))))))

(defn valid-switch?
  "Whether the variant copy head `shape` can switch its property at position
  `pos` to the value `val`: a member of its variant has it."
  [libraries shape pos val]
  (let [component (ctf/get-component libraries (:component-file shape) (:component-id shape)
                                     :include-deleted? false)]
    (and (ctk/instance-head? shape)
         (ctk/in-component-copy? shape)
         (ctk/is-variant? component)
         (nat-int? pos)
         (< pos (count (:variant-properties component)))
         (string? val)
         (or (= val (get-in component [:variant-properties pos :value]))
             (some? (find-switch-target libraries shape pos val))))))

(defn swap-nesting-loop?
  "Whether swapping, or switching, the copy head `shape` of `objects` for a
   copy of the component `component-id` of library data `ldata` would nest a
   component inside itself."
  [objects shape ldata component-id]
  (let [component (ctkl/get-component ldata component-id true)
        page      (ctf/get-component-page ldata component)
        root      (ctf/get-component-root ldata component)]
    (boolean
     (and page
          root
          (cfh/components-nesting-loop?
           (cfh/get-children-with-self (:objects page) (:id root))
           (cfh/get-parents-with-self objects (:parent-id shape)))))))
