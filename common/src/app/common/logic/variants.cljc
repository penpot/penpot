;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.common.logic.variants
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.types.components-list :as ctcl]
   [cuerdas.core :as str]))



(def property-prefix "Property")
(def property-regex (re-pattern (str property-prefix "(\\d+)")))
(def value-prefix "Value")

(defn find-related-components
  "Find a list of the components thet belongs to this variant-id"
  [data objects variant-id]
  (->> (dm/get-in objects [variant-id :shapes])
       (map #(dm/get-in objects [% :component-id]))
       (map #(ctcl/get-component data % true))
       reverse))


(defn properties-to-name
  "Transform the properties into a name, with the values separated by comma"
  [properties]
  (->> properties
       (map :value)
       (remove str/empty?)
       (str/join ", ")))


(defn next-property-number
  "Returns the next property number, to avoid duplicates on the property names"
  [properties]
  (let [numbers (keep
                 #(some->> (:name %) (re-find property-regex) second d/parse-integer)
                 properties)
        max-num (if (seq numbers)
                  (apply max numbers)
                  0)]
    (inc (max max-num (count properties)))))


(defn path-to-properties
  "From a list of properties and a name with path, assign each token of the
   path as value of a different property"
  [path properties]
  (let [next-prop-num  (next-property-number properties)
        cpath          (cfh/split-path path)
        assigned       (mapv #(assoc % :value (nth cpath %2 "")) properties (range))
        remaining      (drop (count properties) cpath)
        new-properties (map-indexed (fn [i v] {:name (str property-prefix (+ next-prop-num i))
                                               :value v}) remaining)]
    (into assigned new-properties)))

(defn- dashes-to-end
  [property-values]
  (let [dashes (if (some #(= % "--") property-values) ["--"] [])]
    (concat (remove #(= % "--") property-values) dashes)))


(defn extract-properties-values
  [data objects variant-id]
  (->> (find-related-components data objects variant-id)
       (mapcat :variant-properties)
       (group-by :name)
       (map (fn [[k v]]
              {:name k
               :value (->> v
                           (map #(if (str/empty? (:value %)) "--" (:value %)))
                           distinct
                           dashes-to-end)}))))


(defn generate-update-property-name
  [changes variant-id pos new-name]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (find-related-components data objects variant-id)]
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
        related-components (find-related-components data objects variant-id)]
    (reduce (fn [changes component]
              (let [props   (:variant-properties component)
                    props   (d/remove-at-index props pos)
                    main-id (:main-instance-id component)
                    name    (properties-to-name props)]
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
                      properties-to-name)]
    (-> changes
        (pcb/update-component component-id #(assoc-in % [:variant-properties pos :value] value)
                              {:apply-changes-local-library? true})
        (pcb/update-shapes [main-id] #(assoc % :variant-name name)))))


(defn generate-add-new-property
  [changes variant-id & {:keys [fill-values?]}]
  (let [data               (pcb/get-library-data changes)
        objects            (pcb/get-objects changes)
        related-components (find-related-components data objects variant-id)

        props              (-> related-components first :variant-properties)
        next-prop-num      (next-property-number props)
        property-name      (str property-prefix next-prop-num)

        [_ changes]
        (reduce (fn [[num changes] component]
                  (let [main-id      (:main-instance-id component)

                        update-props #(-> (d/nilv % [])
                                          (conj {:name property-name
                                                 :value (if fill-values? (str value-prefix num) "")}))

                        update-name #(if fill-values?
                                       (if (str/empty? %)
                                         (str value-prefix num)
                                         (str % ", " value-prefix num))
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

