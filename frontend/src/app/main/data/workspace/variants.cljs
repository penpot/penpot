;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.variants
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.rect :as grc]
   [app.common.logic.variant-properties :as clvp]
   [app.common.logic.variants :as clv]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- variant-changes
  "The empty changes of the page `page-id` (the current one by default), with
  its objects and the data of the file."
  [it state & {:keys [page-id] :or {page-id (:current-page-id state)}}]
  (let [data (dsh/lookup-file-data state)]
    (-> (pcb/empty-changes it page-id)
        (pcb/with-library-data data)
        (pcb/with-objects (:objects (dsh/get-page data page-id))))))

(defn rename-member
  "Rename the component `component-id` of the variant `variant-id` from the
  layers panel, with the text `name` (`clvp/generate-rename-member`)."
  [component-id variant-id name]
  (ptk/reify ::rename-member
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :shape-for-rename))

    ptk/WatchEvent
    (watch [it state _]
      (let [previous (-> (dsh/lookup-file-data state)
                         (ctkl/get-component component-id)
                         :variant-properties)
            updated  (ctv/update-number-in-repeated-prop-names
                      (if (ctv/valid-properties-formula? name) (ctv/properties-formula->map name) []))
            to-add   (ctv/find-properties-to-add previous updated)
            changes  (-> (variant-changes it state)
                         (clvp/generate-rename-member component-id variant-id name))
            removed? (< (count (-> (pcb/get-library-data changes)
                                   (ctkl/get-component component-id)
                                   :variant-properties))
                        (+ (count previous) (count to-add)))]
        (rx/of
         (when (or (seq (ctv/find-properties-to-remove previous updated))
                   (seq (ctv/find-properties-to-update previous updated)))
           (ev/event {::ev/name "variant-edit-property-value" ::ev/origin "workspace:rename-in-layers"}))
         (when (seq to-add)
           (ev/event {::ev/name "variant-add-property" ::ev/origin "workspace:rename-in-layers"}))
         (when removed?
           (ev/event {::ev/name "variant-remove-property" ::ev/origin "workspace:rename-in-layers"}))
         (dch/commit-changes changes))))))

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
      (let [data       (dsh/lookup-file-data state)
            objects    (dsh/lookup-page-objects state)
            props      (-> (cfv/find-variant-components data objects variant-id) last :variant-properties)
            valid-pos? (> (count props) pos)
            prop-name  (when valid-pos? (-> props (nth pos) :name))]
        (when (and valid-pos? (not= prop-name new-name))
          (rx/of
           (dch/commit-changes (-> (variant-changes it state)
                                   (clvp/generate-update-property-name variant-id pos new-name)))
           (ev/event {::ev/name "variant-edit-property-name" ::ev/origin trigger})))))))

(defn update-property-value
  "Set the value of the variant property at `pos` of the components
  `component-ids`, and clear their errors."
  [component-ids pos value]
  (ptk/reify ::update-property-value
    ptk/WatchEvent
    (watch [it state _]
      (rx/of (dch/commit-changes
              (reduce (fn [changes id]
                        (-> changes
                            (clvp/generate-update-property-value id pos value)
                            (clvp/generate-set-variant-error id nil)))
                      (variant-changes it state)
                      component-ids))))))

(defn update-error
  "Sets or unsets an error for a component"
  ([component-id]
   (update-error component-id nil))
  ([component-id value]
   (ptk/reify ::update-error
     ptk/WatchEvent
     (watch [it state _]
       (rx/of (dch/commit-changes (-> (variant-changes it state)
                                      (clvp/generate-set-variant-error component-id value))))))))

(defn remove-property
  "Remove the variant property on the position pos
   in all the components with this variant-id"
  [variant-id pos]
  (ptk/reify ::remove-property
    ptk/WatchEvent
    (watch [it state _]
      (rx/of (dch/commit-changes (-> (variant-changes it state)
                                     (clvp/generate-remove-property variant-id pos)))))))

(defn remove-empty-properties
  "Remove every empty property for all components when their respective values are empty
   for all of them"
  [variant-id]
  (ptk/reify ::remove-empty-properties
    ptk/WatchEvent
    (watch [it state _]
      (let [changes (-> (variant-changes it state)
                        (clvp/generate-remove-empty-properties variant-id))]
        (when (seq (:redo-changes changes))
          (rx/of
           (ev/event {::ev/name "variant-remove-property" ::ev/origin "workspace:rename-in-layers"})
           (dch/commit-changes changes)))))))

(defn add-new-property
  "Add a new variant property to all the components with this variant-id"
  [variant-id & [options]]
  (ptk/reify ::add-new-property
    ptk/WatchEvent
    (watch [it state _]
      (rx/of (dch/commit-changes (-> (variant-changes it state)
                                     (clvp/generate-add-new-property variant-id options)))))))

(defn reorder-variant-properties
  "Reorder properties by moving a property from some position to some space between positions"
  [variant-id from-pos to-space-between-pos]
  (ptk/reify ::reorder-variant-properties
    ptk/WatchEvent
    (watch [it state _]
      (rx/of (dch/commit-changes (-> (variant-changes it state)
                                     (clvp/generate-reorder-variant-properties variant-id from-pos to-space-between-pos)))))))

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
            container (get objects (:parent-id shape))]
        (when (and (some? shape) (some? container))
          (let [width (+ (:width container) (:width shape) 20) ;; 20 is the default gap for variants
                x     (- width (+ (:width shape) 30))]         ;; 30 is the default margin for variants
            (rx/of
             (dwt/update-dimensions [(:parent-id shape)] :width width)
             (dwt/update-position shape-id
                                  {:x x}
                                  {:absolute? false}))))))))

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
                                   shape)]
         (when (some? shape)
           (let [component-id        (:component-id shape)
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
                   (rx/delay 250))))))))))

(defn transform-in-variant
  "Given the id of a main shape of a component, creates a variant structure for
   that component, with a second member (`clv/generate-transform-in-variant`)."
  [main-instance-id]
  (ptk/reify ::transform-in-variant
    ptk/WatchEvent
    (watch [it state _]
      (let [variant-id   (uuid/next)
            new-shape-id (uuid/next)
            changes      (-> (variant-changes it state)
                             (clv/generate-transform-in-variant main-instance-id variant-id
                                                                {:layout?          true
                                                                 :new-component-id (uuid/next)
                                                                 :new-shape-id     new-shape-id}))]
        (rx/concat
         (rx/of (dch/commit-changes changes)
                (ptk/data-event :layout/update {:ids [variant-id]})
                (dws/select-shape new-shape-id))
         (->> (rx/of (focus-property variant-id))
              (rx/delay 250)))))))

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
            add-new-variant?      (and (seq selected-shapes) (every? ctc/is-variant? selected-shapes))
            undo-id              (js/Symbol)]
        (cond
          add-new-variant?
          (rx/concat
           (rx/of
            (ev/event {::ev/name "add-new-variant" ::ev/origin "workspace:shortcut-duplicate"})
            (dwu/start-undo-transaction undo-id)
            (add-new-variant (first selected-ids) false))
           (rx/from (map #(add-new-variant % true) (rest selected-ids)))
           (rx/of (dwu/commit-undo-transaction undo-id)))

          (seq selected-ids)
          (rx/of (dws/duplicate-selected true))

          :else
          (rx/empty))))))

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

(defn combine-as-variants
  "Combines the components identified by the main-instance ids `ids` into a
   new variant container (`clv/generate-combine-as-variants`). If `ids` is a
   sequential collection, its order determines the order of the resulting
   variant components; unordered collections are normalized to the
   layer-tree order."
  [ids {:keys [page-id trigger variant-id]}]
  (ptk/reify ::combine-as-variants
    ptk/WatchEvent
    (watch [it state stream]
      (let [current-page (:current-page-id state)

            combine
            (fn [page-id]
              (let [objects (dsh/lookup-page-objects state page-id)
                    ids     (->> (if (sequential? ids)
                                   ids
                                   (cfh/order-by-indexed-shapes objects ids))
                                 (cfh/clean-loops objects)
                                 (remove (fn [id]
                                           (let [shape (get objects id)]
                                             (or (not (ctc/main-instance? shape))
                                                 (ctc/is-variant? shape)))))
                                 (vec))]
                (when (> (count ids) 1)
                  (let [rect       (grc/join-rects (map #(:selrect (get objects %)) ids))
                        variant-id (or variant-id (uuid/next))
                        changes    (-> (variant-changes it state :page-id page-id)
                                       (clv/generate-combine-as-variants ids variant-id))
                        undo-id    (js/Symbol)]
                    (rx/concat
                     (rx/of (dwu/start-undo-transaction undo-id)
                            (dch/commit-changes changes)
                            (dws/select-shapes (d/ordered-set variant-id))
                            (ptk/data-event :layout/update {:ids (into [variant-id] (map #(:parent-id (get objects %))) ids)})
                            (dwt/update-dimensions [variant-id] :width (+ (:width rect) 60))
                            (dwt/update-dimensions [variant-id] :height (+ (:height rect) 60))
                            (ev/event (-> {::ev/name "combine-as-variants" ::ev/origin trigger :number-of-combined (count ids)}
                                          (merge (meta it)))))

                     ;; NOTE: we need to schedule a commit into a
                     ;; microtask for ensure that all the scheduled
                     ;; microtask of previous events execute before the
                     ;; commit
                     (->> (rx/of (dwu/commit-undo-transaction undo-id))
                          (rx/observe-on :async)))))))]

        (if (and page-id (not= page-id current-page))
          (dwl/go-to-page-then stream page-id #(combine page-id))
          (combine current-page))))))

(defn valid-components-for-variants?
  [state page-id ids]
  (let [ids     (distinct ids)
        objects (dsh/lookup-page-objects state page-id)
        data    (dsh/lookup-file-data state)]
    (and (= page-id (:current-page-id state))
         (> (count ids) 1)
         (every?
          (fn [id]
            (let [shape     (get objects id)
                  component (ctkl/get-component data (:component-id shape) false)]
              (and (ctc/main-instance? shape)
                   component
                   (not (ctc/is-variant? component)))))
          ids))))

(defn combine-selected-as-variants
  [options]
  (ptk/reify ::combine-selected-as-variants
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (combine-as-variants selected options))))))

(defn- variant-switch
  "Switch the shape (that must be a variant copy head) for the closest one
  with the property value passed as parameter. A switch that would nest a
  component in itself does nothing and calls the `:on-error` of the
  metadata of `params`."
  [shape {:keys [pos val] :as params}]
  (ptk/reify ::variant-switch
    ptk/WatchEvent
    (watch [it state _]
      (let [libraries    (dsh/lookup-libraries state)
            nearest-comp (clv/find-switch-target libraries shape pos val)

            {:keys [on-error]
             :or {on-error (constantly nil)}} (meta params)]
        ;; Nothing to do when the value is already val or no variant has it
        (when nearest-comp
          (if (clv/swap-nesting-loop? (dsh/lookup-page-objects state)
                                      shape
                                      (dm/get-in libraries [(:component-file shape) :data])
                                      (:id nearest-comp))
            (do
              (on-error)
              (rx/empty))
            (rx/of
             (dwl/component-swap shape (:component-file shape) (:id nearest-comp) true)
             (ev/event (-> {::ev/name "variant-switch" ::ev/origin "workspace:design-tab"}
                           (merge (meta it)))))))))))

(defn variants-switch
  "Switch each shape (that must be a variant copy head) for the closest one with the property value passed as parameter"
  [{:keys [shapes] :as params}]
  (ptk/reify ::variants-switch
    ptk/WatchEvent
    (watch [it _ _]
      (let [ids (into (d/ordered-set) d/xf:map-id shapes)
            undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (->> (rx/from shapes)
              (rx/map (fn [data]
                        (-> (variant-switch data params)
                            (with-meta (meta it))))))
         (rx/of (dwu/commit-undo-transaction undo-id)
                (dws/select-shapes ids)))))))
