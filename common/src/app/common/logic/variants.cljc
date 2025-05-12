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

(defn generate-keep-touched
  [changes new-shape original-shape original-shapes page libraries]
  (let [objects      (pcb/get-objects changes)
        new-path-map (into {}
                           (map (fn [shape] {(generate-path "" objects (:id new-shape) shape) shape}))
                           (cfh/get-children-with-self objects (:id new-shape)))

        orig-touched (filter (comp seq :touched) original-shapes)
        orig-objects (into {} (map (juxt :id identity) original-shapes))
        container    (ctn/make-container page :page)]
    (reduce
     (fn [changes touched-shape]
       (let [path (generate-path "" orig-objects (:id original-shape) touched-shape)
             related-shape  (get new-path-map path)
             orig-ref-shape (ctf/find-ref-shape nil container libraries touched-shape)]
         (if related-shape
           (cll/update-attrs-on-switch
            changes related-shape touched-shape new-shape original-shape orig-ref-shape container)
           changes)))
     changes
     orig-touched)))

