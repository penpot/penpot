(ns app.common.logic.variants
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.variant :as cfv]
   [app.common.logic.libraries :as cll]
   [app.common.logic.variant-properties :as clvp]
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
