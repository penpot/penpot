(ns app.common.logic.variants
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.logic.libraries :as cll]
   [app.common.logic.variant-properties :as clvp]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.variant :as ctv]))

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
    (-> changes
        (clvp/generate-update-property-value new-component-id prop-num value)
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


(defn generate-keep-touched
  "This is used as part of the switch process, when you switch from
   an original-shape to a new-shape. It generate changes to
   copy the touched attributes on the shapes children of the original-shape
   into the related children of the new-shape.
   This relation is tricky. The shapes are related if:
   * On the main components, both have the same name (the name on the copies are ignored)
   * Both has the same type of ancestors, on the same order (see generate-path for the
     translation of the types)"
  [changes new-shape original-shape original-shapes page libraries]
  (let [objects            (pcb/get-objects changes)
        container          (ctn/make-container page :page)

        ;; Get the touched children of the original-shape
        orig-touched       (filter (comp seq :touched) original-shapes)

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
        orig-ref-shape     (ctf/find-ref-shape nil container libraries original-shape)

        orig-ref-objects   (-> (ctf/get-component-container-from-head orig-ref-shape libraries)
                               :objects)

        ;; Adds a :shape-path attribute to the children of the orig-ref-shape,
        ;; that contains the type of its ancestors and its name
        o-ref-shapes-wp    (add-unique-path
                            (reverse (cfh/get-children-with-self orig-ref-objects (:id orig-ref-shape)))
                            orig-ref-objects
                            (:id orig-ref-shape))

        ;; Creates a map to quickly find a child of the orig-ref-shape by its shape-path
        o-ref-shapes-p-map  (into {} (map (juxt :id :shape-path)) o-ref-shapes-wp)]
    ;; Process each touched children of the original-shape
    (reduce
     (fn [changes orig-child-touched]
       (let [;; orig-child-touched is in a copy. Get the referenced shape on the main component
             orig-ref-shape (ctf/find-ref-shape nil container libraries orig-child-touched)
             ;; Get the shape path of the referenced main
             shape-path     (get o-ref-shapes-p-map (:id orig-ref-shape))
             ;; Get its related shape in the children of new-shape: the one that
             ;; has the same shape-path
             related-shape-in-new  (get new-shapes-map shape-path)]
         (if related-shape-in-new
           ;; If there is a related shape, copy the touched attributes into it
           (cll/update-attrs-on-switch
            changes related-shape-in-new orig-child-touched new-shape original-shape orig-ref-shape container)
           changes)))
     changes
     orig-touched)))

