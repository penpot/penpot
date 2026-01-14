;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.variants
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.logic.variant-properties :as clvp]
   [app.common.logic.variants :as clv]
   [app.common.path-names :as cpn]
   [app.common.types.color :as clr]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
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

            updated-properties   (ctv/update-number-in-repeated-prop-names updated-properties)

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
         (when (or (seq properties-to-remove) (seq properties-to-update))
           (ev/event {::ev/name "variant-edit-property-value" ::ev/origin "workspace:rename-in-layers"}))
         (when (seq properties-to-add)
           (ev/event {::ev/name "variant-add-property" ::ev/origin "workspace:rename-in-layers"}))
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

(defn update-property-name
  "Update the variant property name on the position pos
   in all the components with this variant-id and remove the focus"
  [variant-id pos new-name {:keys [trigger]}]
  (ptk/reify ::update-property-name
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            data    (dsh/lookup-file-data state)
            objects (dsh/lookup-page-objects state)

            related-components    (cfv/find-variant-components data objects variant-id)]

        (reduce
         (fn [s related-component]
           (update-in s
                      [:files file-id :data :components (:id related-component) :variant-properties]
                      (fn [props] (mapv #(with-meta % nil) props))))
         state
         related-components)))

    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            related-components (cfv/find-variant-components data objects variant-id)

            props              (-> related-components last :variant-properties)
            valid-pos?         (> (count props) pos)
            prop-name          (when valid-pos? (-> props (nth pos) :name))

            changes            (when valid-pos?
                                 (-> (pcb/empty-changes it page-id)
                                     (pcb/with-objects objects)
                                     (pcb/with-library-data data)
                                     (clvp/generate-update-property-name variant-id pos new-name)))
            undo-id (js/Symbol)]
        (when (and valid-pos? (not= prop-name new-name))
          (rx/of
           (dwu/start-undo-transaction undo-id)
           (dch/commit-changes changes)
           (dwu/commit-undo-transaction undo-id)
           (ev/event {::ev/name "variant-edit-property-name" ::ev/origin trigger})))))))

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
  "Sets or unsets an error for a component"
  ([component-id]
   (update-error component-id nil))
  ([component-id value]
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
          (dwu/commit-undo-transaction undo-id)))))))

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
  "Remove every empty property for all components when their respective values are empty
   for all of them"
  [variant-id]
  (ptk/reify ::remove-empty-properties
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            variant-components (cfv/find-variant-components data objects variant-id)

            properties-empty-pos (->> variant-components
                                      (mapcat :variant-properties)
                                      (group-by :name)
                                      (map-indexed
                                       (fn [i [_ v]]
                                         [i (->> v
                                                 (map :value)
                                                 (remove empty?)
                                                 empty?)]))
                                      (reverse))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects))

            changes (reduce
                     (fn [changes [pos property-empty?]]
                       (if property-empty?
                         (-> changes
                             (clvp/generate-remove-property variant-id pos))
                         changes))
                     changes
                     properties-empty-pos)

            undo-id (js/Symbol)]

        (when (seq (:redo-changes changes))
          (rx/of
           (ev/event {::ev/name "variant-remove-property" ::ev/origin "workspace:rename-in-layers"})
           (dwu/start-undo-transaction undo-id)
           (dch/commit-changes changes)
           (dwu/commit-undo-transaction undo-id)))))))

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

(defn reorder-variant-poperties
  "Reorder properties by moving a property from some position to some space between positions"
  [variant-id from-pos to-space-between-pos]
  (ptk/reify ::reorder-variant-properties
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (-> (dsh/get-page data page-id)
                        (get :objects))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-library-data data)
                        (pcb/with-objects objects)
                        (clvp/generate-reorder-variant-poperties variant-id from-pos to-space-between-pos))

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
  [variant-id]
  (ptk/reify ::focus-property
    ptk/EffectEvent
    (effect [_ _ _]
      (dom/focus! (dom/get-element (str "variant-prop-" variant-id "-0"))))))

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
  ([shape-id]
   (add-new-variant shape-id false))
  ([shape-id multiselect?]
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
           (if multiselect?
             (dws/shift-select-shapes new-shape-id)
             (dws/select-shape new-shape-id)))
          (->> (rx/of (focus-property (:id variant-container)))
               (rx/delay 250))))))))

(defn transform-in-variant
  "Given the id of a main shape of a component, creates a variant structure for
   that component"
  ([main-instance-id]
   (transform-in-variant main-instance-id nil nil [] false true true))
  ([main-instance-id variant-id delta prefix add-wrapper? duplicate? flex?]
   (ptk/reify ::transform-in-variant
     ptk/WatchEvent
     (watch [_ state _]
       (let [variant-id   (or variant-id (uuid/next))
             variant-vec  [variant-id]
             file-id      (:current-file-id state)
             page-id      (:current-page-id state)
             objects      (dsh/lookup-page-objects state file-id page-id)
             main         (get objects main-instance-id)
             parent       (get objects (:parent-id main))
             component-id (:component-id main)
             name         (if add-wrapper?
                            (str "Component/" (:name main))
                            (:name main))
             ;; If there is a prefix, set is as first item of path
             cpath (-> name
                       cpn/split-path
                       (cond->
                        (seq prefix)
                         (->> (drop (count prefix))
                              (cons (cpn/join-path prefix))
                              vec)))

             name         (first cpath)
             num-props    (max 1 (dec (count cpath)))
             base-props   {:is-variant-container true
                           :name name
                           :r1 20
                           :r2 20
                           :r3 20
                           :r4 20
                           :layout-item-absolute true}
             flex-props   {:layout-item-h-sizing :auto
                           :layout-item-v-sizing :auto
                           :layout-padding {:p1 30 :p2 30 :p3 30 :p4 30}
                           :layout-gap     {:row-gap 0 :column-gap 20}}
             cont-props   (if flex?
                            (into base-props flex-props)
                            base-props)
             main-props   {:name name
                           :variant-id variant-id}

             stroke-props {:stroke-alignment :inner
                           :stroke-style :solid
                           :stroke-color "#bb97d8" ;; todo use color var?
                           :stroke-opacity 1
                           :stroke-width 2}

            ;; Move the position of the variant container so the main shape doesn't
            ;; change its position
             delta        (or delta
                              (if (ctsl/any-layout? parent)
                                (gpt/point 0 0)
                                (gpt/point -30 -30)))
             undo-id      (js/Symbol)]


        ;;TODO Refactor all called methods in order to be able to
        ;;generate changes instead of call the events


         (rx/concat
          (rx/of
           (dwu/start-undo-transaction undo-id)

           (when (not= name (:name main))
             (dwl/rename-component component-id name))

          ;; Create variant container
           (dwsh/create-artboard-from-shapes [main-instance-id] variant-id nil nil nil delta flex?)
           (cl/remove-all-fills variant-vec {:color clr/black :opacity 1})
           (when flex? (dwsl/create-layout-from-id variant-id :flex))
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
           (when duplicate? (add-new-variant main-instance-id))
           (dwsh/update-shapes variant-vec #(dissoc % :layout-item-absolute))
           (dwu/commit-undo-transaction undo-id)
           (when flex?
             (ptk/data-event :layout/update {:ids [variant-id]})))))))))

(defn add-component-or-variant
  "Manage the shared shortcut, and do the pertinent action"
  []
  (ptk/reify ::add-component-or-variant

    ptk/WatchEvent
    (watch [_ state _]
      (let [objects               (dsh/lookup-page-objects state)
            selected-ids          (dsh/lookup-selected state)
            selected-shapes       (map (d/getf objects) selected-ids)
            single?               (= 1 (count selected-ids))
            first-shape          (first selected-shapes)

            transform-in-variant? (and single?
                                       (not (ctc/is-variant? first-shape))
                                       (ctc/main-instance? first-shape))
            add-new-variant?      (every? ctc/is-variant? selected-shapes)
            undo-id              (js/Symbol)]
        (cond
          transform-in-variant?
          (rx/of
           (ev/event {::ev/name "transform-in-variant" ::ev/origin "workspace:shortcut"})
           (transform-in-variant (:id first-shape)))

          add-new-variant?
          (rx/concat
           (rx/of
            (ev/event {::ev/name "add-new-variant" ::ev/origin "workspace:shortcut-create-component"})
            (dwu/start-undo-transaction undo-id))
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
      (let [objects               (dsh/lookup-page-objects state)
            selected-ids          (dsh/lookup-selected state)
            selected-shapes       (map (d/getf objects) selected-ids)
            add-new-variant?      (every? ctc/is-variant? selected-shapes)
            undo-id              (js/Symbol)]
        (if add-new-variant?
          (rx/concat
           (rx/of
            (ev/event {::ev/name "add-new-variant" ::ev/origin "workspace:shortcut-duplicate"})
            (dwu/start-undo-transaction undo-id)
            (add-new-variant (first selected-ids) false))
           (rx/from (map #(add-new-variant % true) (rest selected-ids)))
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
            clean-name         (cpn/clean-path name)
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

(defn- bounding-rect
  "Receives a list of frames (with X, y, width and height) and
  calculates a rect that contains them all"
  [frames]
  (let [xs   (map :x frames)
        ys   (map :y frames)
        x2s  (map #(+ (:x %) (:width %)) frames)
        y2s  (map #(+ (:y %) (:height %)) frames)
        min-x (apply min xs)
        min-y (apply min ys)
        max-x (apply max x2s)
        max-y (apply max y2s)]
    {:x min-x
     :y min-y
     :width (- max-x min-x)
     :height (- max-y min-y)}))

(defn- common-prefix
  [paths]
  (->> (apply map vector paths)
       (take-while #(apply = %))
       (map first)
       vec))

(defn combine-as-variants
  [ids {:keys [page-id trigger]}]
  (ptk/reify ::combine-as-variants
    ptk/WatchEvent
    (watch [_ state stream]
      (let [current-page  (:current-page-id state)

            combine
            (fn [current-page]
              (let [objects       (dsh/lookup-page-objects state current-page)
                    ids           (->> ids
                                       (cfh/clean-loops objects)
                                       (remove (fn [id]
                                                 (let [shape (get objects id)]
                                                   (or (not (ctc/main-instance? shape))
                                                       (ctc/is-variant? shape))))))]
                (when (> (count ids) 1)
                  (let [shapes        (mapv #(get objects %) ids)
                        rect          (bounding-rect shapes)
                        prefix        (->> shapes
                                           (mapv #(cpn/split-path (:name %)))
                                           (common-prefix))
                         ;; When the common parent is root, add a wrapper
                        add-wrapper?  (empty? prefix)
                        first-shape   (first shapes)
                        delta         (gpt/point (- (:x rect) (:x first-shape) 30)
                                                 (- (:y rect) (:y first-shape) 30))
                        common-parent (->> ids
                                           (mapv #(-> (cfh/get-parent-ids objects %) reverse))
                                           common-prefix
                                           last)
                        index         (-> (get objects common-parent)
                                          :shapes
                                          count
                                          inc)
                        variant-id    (uuid/next)
                        undo-id       (js/Symbol)]

                    (rx/concat
                     (if (and page-id (not= current-page page-id))
                       (rx/of (dcm/go-to-workspace :page-id page-id))
                       (rx/empty))

                     (rx/of (dwu/start-undo-transaction undo-id)
                            (transform-in-variant (first ids) variant-id delta prefix add-wrapper? false false)
                            (dwsh/relocate-shapes (into #{} (-> ids rest reverse)) variant-id 0)
                            (dwsh/update-shapes ids #(-> %
                                                         (assoc :constraints-h :left)
                                                         (assoc :constraints-v :top)
                                                         (assoc :fixed-scroll false)))
                            (dwsh/relocate-shapes #{variant-id} common-parent index)
                            (dwt/update-dimensions [variant-id] :width (+ (:width rect) 60))
                            (dwt/update-dimensions [variant-id] :height (+ (:height rect) 60))
                            (ev/event {::ev/name "combine-as-variants" ::ev/origin trigger :number-of-combined (count ids)}))

                      ;; NOTE: we need to schedule a commit into a
                      ;; microtask for ensure that all the scheduled
                      ;; microtask of previous events execute before the
                      ;; commit
                     (->> (rx/of (dwu/commit-undo-transaction undo-id))
                          (rx/observe-on :async)))))))

            redirect-to-page
            (fn [page-id]
              (rx/merge
               (->> stream
                    (rx/filter (ptk/type? ::dwpg/initialize-page))
                    (rx/take 1)
                    (rx/observe-on :async)
                    (rx/mapcat (fn [_] (combine page-id))))
               (rx/of (dcm/go-to-workspace :page-id page-id))))]

        (if (and page-id (not= page-id current-page))
          (redirect-to-page page-id)
          (combine current-page))))))

(defn combine-selected-as-variants
  [options]
  (ptk/reify ::combine-selected-as-variants
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (combine-as-variants selected options))))))

(defn- variant-switch
  "Switch the shape (that must be a variant copy head) for the closest one with the property value passed as parameter"
  [shape {:keys [pos val] :as params}]
  (ptk/reify ::variant-switch
    ptk/WatchEvent
    (watch [_ state _]
      (let [libraries    (dsh/lookup-libraries state)
            component-id (:component-id shape)
            component    (ctf/get-component libraries (:component-file shape) component-id :include-deleted? false)]
             ;; If the value is already val, do nothing
        (when (not= val (dm/get-in component [:variant-properties pos :value]))
          (let [current-page-objects   (dsh/lookup-page-objects state)
                variant-id             (:variant-id component)
                component-file-data    (dm/get-in libraries [(:component-file shape) :data])
                component-page-objects (-> (dsh/get-page component-file-data (:main-instance-page component))
                                           (get :objects))
                variant-comps          (cfv/find-variant-components component-file-data component-page-objects variant-id)
                target-props           (-> (:variant-properties component)
                                           (update pos assoc :value val))
                valid-comps            (->> variant-comps
                                            (remove #(= (:id %) component-id))
                                            (filter #(= (dm/get-in % [:variant-properties pos :value]) val))
                                            (reverse))
                nearest-comp           (apply min-key #(ctv/distance target-props (:variant-properties %)) valid-comps)
                shape-parents          (cfh/get-parents-with-self current-page-objects (:parent-id shape))
                nearest-comp-children  (cfh/get-children-with-self component-page-objects (:main-instance-id nearest-comp))
                comps-nesting-loop?    (seq? (cfh/components-nesting-loop? nearest-comp-children shape-parents))

                {:keys [on-error]
                 :or {on-error rx/throw}} (meta params)]

            ;; If there is no nearest-comp, do nothing
            (when nearest-comp
              (if comps-nesting-loop?
                (do
                  (on-error)
                  (rx/empty))
                (rx/of
                 (dwl/component-swap shape (:component-file shape) (:id nearest-comp) true)
                 (ev/event {::ev/name "variant-switch" ::ev/origin "workspace:design-tab"}))))))))))

(defn variants-switch
  "Switch each shape (that must be a variant copy head) for the closest one with the property value passed as parameter"
  [{:keys [shapes] :as params}]
  (ptk/reify ::variants-switch
    ptk/WatchEvent
    (watch [_ _ _]
      (let [ids (into (d/ordered-set) d/xf:map-id shapes)
            undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (->> (rx/from shapes)
              (rx/map #(variant-switch % params)))
         (rx/of (dwu/commit-undo-transaction undo-id)
                (dws/select-shapes ids)))))))

