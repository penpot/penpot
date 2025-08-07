;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.common.logic.variant-properties
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctcl]
   [app.common.types.variant :as ctv]
   [cuerdas.core :as str]))

(defn generate-update-property-name
  [changes variant-id pos new-name]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)

        props              (-> related-components last :variant-properties)
        prop-names         (mapv :name props)
        prop-names         (concat (subvec prop-names 0 pos) (subvec prop-names (inc pos)))
        new-name           (ctv/update-number-in-repeated-item prop-names new-name)]
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


(defn generate-set-variant-error
  [changes component-id value]
  (let [data      (pcb/get-library-data changes)
        component (ctcl/get-component data component-id true)
        main-id   (:main-instance-id component)]
    (-> changes
        (pcb/update-shapes [main-id] (if (str/blank? value)
                                       #(dissoc % :variant-error)
                                       #(assoc % :variant-error value))))))


(defn generate-add-new-property
  [changes variant-id & {:keys [fill-values? property-name]}]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)

        props              (-> related-components last :variant-properties)
        next-prop-num      (ctv/next-property-number props)
        property-name      (or property-name (str ctv/property-prefix next-prop-num))

        prop-names         (mapv :name props)
        property-name      (ctv/update-number-in-repeated-item prop-names property-name)

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

(defn- generate-make-shape-no-variant
  [changes shape]
  (let [new-name      (ctv/variant-name-to-name shape)
        [cpath cname] (cfh/parse-path-name new-name)]
    (-> changes
        (pcb/update-component (:component-id shape)
                              #(-> (dissoc % :variant-id :variant-properties)
                                   (assoc :name cname
                                          :path cpath))
                              {:apply-changes-local-library? true})
        (pcb/update-shapes [(:id shape)]
                           #(-> (dissoc % :variant-id :variant-name)
                                (assoc :name new-name))))))

(defn generate-make-shapes-no-variant
  [changes shapes]
  (reduce generate-make-shape-no-variant changes shapes))


(defn- create-new-properties-from-variant
  [shape min-props data container-name base-properties]
  (let [component (ctcl/get-component data (:component-id shape) true)

        add-name? (not= (:name component) container-name)
        props     (ctv/merge-properties base-properties
                                        (:variant-properties component))
        new-props (- min-props
                     (+ (count props)
                        (if add-name? 1 0)))
        props     (ctv/add-new-props props (repeat new-props ""))]

    (if add-name?
      (ctv/add-new-prop props (:name component))
      props)))

(defn- create-new-properties-from-non-variant
  [shape min-props container-name base-properties]
  (let [;; Remove container name from shape name if present
        shape-name (ctv/remove-prefix (:name shape) container-name)]
    (ctv/path-to-properties shape-name base-properties min-props)))


(defn generate-make-shapes-variant
  [changes shapes variant-container]
  (let [data           (pcb/get-library-data changes)
        objects        (pcb/get-objects changes)
        variant-id     (:id variant-container)

        ;; If we are cut-pasting a variant-container, this will be null
        ;; because it hasn't any shapes yet
        first-comp-id  (->> variant-container
                            :shapes
                            first
                            (get objects)
                            :component-id)

        base-props     (->> (get-in data [:components first-comp-id :variant-properties])
                            (map #(assoc % :value "")))
        num-base-props (count base-props)

        [cpath cname]  (cfh/parse-path-name (:name variant-container))
        container-name (:name variant-container)

        create-new-properties
        (fn [shape min-props]
          (if (ctk/is-variant? shape)
            (create-new-properties-from-variant shape min-props data container-name base-props)
            (create-new-properties-from-non-variant shape min-props container-name base-props)))

        total-props    (reduce (fn [m shape]
                                 (max m (count (create-new-properties shape num-base-props))))
                               0
                               shapes)

        num-new-props  (if (or (zero? num-base-props)
                               (< total-props num-base-props))
                         0
                         (- total-props num-base-props))

        changes        (nth
                        (iterate #(generate-add-new-property % variant-id) changes)
                        num-new-props)

        changes        (pcb/update-shapes changes (map :id shapes)
                                          #(assoc % :variant-id variant-id
                                                  :name (:name variant-container)))]
    (reduce
     (fn [changes shape]
       (let [component (ctcl/get-component data (:component-id shape) true)]
         (if (or (zero? num-base-props)                  ;; do nothing if there are no base props
                 (and (= variant-id (:variant-id shape)) ;; or we are only moving the shape inside its parent (it is
                      (not (:deleted component))))       ;; the same parent and the component isn't deleted)
           changes
           (let [props               (create-new-properties shape total-props)
                 variant-name        (ctv/properties-to-name props)]
             (-> (pcb/update-component changes
                                       (:component-id shape)
                                       #(assoc % :variant-id variant-id
                                               :variant-properties props
                                               :name cname
                                               :path cpath)
                                       {:apply-changes-local-library? true})
                 (pcb/update-shapes [(:id shape)]
                                    #(assoc % :variant-name variant-name)))))))
     changes
     shapes)))
