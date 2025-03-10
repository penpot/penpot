;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.variants
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.variants :as clv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(dm/export clv/find-related-components)

(defn is-secondary-variant?
  [component data]
  (if-let [variant-id (:variant-id component)]
    (let [page-id (:main-instance-page component)
          objects (-> (dsh/get-page data page-id)
                      (get :objects))
          shapes  (dm/get-in objects [variant-id :shapes])]
      (not= (:main-instance-id component) (last shapes)))
    false))

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
                        (clv/generate-update-property-name variant-id pos new-name))
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
                           (clv/generate-update-property-value component-id pos value))
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
                        (clv/generate-remove-property variant-id pos))

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
                        (clv/generate-add-new-property variant-id options))

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

(defn focus-property
  [shape-id prop-num]
  (ptk/reify ::focus-property
    ptk/EffectEvent
    (effect [_ _ _]
      (dom/focus! (dom/get-element (str "variant-prop-" shape-id prop-num))))))


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

            new-component-id    (uuid/next)
            new-shape-id        (uuid/next)

            value               (str clv/value-prefix
                                     (-> (clv/extract-properties-values data objects (:variant-id component))
                                         last
                                         :value
                                         count
                                         inc))

            prop-num            (dec (count (:variant-properties component)))


            [new-shape changes] (-> (pcb/empty-changes it page-id)
                                    (pcb/with-library-data data)
                                    (pcb/with-objects objects)
                                    (pcb/with-page-id page-id)
                                    (cll/generate-duplicate-component
                                     {:data data}
                                     component-id
                                     new-component-id
                                     true
                                     {:new-shape-id new-shape-id :apply-changes-local-library? true}))

            changes             (-> changes
                                    (clv/generate-update-property-value new-component-id prop-num value)
                                    (pcb/change-parent (:parent-id shape) [new-shape] 0))

            undo-id             (js/Symbol)]
        (rx/concat
         (rx/of
          (dwu/start-undo-transaction undo-id)
          (dch/commit-changes changes)
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
            main-id      (:id main)
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
            undo-id      (js/Symbol)]


        (rx/concat
         (rx/of
          (dwu/start-undo-transaction undo-id)

          (when (not= name (:name main))
            (dwl/rename-component component-id name))

          ;; Create variant container
          (dwsh/create-artboard-from-selection variant-id)
          (cl/remove-all-fills variant-vec {:color clr/black :opacity 1})
          (dwsl/create-layout-from-id variant-id :flex)
          (dwsh/update-shapes variant-vec #(merge % cont-props))
          (dwsh/update-shapes [main-id] #(merge % main-props))
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
          (dwu/commit-undo-transaction undo-id)))))))

(defn add-component-or-variant
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
