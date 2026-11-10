(ns app.common.logic.variants
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.swap :as clsw]
   [app.common.logic.variant-properties :as clvp]
   [app.common.math :as mth]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape.layout :as ctsl]
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
        value               (cfv/new-property-value data objects variant-id prop-num)

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
      ;; one move per main, in the order of `ids`, so the property values it
      ;; generates follow that order
      (reduce #(generate-move-into %1 #{%2} variant-id 0) $ (rest ids))
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

        ;; the child and its subtree follow the change from the old parent to
        ;; the new one, each through its own constraints
        changes        (-> (cll/generate-follow-parent changes
                                                       (ctn/make-container (assoc page :objects (pcb/get-objects changes)) :page)
                                                       (get (pcb/get-objects changes) (:id orig-swapped-child))
                                                       (get (:objects page) (:parent-id orig-swapped-child))
                                                       current-parent
                                                       nil)
                           (pcb/apply-changes-local))

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

        ;; and its cell in a grid parent
        (cond-> (ctsl/grid-layout? current-parent)
          (pcb/update-shapes
           [(:id current-parent)]
           (fn [parent]
             (let [;; the child in place of the related shape
                   take-id #(if (= % (:id related-shape-in-new)) child-id %)]
               (update parent :layout-grid-cells update-vals
                       (fn [cell] (update cell :shapes (partial mapv take-id))))))
           {:ignore-touched true}))

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

(defn- nests-in-switch?
  "Whether the swapped shape `swapped` of `objects` holds a copy of a
   component of `holders`, the shapes a switch would place it inside, so
   keeping it would nest a component in itself."
  [objects swapped holders]
  (cfh/components-nesting-loop? (cfh/get-children-with-self objects (:id swapped))
                                holders))

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
   the :touched of all its chain of ref shapes. A swap made in a main of the
   chain is part of that main, so only the shape's own swap counts, and a
   swapped shape keeps only its own groups."
  [container libraries shape]
  (if (ctk/swapped? shape)
    ;; its content comes from its own component's main, which has no overrides
    shape
    (let [new-touched (ctf/get-touched-from-ref-chain-until-target-ref container libraries shape nil)]
      (assoc shape :touched (disj new-touched :swap-group)))))

(defn- same-orientation?
  "Whether shapes `a` and `b` have the same rotation and flips."
  [a b]
  (and (mth/close? (mod (or (:rotation a) 0) 360) (mod (or (:rotation b) 0) 360) 0.01)
       (= (boolean (:flip-x a)) (boolean (:flip-x b)))
       (= (boolean (:flip-y a)) (boolean (:flip-y b)))))

(defn- keep-orientation-override
  "Retained orientation that differs from the new component's expected
   placement needs a geometry override to survive later sync."
  [changes shape baseline page ldata component-id position]
  (let [parent (get-in page [:objects (:parent-id shape)])
        main (ctf/get-component-root ldata (ctkl/get-component ldata component-id))
        main-parent (get-in (ctf/get-component-container ldata (ctkl/get-component ldata component-id))
                            [:objects (:parent-id main)])
        baseline (or position baseline)
        main-parent (if position
                      (get-in (meta position) [:container :objects (:parent-id position)])
                      main-parent)
        rotation (mod (+ (or (:rotation baseline) 0)
                         (- (or (:rotation parent) 0) (or (:rotation main-parent) 0)))
                      360)
        flip (fn [key]
               (odd? (count (filter identity [(get baseline key) (get parent key)
                                              (get main-parent key)]))))
        changed? (or (not (mth/close? (mod (or (:rotation shape) 0) 360) rotation 0.01))
                     (not= (boolean (:flip-x shape)) (flip :flip-x))
                     (not= (boolean (:flip-y shape)) (flip :flip-y)))]
    (cond-> changes
      changed?
      (pcb/update-shapes [(:id shape)]
                         #(update % :touched (fnil conj #{}) :geometry-group)
                         {:ignore-touched true}))))

(defn- grid-cells-by-path
  "The cells of the grid `shape` without their ids, with each child as its
   path (see `add-unique-path`)."
  [shape path-of]
  (into #{}
        (map (fn [cell] (-> cell (dissoc :id) (update :shapes #(mapv path-of %)))))
        (vals (:layout-grid-cells shape))))

(defn- carry-grid-cells
  "Carry the cell overrides of the switched grid `old` into `new`, its clean
   copy of the new variant, when the old main `old-ref` and `new` have
   identical grids: the same tracks and the same cells holding
   corresponding children, and they differ from the cells `new` shows.
   Otherwise the new variant's cells win. `counterpart` gives the child of
   `new` that relates to a child of `old`. The children of `new` then follow
   the flow of the carried cells."
  [changes old old-ref new old-path new-path counterpart]
  (let [identical? (and (= (:layout-grid-rows old-ref) (:layout-grid-rows new))
                        (= (:layout-grid-columns old-ref) (:layout-grid-columns new))
                        (= (grid-cells-by-path old-ref old-path)
                           (grid-cells-by-path new new-path)))
        cell-id    (fn [{:keys [row column]}]
                     (some (fn [[id cell]] (when (and (= row (:row cell)) (= column (:column cell))) id))
                           (:layout-grid-cells new)))
        cells      (when identical?
                     (reduce (fn [cells cell]
                               (let [shapes (mapv counterpart (:shapes cell))
                                     id     (or (cell-id cell) (:id cell))]
                                 (if (some nil? shapes)
                                   (reduced nil)
                                   (assoc cells id (assoc cell :id id :shapes shapes)))))
                             {}
                             (vals (:layout-grid-cells old))))
        ;; cells the new copy already shows are no override
        shown?     (fn [cells]
                     (= (set (map #(dissoc % :id) (vals cells)))
                        (set (map #(dissoc % :id) (vals (:layout-grid-cells new))))))]
    (if (and cells (not (shown? cells)))
      (-> changes
          (pcb/update-shapes [(:id new)]
                             #(-> %
                                  (assoc :layout-grid-cells cells)
                                  (update :touched (fnil conj #{}) :layout-grid-cells))
                             {:ignore-touched true})
          (pcb/apply-changes-local)
          ;; the children follow the flow of the carried cells
          (pcb/reorder-grid-children [(:id new)]))
      changes)))

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
                      new     (get new-shapes-map (get o-ref-shapes-p-map (:id old-ref)))
                      swapped? (and (not root?) (ctk/swapped? old))
                      ;; a main between the shape and the variant swapped it, so its
                      ;; content and overrides belong to another component
                      swapped-in-chain?
                      (and (not root?) (not swapped?)
                           (some #(and (ctk/swapped? %) (not= (:id %) (:id old-ref)))
                                 (rest (ctf/get-ref-chain-until-target-ref container libraries old
                                                                           (:id old-ref)))))]
                  ;; a swap is kept only where the new variant holds a copy,
                  ;; and when it does not nest the new variant in itself
                  (when (and new
                             (not swapped-in-chain?)
                             (or (not swapped?)
                                 (and (ctk/instance-head? new)
                                      (not (nests-in-switch? page-objects old
                                                             (cfh/get-parents objects (:id new)))))))
                    {:old old :old-ref old-ref :new new :swapped? swapped?})))
              orig-touched)

        ;; A swapped child is kept as it is, and the others carry their
        ;; touched attributes into their new child
        ;; the old main's shapes turned as the old copy turns them, so the
        ;; switch compares them with the new copy's in one frame
        orient-ref          (or (when orig-base-ref-shape
                                  (cll/orient-like orig-base-ref-shape original-shape
                                                   (gsh/shape->center orig-base-ref-shape)))
                                identity)
        ;; `ref-shape` turned too as the copy turns the parent of `old` below
        ;; its root, so a turn a nested copy authors is not the child's own
        nested-turn         (fn [ref-shape old old-ref]
                              (let [ref-objects (:objects (:container (meta old-ref)))
                                    ref-root    (get ref-objects (:id orig-base-ref-shape))
                                    ref-parent  (get ref-objects (:parent-id old-ref))
                                    old-parent  (get page-objects (:parent-id old))
                                    root-turn   (when (and ref-root ref-parent)
                                                  (cll/orient-like ref-root original-shape
                                                                   (gsh/shape->center ref-root)))
                                    turned      (when ref-parent
                                                  (cond-> ref-parent (some? root-turn) root-turn))
                                    turn        (when (and turned old-parent
                                                           (not= (:id old-parent) (:id original-shape)))
                                                  (cll/orient-like turned old-parent (gsh/shape->center turned)))]
                                (cond-> ref-shape (some? turn) turn)))

        changes
        (reduce (fn [changes {:keys [old old-ref new swapped?]}]
                  (if swapped?
                    (keep-swapped-item changes new old ldata page libraries)
                    (clsw/update-attrs-on-switch changes new old new-shape original-shape
                                                 (nested-turn (orient-ref old-ref) old old-ref)
                                                 container)))
                changes
                pairs)

        ;; Cell overrides carry only between identical grids.
        new-paths           (into {} (map (juxt :id :shape-path)) new-shapes-w-path)
        ;; a kept swap takes the place of its new child with its own id
        kept-swaps          (into #{} (comp (filter :swapped?) (map (comp :id :old))) pairs)
        counterpart         (fn [id]
                              (if (contains? kept-swaps id)
                                id
                                (when-let [old-child (get page-objects id)]
                                  (let [old-ref (find-shape-ref-child-of container libraries old-child
                                                                         (:id orig-base-ref-shape))]
                                    (:id (get new-shapes-map (get o-ref-shapes-p-map (:id old-ref))))))))
        grid-pairs          (filter (fn [{:keys [old new swapped?]}]
                                      (and (not swapped?)
                                           (ctsl/grid-layout? old)
                                           (ctsl/grid-layout? new)
                                           (ctk/touched-group? old :layout-grid-cells)))
                                    pairs)
        changes             (reduce (fn [changes {:keys [old old-ref new]}]
                                      (carry-grid-cells changes old old-ref new
                                                        o-ref-shapes-p-map new-paths counterpart))
                                    changes
                                    grid-pairs)
        new-ldata           (or (get-in libraries [(:component-file new-shape) :data]) ldata)
        new-main            (ctf/get-component-root new-ldata (ctkl/get-component new-ldata (:component-id new-shape)))
        ;; An authored orientation carries only where both variants share it
        parent              (get page-objects (:parent-id original-shape))
        ref-parent          (get orig-ref-objects (:parent-id orig-base-ref-shape))
        ;; Whether the switched copy's orientation is authored along its
        ;; reference chain, not just its component's turned with its parent
        authored-orientation? (let [flip (fn [key]
                                           (odd? (count (filter identity [(get orig-base-ref-shape key) (get parent key)
                                                                          (get ref-parent key)]))))]
                                (or (not (mth/close? (mod (or (:rotation original-shape) 0) 360)
                                                     (mod (+ (or (:rotation orig-base-ref-shape) 0)
                                                             (- (or (:rotation parent) 0) (or (:rotation ref-parent) 0)))
                                                          360)
                                                     0.01))
                                    (not= (boolean (:flip-x original-shape)) (flip :flip-x))
                                    (not= (boolean (:flip-y original-shape)) (flip :flip-y))))
        carried-orientation? (and (some #(and (= (:id %) (:id original-shape))
                                              (ctk/touched-group? % :geometry-group)) orig-touched)
                                  authored-orientation?
                                  (same-orientation? orig-base-ref-shape new-main))
        ;; A switch keeps orientation even when the old main supplied it.
        ;; If no orientation was carried, placement from an enclosing copy is
        ;; still inherited rather than authored by this switch.
        position            (when (and (not carried-orientation?) (:shape-ref parent))
                              (ctf/find-ref-shape nil page libraries original-shape
                                                  :include-deleted? true :with-context? true))
        changes             (keep-orientation-override changes original-shape new-shape page
                                                       new-ldata (:component-id new-shape) position)
        ;; A kept child whose swap ended is a plain copy of its new position
        ended-swap?         (some (fn [{:keys [old swapped?]}]
                                    (and swapped? (not (ctk/swapped? (get (pcb/get-objects changes) (:id old))))))
                                  pairs)
        ;; A carried size changes the frame its untouched children follow,
        ;; and an ended swap shows its position: both sync as an ordinary copy.
        changes             (cond-> changes
                              (or ended-swap? (some #(ctk/touched-group? % :geometry-group) orig-touched))
                              (cll/generate-sync-shape-direct
                               nil libraries (assoc container :objects (pcb/get-objects changes))
                               (:id new-shape) false))
        changes             (clsw/generate-sync-swap-constraints changes page libraries (:id new-shape))]
    [changes (into (mapv (comp :parent-id :new) pairs) (map (comp :id :new)) grid-pairs)]))

(defn- shape-index
  "Position of `shape-id` among the children of `parent-id`; 0 when it is not
   among them, nil when the parent does not exist."
  [objects parent-id shape-id]
  (when-let [parent (get objects parent-id)]
    (or (d/index-of (:shapes parent) shape-id) 0)))

(defn- sync-restored-position
  "A swap back is an ordinary copy of its position, including that
   position's content overrides. Sync through the head that owns its ref."
  [changes page libraries shape-id]
  (let [objects   (pcb/get-objects changes)
        shape     (get objects shape-id)
        parent    (get objects (:parent-id shape))
        container (ctn/make-container (assoc page :objects objects) :page)]
    (if (and (:shape-ref parent) (not (ctk/swapped? shape)))
      (let [component (ctf/find-ref-component nil container libraries shape
                                              :include-deleted? true)
            head      (d/seek #(= (:id component) (:component-id %))
                              (ctn/get-parent-copy-heads objects parent))]
        (if head
          (cll/generate-sync-shape-direct changes nil libraries container (:id head) false)
          changes))
      changes)))

(defn- keep-moved-placement
  "A swap keeps the place of the copy shape `original-shape` it replaces.
   Inside a copy, a first corner that the position of `new-shape` would not
   give it needs a geometry override to survive later sync."
  [changes original-shape new-shape page libraries]
  (let [objects          (pcb/get-objects changes)
        shape            (get objects (:id new-shape))
        parent           (get objects (:parent-id shape))
        position         (when (and (:shape-ref parent)
                                    (ctk/touched-group? original-shape :geometry-group)
                                    (not (ctk/touched-group? shape :geometry-group)))
                           (ctf/find-ref-shape nil (assoc page :objects objects) libraries shape
                                               :include-deleted? true :with-context? true))
        position-objects (:objects (:container (meta position)))
        position-parent  (get position-objects (:parent-id position))
        placed           (when position-parent
                           (get (cll/placed-like position-objects position-parent parent {}) (:id position)))]
    (cond-> changes
      (and placed (not (gpt/close? (first (:points shape)) (first (:points placed)))))
      (pcb/update-shapes [(:id shape)] #(update % :touched ctk/set-touched-group :geometry-group)
                         {:ignore-touched true}))))

(defn- keep-placement-overrides
  "A switch gives the new head the `swap-keep-attrs` of the copy head
   `original-shape` it replaces, and with them the overrides that authored
   them."
  [changes original-shape new-shape]
  (let [groups (into #{}
                     (comp (keep #(ctk/resolve-sync-group (:type original-shape) %))
                           (filter #(ctk/touched-group? original-shape %)))
                     ctk/swap-keep-attrs)]
    (cond-> changes
      (seq groups)
      (pcb/update-shapes [(:id new-shape)] #(update % :touched (fnil into #{}) groups)
                         {:ignore-touched true}))))

(defn- kept-swapped-ids
  "The swapped shapes in the subtree of the copy head `shape` that a switch
   to the component `component-id` of `ldata` keeps: those whose place in
   their main, by name and ancestor kinds, the new component holds a copy
   in, and that would not nest a component in itself. The others go with
   the old head."
  [page libraries ldata shape component-id]
  (let [objects      (:objects page)
        container    (ctn/make-container page :page)
        base-ref     (ctf/find-remote-shape container libraries shape {:with-context? true})
        ref-objects  (:objects (:container (meta base-ref)))
        ref-paths    (when base-ref
                       (into {} (map (juxt :id :shape-path))
                             (add-unique-path (reverse (cfh/get-children-with-self ref-objects (:id base-ref)))
                                              ref-objects (:id base-ref))))
        component    (ctkl/get-component ldata component-id)
        main         (ctf/get-component-root ldata component)
        main-objects (:objects (ctf/get-component-container ldata component))
        main-ids     (into #{} (map :id) (cfh/get-children-with-self main-objects (:id main)))
        ;; the copies the new main holds, by path
        new-copies   (into {} (comp (filter ctk/instance-head?) (map (juxt :shape-path identity)))
                           (add-unique-path (reverse (cfh/get-children-with-self main-objects (:id main)))
                                            main-objects (:id main)))
        ;; the shapes a swap that takes the place of `place` is inside: the
        ;; ancestors of `place` in the new main, and those of the head
        holders      (fn [place]
                       (concat (filter #(contains? main-ids (:id %)) (cfh/get-parents main-objects (:id place)))
                               (cfh/get-parents objects (:id shape))))]
    (into #{}
          (comp (filter ctk/swapped?)
                (remove #(child-of-swapped? % objects (:id shape)))
                (filter (fn [old]
                          (let [old-ref (find-shape-ref-child-of container libraries old (:id base-ref))
                                place   (when old-ref (get new-copies (get ref-paths (:id old-ref))))]
                            (and (some? place)
                                 (not (nests-in-switch? objects old (holders place)))))))
                (map :id))
          (rest (cfh/get-children-with-self objects (:id shape))))))

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
                                      index target-cell keep-props
                                      (when keep-touched?
                                        (kept-swapped-ids page libraries ldata shape component-id)))

        [changes parents-of-swapped]
        (if keep-touched?
          (generate-keep-touched changes new-shape shape orig-shapes page libraries ldata)
          [(keep-orientation-override changes shape new-shape page ldata component-id
                                      (when (:shape-ref parent)
                                        (ctf/find-ref-shape nil page libraries shape
                                                            :include-deleted? true :with-context? true))) []])

        changes (sync-restored-position changes page libraries (:id new-shape))

        changes (cond-> (keep-moved-placement changes shape new-shape page libraries)
                  keep-touched?
                  (keep-placement-overrides shape new-shape))

        ;; Replacement keeps the head id and its cell. Cleanup during the
        ;; temporary deletion must not change the parent's placement data.
        changes (cond-> changes
                  (ctsl/grid-layout? parent)
                  (-> (pcb/update-shapes [(:id parent)]
                                         #(assoc % :layout-grid-cells (:layout-grid-cells parent))
                                         {:ignore-touched true})
                      ;; a head that enters or leaves the flow takes or frees a cell
                      (cll/reassign-parent-grid-cells page (:id shape))))]
    [new-shape changes (concat all-parents parents-of-swapped)]))

(defn find-switch-target
  "The variant component that the variant copy head `shape` switches to when
   its property at position `pos` takes the value `val`: among the other
   variants with that value, the one whose properties are nearest to the
   current ones; between equally near ones, the first among the children of
   the variant container. Nil when the property already has that value or no
   variant has it."
  [libraries shape pos val]
  (let [component-id (:component-id shape)
        component    (ctf/get-component libraries (:component-file shape) component-id
                                        :include-deleted? false)]
    (when (not= val (get-in component [:variant-properties pos :value]))
      (let [ldata         (get-in libraries [(:component-file shape) :data])
            objects       (-> (ctf/get-component-page ldata component) :objects)
            target-props  (-> (:variant-properties component)
                              (update pos assoc :value val))
            distance      #(ctv/distance target-props (:variant-properties %))
            ;; in the order of the container's children
            candidates    (->> (cfv/find-variant-components ldata objects (:variant-id component))
                               (remove #(= (:id %) component-id))
                               (filter #(= (get-in % [:variant-properties pos :value]) val))
                               (reverse))]
        (reduce (fn [nearest candidate]
                  (if (< (distance candidate) (distance nearest)) candidate nearest))
                (first candidates)
                (rest candidates))))))

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
   component inside itself, directly or through the mains of the components
   that component holds in `ldata`."
  [objects shape ldata component-id]
  (let [component (ctkl/get-component ldata component-id true)
        page      (ctf/get-component-page ldata component)
        root      (ctf/get-component-root ldata component)
        parents   (into #{} (keep :component-id) (cfh/get-parents-with-self objects (:parent-id shape)))]
    (boolean
     (and page
          root
          (some parents
                (ctn/held-component-ids (cfh/get-children-with-self (:objects page) (:id root))
                                        {(:id ldata) {:data ldata}}))))))
