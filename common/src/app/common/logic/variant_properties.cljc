;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.common.logic.variant-properties
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.variant :as cfv]
   [app.common.types.components-list :as ctcl]
   [app.common.types.variant :as ctv]
   [cuerdas.core :as str]))

(defn generate-update-property-name
  [changes variant-id pos new-name]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)]
    (reduce (fn [changes component]
              (pcb/update-component
               changes (:id component)
               #(assoc-in % [:variant-properties pos :name] new-name)
               {:apply-changes-local-library? true}))
            changes
            related-components)))


(defn generate-remove-property
  [changes variant-id pos]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)]
    (reduce (fn [changes component]
              (let [props   (:variant-properties component)
                    props   (d/remove-at-index props pos)
                    main-id (:main-instance-id component)
                    name    (ctv/properties-to-name props)]
                (-> changes
                    (pcb/update-component (:id component) #(assoc % :variant-properties props)
                                          {:apply-changes-local-library? true})
                    (pcb/update-shapes [main-id] #(assoc % :variant-name name)))))
            changes
            related-components)))


(defn generate-update-property-value
  [changes component-id pos value]
  (let [data      (pcb/get-library-data changes)
        component (ctcl/get-component data component-id true)
        main-id   (:main-instance-id component)
        name      (-> (:variant-properties component)
                      (update pos assoc :value value)
                      ctv/properties-to-name)]
    (-> changes
        (pcb/update-component component-id #(assoc-in % [:variant-properties pos :value] value)
                              {:apply-changes-local-library? true})
        (pcb/update-shapes [main-id] #(assoc % :variant-name name)))))


(defn generate-add-new-property
  [changes variant-id & {:keys [fill-values?]}]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)

        props              (-> related-components first :variant-properties)
        next-prop-num      (ctv/next-property-number props)
        property-name      (str ctv/property-prefix next-prop-num)

        [_ changes]
        (reduce (fn [[num changes] component]
                  (let [main-id      (:main-instance-id component)

                        update-props #(-> (d/nilv % [])
                                          (conj {:name property-name
                                                 :value (if fill-values? (str ctv/value-prefix num) "")}))

                        update-name #(if fill-values?
                                       (if (str/empty? %)
                                         (str ctv/value-prefix num)
                                         (str % ", " ctv/value-prefix num))
                                       %)]
                    [(inc num)
                     (-> changes
                         (pcb/update-component (:id component)
                                               #(update % :variant-properties update-props)
                                               {:apply-changes-local-library? true})
                         (pcb/update-shapes [main-id] #(update % :variant-name update-name)))]))
                [1 changes]
                related-components)]
    changes))

