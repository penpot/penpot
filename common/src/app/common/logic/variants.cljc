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
  [changes new-shape original-shape original-shapes page libraries]
  (let [objects            (pcb/get-objects changes)
        orig-objects       (into {} (map (juxt :id identity) original-shapes))
        orig-shapes-w-path (add-unique-path
                            (reverse original-shapes)
                            orig-objects
                            (:id original-shape))
        new-shapes-w-path  (add-unique-path
                            (reverse (cfh/get-children-with-self objects (:id new-shape)))
                            objects
                            (:id new-shape))
        new-shapes-map     (into {} (map (juxt :shape-path identity) new-shapes-w-path))
        orig-touched      (filter (comp seq :touched) orig-shapes-w-path)

        container          (ctn/make-container page :page)]
    (reduce
     (fn [changes touched-shape]
       (let [related-shape  (get new-shapes-map (:shape-path touched-shape))
             orig-ref-shape (ctf/find-ref-shape nil container libraries touched-shape)]
         (if related-shape
           (cll/update-attrs-on-switch
            changes related-shape touched-shape new-shape original-shape orig-ref-shape container)
           changes)))
     changes
     orig-touched)))

