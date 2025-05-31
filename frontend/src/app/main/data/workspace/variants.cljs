;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.variants
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.logic.variant-properties :as clvp]
   [app.common.logic.variants :as clv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn update-properties-names-and-values
  "Compares the previous properties with the updated ones and executes the correspondent action
   for each one depending on if it needs to be removed, updated or added"
  [component-id variant-id previous-properties updated-properties]
  (ptk/reify ::update-properties-names-and-values
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :shape-for-rename))

    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            properties-to-remove (ctv/find-properties-to-remove previous-properties updated-properties)
            properties-to-add    (ctv/find-properties-to-add previous-properties updated-properties)
            properties-to-update (ctv/find-properties-to-update previous-properties updated-properties)

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data))

            changes (reduce
                     (fn [changes {:keys [name]}]
                       (-> changes
                           (clvp/generate-update-property-value component-id (ctv/find-index-for-property-name previous-properties name) "")))
                     changes
                     properties-to-remove)

            changes (reduce
                     (fn [changes {:keys [name value]}]
                       (-> changes
                           (clvp/generate-update-property-value component-id (ctv/find-index-for-property-name previous-properties name) value)))
                     changes
                     properties-to-update)

            changes (reduce
                     (fn [changes [idx {:keys [name value]}]]
                       (-> changes
                           (clvp/generate-add-new-property variant-id {:property-name name})
                           (clvp/generate-update-property-value component-id (+ idx (count previous-properties)) value)))
                     changes
                     (map-indexed vector properties-to-add))

            undo-id (js/Symbol)]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn update-property-name
  "Update the variant property name on the position pos
   in all the components with this variant-id"
  [variant-id pos new-name]
  (ptk/reify ::update-property-name
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data)
                        (clvp/generate-update-property-name variant-id pos new-name))
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
      (let [page-id    (:current-page-id state)
            data       (dsh/lookup-file-data state)
            objects    (-> (dsh/get-page data page-id)
                           (get :objects))

            changes    (-> (pcb/empty-changes it page-id)
                           (pcb/with-library-data data)
                           (pcb/with-objects objects)
                           (clvp/generate-update-property-value component-id pos value))
            undo-id    (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))


(defn update-error
  "Updates the error in a component"
  [component-id value]
  (ptk/reify ::update-error
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id    (:current-page-id state)
            data       (dsh/lookup-file-data state)
            objects    (-> (dsh/get-page data page-id)
                           (get :objects))

            changes    (-> (pcb/empty-changes it page-id)
                           (pcb/with-library-data data)
                           (pcb/with-objects objects)
                           (clvp/generate-set-variant-error component-id value))
            undo-id    (js/Symbol)]
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
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects)
                        (clvp/generate-remove-property variant-id pos))

            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))


(defn remove-empty-properties
  "Remove a property for all components when its value is empty for all of them"
  [variant-id]
  (ptk/reify ::remove-empty-properties
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            variant-components (cfv/find-variant-components data objects variant-id)

            properties-empty   (->> variant-components
                                    (mapcat :variant-properties)
                                    (group-by :name)
                                    (mapv (fn [[_ v]]
                                            (->> v (mapv :value) (remove empty?))))
                                    (mapv empty?))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects))

            changes (reduce
                     (fn [changes [idx property-empty?]]
                       (if property-empty?
                         (-> changes
                             (clvp/generate-remove-property variant-id idx))
                         changes))
                     changes
                     (map-indexed vector properties-empty))

            undo-id (js/Symbol)]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))


(defn add-new-property
  "Add a new variant property to all the components with this variant-id"
  [variant-id & [options]]
  (ptk/reify ::add-new-property
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects)
                        (clvp/generate-add-new-property variant-id options))

            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn- set-variant-id
  "Sets the variant-id on a component"
  [component-id variant-id]
  (ptk/reify ::set-variant-id
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/update-component component-id #(assoc % :variant-id variant-id)))
            undo-id  (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn- focus-property
  [shape-id prop-num]
  (ptk/reify ::focus-property
    ptk/EffectEvent
    (effect [_ _ _]
      (dom/focus! (dom/get-element (str "variant-prop-" shape-id prop-num))))))


(defn- resposition-and-resize-variant
  "Resize the variant container, and move the shape (that is a variant) to the right"
  [shape-id]
  (ptk/reify ::resposition-and-resize-variant
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            shape     (get objects shape-id)
            container (get objects (:parent-id shape))
            width     (+ (:width container) (:width shape) 20) ;; 20 is the default gap for variants
            x         (- width (+ (:width shape) 30))]         ;; 30 is the default margin for variants
        (rx/of
         (dwt/update-dimensions [(:parent-id shape)] :width width)
         (dwt/update-position shape-id
                              {:x x}
                              {:absolute? false}))))))


(defn add-new-variant
  "Create a new variant and add it to the variant-container"
  [shape-id]
  (ptk/reify ::add-new-variant
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id             (:current-page-id state)
            data                (dsh/lookup-file-data state)
            objects             (-> (dsh/get-page data page-id)
                                    (get :objects))
            shape               (get objects shape-id)
            shape               (if (ctc/is-variant-container? shape)
                                  (get objects (last (:shapes shape)))
                                  shape)
            component-id        (:component-id shape)
            component           (ctkl/get-component data component-id)

            container-id        (:parent-id shape)
            variant-container   (get objects container-id)
            has-layout?         (ctsl/any-layout? variant-container)

            new-component-id    (uuid/next)
            new-shape-id        (uuid/next)

            prop-num            (dec (count (:variant-properties component)))

            changes             (-> (pcb/empty-changes it page-id)
                                    (pcb/with-library-data data)
                                    (pcb/with-objects objects)
                                    (pcb/with-page-id page-id)
                                    (clv/generate-add-new-variant shape (:variant-id component) new-component-id new-shape-id prop-num))

            undo-id             (js/Symbol)]
        (rx/concat
         (rx/of
          (dwu/start-undo-transaction undo-id)
          (dch/commit-changes changes)
          (when-not has-layout?
            (resposition-and-resize-variant new-shape-id))
          (dwu/commit-undo-transaction undo-id)
          (ptk/data-event :layout/update {:ids [(:parent-id shape)]})
          (dws/select-shape new-shape-id))
         (->> (rx/of (focus-property new-shape-id prop-num))
              (rx/delay 250)))))))

(defn transform-in-variant
  "Given the id of a main shape of a component, creates a variant structure for
   that component"
  [main-instance-id]
  (ptk/reify ::transform-in-variant
    ptk/WatchEvent
    (watch [_ state _]
      (let [variant-id (uuid/next)
            variant-vec [variant-id]
            file-id      (:current-file-id state)
            page-id      (:current-page-id state)
            objects      (dsh/lookup-page-objects state file-id page-id)
            main         (get objects main-instance-id)
            parent       (get objects (:parent-id main))
            component-id (:component-id main)
            cpath        (cfh/split-path (:name main))
            name         (first cpath)
            num-props    (max 1 (dec (count cpath)))
            cont-props   {:layout-item-h-sizing :auto
                          :layout-item-v-sizing :auto
                          :layout-padding {:p1 30 :p2 30 :p3 30 :p4 30}
                          :layout-gap     {:row-gap 0 :column-gap 20}
                          :name name
                          :r1 20
                          :r2 20
                          :r3 20
                          :r4 20
                          :is-variant-container true}
            main-props   {:layout-item-h-sizing :fix
                          :layout-item-v-sizing :fix
                          :variant-id variant-id
                          :name name}
            stroke-props {:stroke-alignment :inner
                          :stroke-style :solid
                          :stroke-color "#bb97d8" ;; todo use color var?
                          :stroke-opacity 1
                          :stroke-width 2}

            ;; Move the position of the variant container so the main shape doesn't
            ;; change its position
            delta        (if (ctsl/any-layout? parent)
                           (gpt/point 0 0)
                           (gpt/point -30 -30))
            undo-id      (js/Symbol)]


        ;;TODO Refactor all called methods in order to be able to
        ;;generate changes instead of call the events
        (rx/concat
         (rx/of
          (dwu/start-undo-transaction undo-id)

          (when (not= name (:name main))
            (dwl/rename-component component-id name))

          ;; Create variant container
          (dwsh/create-artboard-from-selection variant-id nil nil nil delta)
          (cl/remove-all-fills variant-vec {:color clr/black :opacity 1})
          (dwsl/create-layout-from-id variant-id :flex)
          (dwsh/update-shapes variant-vec #(merge % cont-props))
          (dwsh/update-shapes [main-instance-id] #(merge % main-props))
          (cl/add-stroke variant-vec stroke-props)
          (set-variant-id component-id variant-id))

         ;; Add the necessary number of new properties, with default values
         (rx/from
          (repeatedly num-props
                      #(add-new-property variant-id {:fill-values? true})))

         ;; When the component has path, set the path items as properties values
         (when (> (count cpath) 1)
           (rx/from
            (map
             #(update-property-value component-id % (nth cpath (inc %)))
             (range num-props))))

         (rx/of
          (add-new-variant main-instance-id)
          (dwu/commit-undo-transaction undo-id)
          (ptk/data-event :layout/update {:ids [variant-id]})))))))

(defn add-component-or-variant
  "Manage the shared shortcut, and do the pertinent action"
  []
  (ptk/reify ::add-component-or-variant

    ptk/WatchEvent
    (watch [_ state _]
      (let [variants?             (features/active-feature? state "variants/v1")
            objects               (dsh/lookup-page-objects state)
            selected-ids          (dsh/lookup-selected state)
            selected-shapes       (map (d/getf objects) selected-ids)
            single?               (= 1 (count selected-ids))
            first-shape          (first selected-shapes)

            transform-in-variant? (and variants?
                                       single?
                                       (not (ctc/is-variant? first-shape))
                                       (ctc/main-instance? first-shape))
            add-new-variant?      (and variants?
                                       (every? ctc/is-variant? selected-shapes))
            undo-id              (js/Symbol)]
        (cond
          transform-in-variant?
          (rx/of (transform-in-variant (:id first-shape)))

          add-new-variant?
          (rx/concat
           (rx/of (dwu/start-undo-transaction undo-id))
           (rx/from (map add-new-variant selected-ids))
           (rx/of (dwu/commit-undo-transaction undo-id)))

          :else
          (rx/of (dwl/add-component)))))))

(defn duplicate-or-add-variant
  "Manage the shared shortcut, and do the pertinent action"
  []
  (ptk/reify ::duplicate-or-add-variant
    ptk/WatchEvent
    (watch [_ state _]
      (let [variants?             (features/active-feature? state "variants/v1")
            objects               (dsh/lookup-page-objects state)
            selected-ids          (dsh/lookup-selected state)
            selected-shapes       (map (d/getf objects) selected-ids)
            add-new-variant?      (and variants?
                                       (every? ctc/is-variant? selected-shapes))
            undo-id              (js/Symbol)]
        (if add-new-variant?
          (rx/concat
           (rx/of (dwu/start-undo-transaction undo-id))
           (rx/from (map add-new-variant selected-ids))
           (rx/of (dwu/commit-undo-transaction undo-id)))
          (rx/of (dws/duplicate-selected true)))))))


(defn rename-variant
  "Rename the variant container and all components belonging to this variant"
  [variant-id name]
  (ptk/reify ::rename-variant

    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id            (:current-page-id state)
            data               (dsh/lookup-file-data state)
            objects            (-> (dsh/get-page data page-id)
                                   (get :objects))
            variant-components (cfv/find-variant-components data objects variant-id)
            clean-name         (cfh/clean-path name)
            undo-id            (js/Symbol)]

        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id)
                (dwsh/update-shapes [variant-id] #(assoc % :name clean-name)))
         (rx/from (map
                   #(dwl/rename-component-and-main-instance (:id %) clean-name)
                   variant-components))
         (rx/of (dwu/commit-undo-transaction undo-id)))))))


(defn rename-comp-or-variant-and-main
  "If the component is in a variant, rename the variant.
   If it is not, rename the component and its main"
  [component-id name]
  (ptk/reify ::rename-comp-or-variant-and-main

    ptk/WatchEvent
    (watch [_ state _]
      (let [data               (dsh/lookup-file-data state)
            component          (ctkl/get-component data component-id)]
        (if (ctc/is-variant? component)
          (rx/of (rename-variant (:variant-id component) name))
          (rx/of (dwl/rename-component-and-main-instance component-id name)))))))

