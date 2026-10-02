;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
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
  "Update the name of a variant property at position `pos` for all components in the variant.
   The new name may be added a suffix to ensure it is unique among the other property names."
  [changes variant-id pos new-name]
  (if (or (nil? variant-id) (nil? pos))
    changes
    (if-let [new-name (ctv/prepare-property-name new-name)]
      (let [data               (pcb/get-library-data changes)
            objects            (pcb/get-objects changes)
            related-components (cfv/find-variant-components data objects variant-id)]
        (if (empty? related-components)
          changes
          (let [props      (-> related-components last :variant-properties)
                num-props  (count props)]
            (if (or (< pos 0) (>= pos num-props))
              changes
              (let [prop-names (mapv :name props)
                    prop-names (concat (subvec prop-names 0 pos) (subvec prop-names (inc pos)))
                    new-name   (ctv/update-number-in-repeated-item prop-names new-name)

                    changes    (reduce (fn [changes component]
                                         (pcb/update-component
                                          changes (:id component)
                                          (fn [component]
                                            (d/update-in-when component [:variant-properties pos] #(assoc % :name new-name)))
                                          {:apply-changes-local-library? true}))
                                       changes
                                       related-components)

                    ;; All components of the variant change, so validate the
                    ;; container, which also checks every main instance
                    changes (pcb/validate-shapes changes
                                                 (pcb/get-page-id changes)
                                                 [variant-id]
                                                 (str "generate-update-property-name: " variant-id
                                                      " pos: " pos " new-name: " new-name))]
                changes)))))
      changes)))

(defn generate-remove-property
  "Remove the property at `pos` from every variant of the set. Then, unless
   `sanitize-set?` is false, sanitize the remaining set as a whole, so two
   variants that differed only in the removed property do not end up with
   the same values."
  [changes variant-id pos & {:keys [sanitize-set?] :or {sanitize-set? true}}]
  (let [data       (pcb/get-library-data changes)
        objects    (pcb/get-objects changes)
        ;; Layers-panel order, the reverse of :shapes: on a clash the variant
        ;; higher in the panel keeps its values and the lower one gets " (n)".
        components (->> (cfv/qualifying-variant-members data objects (get objects variant-id))
                        (map :component)
                        (reverse))
        props      (-> components first :variant-properties)]
    (if (and (seq props) (<= 0 pos) (< pos (count props)))
      (let [stripped  (mapv #(d/remove-at-index (:variant-properties %) pos) components)
            props-seq (cond-> stripped
                        sanitize-set? ctv/sanitize-properties-set)

            changes   (reduce (fn [changes [component props]]
                                (-> changes
                                    (pcb/update-component (:id component)
                                                          #(assoc % :variant-properties props)
                                                          {:apply-changes-local-library? true})
                                    (pcb/update-shapes [(:main-instance-id component)]
                                                       #(assoc % :variant-name (ctv/properties-to-name props)))))
                              changes
                              (map vector components props-seq))

            ;; All components of the variant change, so validate the
            ;; container, which also checks every main instance
            changes (pcb/validate-shapes changes
                                         (pcb/get-page-id changes)
                                         [variant-id]
                                         (str "generate-remove-property: " variant-id " pos: " pos))]
        changes)
      changes)))

(defn generate-update-property-value
  "Update the value of the variant property at position 'pos' for the given component and its main instance."
  [changes component-id pos value]
  (if (or (nil? component-id) (nil? pos))
    changes
    (if-let [value (ctv/prepare-property-value value)]
      (let [data      (pcb/get-library-data changes)
            objects   (pcb/get-objects changes)
            component (ctcl/get-component data component-id true)]
        (if (and component
                 (contains? objects (:main-instance-id component))
                 (< pos (count (:variant-properties component))))
          (let [main-id (:main-instance-id component)
                name    (-> (:variant-properties component)
                            (update pos assoc :value value)
                            ctv/properties-to-name)]
            (-> changes
                (pcb/update-component component-id #(assoc-in % [:variant-properties pos :value] value)
                                      {:apply-changes-local-library? true})
                (pcb/update-shapes [main-id] #(assoc % :variant-name name))
                (pcb/validate-shapes (pcb/get-page-id changes)
                                     [main-id]
                                     (str "generate-update-property-value: " component-id
                                          " pos: " pos " value: " value))))
          changes))
      changes)))

(defn generate-set-variant-error
  "Set an invalid property value as a :variant-error attribute in the main instance of the given component.
   If value is nil, remove the :variant-error attribute."
  [changes component-id value]
  (if (nil? component-id)
    changes
    (let [data      (pcb/get-library-data changes)
          component (ctcl/get-component data component-id true)]
      (if component
        (let [main-id   (:main-instance-id component)]
          (-> changes
              (pcb/update-shapes [main-id] (if (nil? value)
                                             #(dissoc % :variant-error)
                                             #(assoc % :variant-error value)))
              (pcb/validate-shapes (pcb/get-page-id changes)
                                   [main-id]
                                   (str "generate-set-variant-error: " component-id
                                        " value: " value))))
        changes))))

(defn generate-reorder-variant-poperties
  "Change the order of the variant properties for all components in the variant.
   Move the property at the given position to some space between positions.
   Clamp the positions to the valid range of property indexes."
  [changes variant-id from-pos to-space-between-pos]
  (if (or (nil? variant-id) (nil? from-pos) (nil? to-space-between-pos))
    changes
    (let [data               (pcb/get-library-data changes)
          objects            (pcb/get-objects changes)
          related-components (cfv/find-variant-components data objects variant-id)

          changes'           (reduce (fn [changes component]
                                       (let [props   (:variant-properties component)
                                             props'  (d/reorder props from-pos to-space-between-pos)
                                             main-id (:main-instance-id component)
                                             name    (ctv/properties-to-name props')]
                                         (if (not= props props')
                                           (-> changes
                                               (pcb/update-component (:id component)
                                                                     #(assoc % :variant-properties props')
                                                                     {:apply-changes-local-library? true})
                                               (pcb/update-shapes [main-id]
                                                                  #(assoc % :variant-name name)))
                                           changes)))
                                     changes
                                     related-components)]

      ;; Nothing to validate if the order did not change
      (if (identical? changes changes')
        changes
        ;; All the components of the variant change, so validate the
        ;; container, which checks every main instance and that all of them
        ;; keep the same order of properties
        (pcb/validate-shapes changes'
                             (pcb/get-page-id changes')
                             [variant-id]
                             (str "generate-reorder-variant-poperties: " variant-id
                                  " from: " from-pos " to: " to-space-between-pos))))))

(defn generate-add-new-property
  "Add a new variant property to all components in the variant and their respective main instances.

   The new property name may be given or it will be generated based on the next available property
   number. In any case, it may be modified to be unique among the other property names.

   The value may also be given, or else it will be an empty value, unless fill-values? is true, in
   which case the value will be set to a default value based on the property number.

   The editing? flag, if set, will be added to the metadata of the properties, for later use.

   The skip-validation? flag is used when this function is part of a higher level operation.
   The validation must be done after the whole operation is complete."
  [changes variant-id & {:keys [fill-values? editing? property-name property-value skip-validation?]}]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)

        props              (-> related-components last :variant-properties)
        next-prop-num      (ctv/next-property-number props)
        property-name      (if (some? property-name)
                             (ctv/prepare-property-name property-name)
                             (str ctv/property-prefix next-prop-num))]
    (if (nil? property-name)
      changes
      (let [prop-names     (mapv :name props)
            property-name  (ctv/update-number-in-repeated-item prop-names property-name)
            property-value (not-empty
                            (when property-value
                              (ctv/prepare-property-value property-value)))
            mdata          (if editing? {:editing? true} nil)

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
        ;; All components of the variant change, so validate the container
        (cond-> changes
          (and (seq related-components) (not skip-validation?))
          (pcb/validate-shapes (pcb/get-page-id changes)
                               [variant-id]
                               (str "generate-add-new-property: " variant-id
                                    " name: " property-name)))))))

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
  "Extract some components from a variant, removing the variant-id and variant-name from the 
   main instances and the variant-id and variant-properties from the components."
  [changes shapes]
  (if (empty? shapes)
    changes
    ;; Only the extracted main instances change. The container is not
    ;; validated because this is only a partial operation
    (-> (reduce generate-make-shape-no-variant changes shapes)
        (pcb/validate-shapes (pcb/get-page-id changes)
                             (mapv :id shapes)
                             (str "generate-make-shapes-no-variant: " (mapv :id shapes))))))

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
        props     (ctv/add-new-properties props (repeat new-props ""))]

    (if add-name?
      (ctv/add-new-property props (or (ctv/sanitize-property-text (:name component)) ""))
      props)))

(defn- create-new-properties-from-non-variant
  [shape min-props container-name base-properties]
  (let [;; Remove container name from shape name if present
        shape-name (ctv/remove-prefix (:name shape) container-name)]
    (ctv/path-to-properties shape-name base-properties min-props)))

(defn- fill-blank-properties
  "When every value in `props` is blank, set the last one to the first
   `Value N` not contained in `used-values`, so the variant does not end up
   with an empty combination."
  [props used-values]
  (if (and (seq props) (every? (comp str/blank? :value) props))
    (let [value (->> (iterate inc 1)
                     (map #(str ctv/value-prefix %))
                     (remove used-values)
                     first)]
      (assoc-in (vec props) [(dec (count props)) :value] value))
    props))

(defn generate-make-shapes-variant
  "Introduce some components into a variant, adding the variant-id and variant-name to the
   main instances and the variant-id and variant-properties to the components.

   It does not validate the shapes because this is only a partial operation. The validation
   must be done after the whole operation is complete."
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
                        (iterate #(generate-add-new-property % variant-id :skip-validation? true) changes)
                        num-new-props)

        changes        (pcb/update-shapes changes (map :id shapes)
                                          #(assoc % :variant-id variant-id
                                                  :name (:name variant-container)))

        ;; Property values already present in the container's variants
        used-values    (->> (cfv/extract-properties-values data objects variant-id)
                            (mapcat :value)
                            set)]
    (first
     (reduce
      (fn [[changes used-values] shape]
        (let [component     (ctcl/get-component data (:component-id shape) true)
              already-here? (= variant-id (:variant-id shape))
              prop-names    (cfv/extract-properties-names shape data)
              base-names    (map :name base-props)
              props-ok?     (= prop-names base-names)
              names-ok?     (= (cpn/merge-path-item (:path component) (:name component))
                               container-name)
              variant-ok?   (= (:variant-name shape)
                               (ctv/properties-to-name (:variant-properties component)))
              aligned?      (and props-ok? names-ok? variant-ok?)]
          (if (or (zero? num-shapes) ;; do nothing if there are no shapes
                  (and already-here? ;; or already a fully aligned child of this container
                       aligned?
                       (not (:deleted component))))
            [changes used-values]
            (let [props               (-> (create-new-properties shape total-props)
                                          (fill-blank-properties used-values))
                  variant-name        (ctv/properties-to-name props)]
              [(-> (pcb/update-component changes
                                         (:component-id shape)
                                         #(assoc % :variant-id variant-id
                                                 :variant-properties props
                                                 :name cname
                                                 :path cpath)
                                         {:apply-changes-local-library? true})
                   (pcb/update-shapes [(:id shape)]
                                      #(assoc % :variant-name variant-name)))
               (into used-values (map :value) props)]))))
      [changes used-values]
      shapes))))
