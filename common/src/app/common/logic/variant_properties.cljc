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
   [app.common.path-names :as cpn]
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
               (fn [component]
                 (d/update-in-when component [:variant-properties pos] #(assoc % :name new-name)))
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
        (pcb/update-shapes [main-id] (if (nil? value)
                                       #(dissoc % :variant-error)
                                       #(assoc % :variant-error value))))))


(defn generate-reorder-variant-poperties
  [changes variant-id from-pos to-space-between-pos]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)]
    (reduce (fn [changes component]
              (let [props   (:variant-properties component)
                    props   (d/reorder props from-pos to-space-between-pos)
                    main-id (:main-instance-id component)
                    name    (ctv/properties-to-name props)]
                (-> changes
                    (pcb/update-component (:id component)
                                          #(assoc % :variant-properties props)
                                          {:apply-changes-local-library? true})
                    (pcb/update-shapes [main-id]
                                       #(assoc % :variant-name name)))))
            changes
            related-components)))


(defn generate-add-new-property
  [changes variant-id & {:keys [fill-values? editing? property-name property-value]}]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)

        props              (-> related-components last :variant-properties)
        next-prop-num      (ctv/next-property-number props)
        property-name      (or property-name (str ctv/property-prefix next-prop-num))

        prop-names         (mapv :name props)
        property-name      (ctv/update-number-in-repeated-item prop-names property-name)

        mdata              (if editing? {:editing? true} nil)

        [_ changes]
        (reduce (fn [[num changes] component]
                  (let [main-id      (:main-instance-id component)

                        update-props #(-> (d/nilv % [])
                                          (conj (with-meta {:name property-name
                                                            :value (cond fill-values?   (str ctv/value-prefix num)
                                                                         property-value property-value
                                                                         :else          "")}
                                                  mdata)))

                        update-name #(cond fill-values?   (if (str/empty? %)
                                                            (str ctv/value-prefix num)
                                                            (str % ", " ctv/value-prefix num))
                                           property-value (if (str/empty? %)
                                                            property-value
                                                            (str % ", " property-value))
                                           :else %)]
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
        [cpath cname] (cpn/split-group-name new-name)]
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
        component-full-name (cpn/merge-path-item (:path component) (:name component))
        add-name? (not= component-full-name container-name)
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

        num-shapes     (->> variant-container
                            :shapes
                            count)

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

        [cpath cname]  (cpn/split-group-name (:name variant-container))
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

        num-new-props  (if (or (zero? num-shapes)
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
         (if (or (zero? num-shapes)                      ;; do nothing if there are no shapes
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
