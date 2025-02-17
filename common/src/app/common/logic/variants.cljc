;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.common.logic.variants
  (:require
   [app.common.files.changes-builder :as pcb]
   [cuerdas.core :as str]))


(defn properties-to-name
  [properties]
  (->> properties
       (map :value)
       (str/join ", ")))

(defn generate-update-property-name
  [changes related-components pos new-name]
  (reduce (fn [changes component]
            (pcb/update-component
             changes (:id component)
             #(assoc-in % [:variant-properties pos :name] new-name)))
          changes
          related-components))


(defn generate-remove-property
  [changes related-components pos]
  (reduce (fn [changes component]
            (let [props   (:variant-properties component)
                  props   (vec (concat (subvec props 0 pos) (subvec props (inc pos))))
                  main-id (:main-instance-id component)
                  name    (properties-to-name props)]
              (-> changes
                  (pcb/update-component (:id component) #(assoc % :variant-properties props))
                  (pcb/update-shapes [main-id] #(assoc % :variant-name name)))))
          changes
          related-components))


(defn generate-update-property-value
  [changes component-id main-id pos value name]
  (-> changes
      (pcb/update-component component-id #(assoc-in % [:variant-properties pos :value] value))
      (pcb/update-shapes [main-id] #(assoc % :variant-name name))))

(defn generate-add-new-property
  [changes related-components property-name]
  (let [[_ changes]
        (reduce (fn [[num changes] component]
                  (let [props        (-> (or (:variant-properties component) [])
                                         (conj {:name property-name :value (str "Value" num)}))
                        main-id      (:main-instance-id component)
                        variant-name (properties-to-name props)]
                    [(inc num)
                     (-> changes
                         (pcb/update-component (:id component) #(assoc % :variant-properties props))
                         (pcb/update-shapes [main-id] #(assoc % :variant-name variant-name)))]))
                [1 changes]
                related-components)]
    changes))
