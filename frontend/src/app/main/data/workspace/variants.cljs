;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.variants
  (:require
   [app.common.colors :as clr]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))


(defn properties-to-name
  [properties]
  (->> properties
       (map :value)
       (str/join ", ")))

(defn update-property-name
  "Update the variant property name on the position pos
   in all the components with this variant-id"
  [variant-id pos new-name]
  (ptk/reify ::update-property-name
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)

            related-components (->> (:components data)
                                    vals
                                    (filter #(= (:variant-id %) variant-id)))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data))

            changes (reduce (fn [changes component]
                              (pcb/update-component
                               changes (:id component)
                               #(assoc-in % [:variant-properties pos :name] new-name)))
                            changes
                            related-components)
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))




(defn remove-property
  "Remove the variant property on the position pos
   in all the components with this variant-id"
  [variant-id pos]
  (ptk/reify ::remove-property
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects    (dsh/lookup-page-objects state page-id)

            related-components (->> (:components data)
                                    vals
                                    (filter #(= (:variant-id %) variant-id)))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects))

            changes (reduce (fn [changes component]
                              (let [props   (:variant-properties component)
                                    props   (vec (concat (subvec props 0 pos) (subvec props (inc pos))))
                                    main-id (:main-instance-id component)
                                    name    (properties-to-name props)]
                                (-> changes
                                    (pcb/update-component (:id component) #(assoc % :variant-properties props))
                                    (pcb/update-shapes [main-id] #(assoc % :variant-name name)))))
                            changes
                            related-components)
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))


(defn update-property-value
  "Updates the variant property value on the position pos in a component"
  [component-id pos value]
  (ptk/reify ::update-property-value
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            data      (dsh/lookup-file-data state)
            objects   (dsh/lookup-page-objects state page-id)
            component (dm/get-in data [:components component-id])
            main-id   (:main-instance-id component)
            properties (-> (:variant-properties component)
                           (assoc-in [pos :value] value))

            name       (properties-to-name properties)

            changes   (-> (pcb/empty-changes it page-id)
                          (pcb/with-library-data data)
                          (pcb/with-objects objects)
                          (pcb/update-component component-id #(assoc-in % [:variant-properties pos :value] value))
                          (pcb/update-shapes [main-id] #(assoc % :variant-name name)))
            undo-id   (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn add-new-property
  "Add a new variant property to all the components with this variant-id"
  [variant-id]
  (ptk/reify ::add-new-property
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects    (dsh/lookup-page-objects state page-id)

            related-components (->> (:components data)
                                    vals
                                    (filter #(= (:variant-id %) variant-id))
                                    reverse)


            property-name (str "Property" (-> related-components
                                              first
                                              :variant-properties
                                              count
                                              inc))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects))

            [_ changes]
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
                    related-components)
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn set-variant-id
  "Sets the variant-id on a component"
  [component-id variant-id]
  (ptk/reify ::set-variant-id
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id      (:current-page-id state)
            data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/update-component component-id #(assoc % :variant-id variant-id)))
            undo-id  (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn transform-in-variant
  "Given the id of a main shape of a component, creates a variant structure for
   that component"
  [id]
  (ptk/reify ::transform-in-variant
    ptk/WatchEvent
    (watch [_ state _]
      (let [variant-id (uuid/next)
            new-component-id (uuid/next)
            file-id      (:current-file-id state)
            page-id      (:current-page-id state)
            objects      (dsh/lookup-page-objects state page-id)
            main         (get objects id)
            main-id      (:id main)
            undo-id  (js/Symbol)]


        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/create-artboard-from-selection variant-id)
         (cl/remove-all-fills [variant-id] {:color clr/black :opacity 1})
         (dwsl/create-layout-from-id variant-id :flex)
         (dwsh/update-shapes [variant-id] #(assoc % :layout-item-h-sizing :auto
                                                  :layout-item-v-sizing :auto
                                                  :layout-padding {:p1 30 :p2 30 :p3 30 :p4 30}
                                                  :layout-gap     {:row-gap 0 :column-gap 20}
                                                  :name (:name main)
                                                  :r1 20
                                                  :r2 20
                                                  :r3 20
                                                  :r4 20
                                                  :is-variant-container true))
         (dwsh/update-shapes [main-id] #(assoc % :layout-item-h-sizing :fix :layout-item-v-sizing :fix :variant-id variant-id))
         (cl/add-stroke [variant-id] {:stroke-alignment :inner
                                      :stroke-style :solid
                                      :stroke-color "#bb97d8" ;; todo use color var?
                                      :stroke-opacity 1
                                      :stroke-width 2})
         (dwl/duplicate-component file-id (:component-id main) new-component-id)
         (set-variant-id (:component-id main) variant-id)
         (set-variant-id new-component-id variant-id)
         (add-new-property variant-id)
         (dwu/commit-undo-transaction undo-id))))))
