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
  [changes variant-id & {:keys [fill-values? property-name]}]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (cfv/find-variant-components data objects variant-id)

        props              (-> related-components last :variant-properties)
        next-prop-num      (ctv/next-property-number props)
        property-name      (or property-name (str ctv/property-prefix next-prop-num))

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
  (let [data      (pcb/get-library-data changes)
        component (ctcl/get-component data (:component-id shape) true)
        new-name (str (:name component)
                      " / "
                      (if (ctk/is-variant? shape)
                        (str/replace (:variant-name shape) #", " " / ")
                        (:name shape)))
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

(defn generate-make-shapes-variant
  [changes shapes variant-container]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)

        container-name     (:name variant-container)
        long-name          (str container-name " / ")

        get-base-name      (fn [shape]
                             (let [component (ctcl/get-component data (:component-id shape) true)

                                   name      (if (some? (:variant-name shape))
                                               (str (:name component)
                                                    " / "
                                                    (str/replace (:variant-name shape) #", " " / "))
                                               (:name shape))]
                                 ;; When the name starts by the same name that the container,
                                 ;; we should ignore that part of the name
                               (cond
                                 (str/starts-with? name long-name)
                                 (subs name (count long-name))

                                 (str/starts-with? name container-name)
                                 (subs name (count container-name))

                                 :else
                                 name)))

        calc-num-props     #(-> %
                                get-base-name
                                cfh/split-path
                                count)

        max-path-items     (apply max (map calc-num-props shapes))

        ;; If we are cut-pasting a variant-container, this will be null
        ;; because it hasn't any shapes yet
        first-comp-id      (->> variant-container
                                :shapes
                                first
                                (get objects)
                                :component-id)

        variant-properties (get-in data [:components first-comp-id :variant-properties])
        num-props          (count variant-properties)
        num-new-props      (if (or (nil? first-comp-id)
                                   (< max-path-items num-props))
                             0
                             (- max-path-items num-props))
        total-props        (+ num-props num-new-props)

        changes            (nth
                            (iterate #(generate-add-new-property % (:id variant-container)) changes)
                            num-new-props)

        changes            (pcb/update-shapes changes (map :id shapes)
                                              #(assoc % :variant-id (:id variant-container)
                                                      :name (:name variant-container)))]
    (reduce
     (fn [changes shape]
       (if (or (nil? first-comp-id)
               (= (:id variant-container) (:variant-id shape)))
         changes ;; do nothing if we aren't changing the parent
         (let [base-name           (get-base-name shape)

               ;; we need to get the updated library data to have access to the current properties
               data                (pcb/get-library-data changes)

               props               (ctv/path-to-properties
                                    base-name
                                    (get-in data [:components first-comp-id :variant-properties])
                                    total-props)



               variant-name        (ctv/properties-to-name props)
               [cpath cname]       (cfh/parse-path-name (:name variant-container))]
           (-> (pcb/update-component changes
                                     (:component-id shape)
                                     #(assoc % :variant-id (:id variant-container)
                                             :variant-properties props
                                             :name cname
                                             :path cpath)
                                     {:apply-changes-local-library? true})
               (pcb/update-shapes [(:id shape)]
                                  #(assoc % :variant-name variant-name))))))
     changes
     shapes)))

