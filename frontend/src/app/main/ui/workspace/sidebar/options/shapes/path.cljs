;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.shapes.path
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.path :as cpath]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-menu* exports-attrs]]
   [app.main.ui.workspace.sidebar.options.menus.fill :as fill]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu* node-position-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu*]]
   [rumext.v2 :as mf]))

(mf/defc options*
  [{:keys [shape file-id page-id]}]
  (let [id     (dm/get-prop shape :id)
        type   (dm/get-prop shape :type)
        ids    (mf/with-memo [id] [id])
        shapes (mf/with-memo [shape] [shape])

        applied-tokens
        (get shape :applied-tokens)

        measure-values
        (select-keys shape measure-attrs)

        stroke-values
        (select-keys shape stroke-attrs)

        layer-values
        (select-keys shape layer-attrs)

        constraint-values
        (select-keys shape constraint-attrs)

        layout-item-values
        (select-keys shape layout-item-attrs)

        layout-container-values
        (select-keys shape layout-container-flex-attrs)

        is-layout-child-ref
        (mf/with-memo [ids]
          (refs/is-layout-child? ids))

        is-layout-child?
        (mf/deref is-layout-child-ref)

        is-flex-parent-ref
        (mf/with-memo [ids]
          (refs/flex-layout-child? ids))

        is-flex-parent?
        (mf/deref is-flex-parent-ref)

        is-grid-parent-ref
        (mf/with-memo [ids]
          (refs/grid-layout-child? ids))

        is-grid-parent?
        (mf/deref is-grid-parent-ref)

        is-layout-child-absolute?
        (ctl/item-absolute? shape)

        parents-by-ids-ref
        (mf/with-memo [ids]
          (refs/parents-by-ids ids))

        parents
        (mf/deref parents-by-ids-ref)]

    [:*
     [:> layer-menu* {:ids ids
                      :applied-tokens applied-tokens
                      :type type
                      :values layer-values}]
     [:> measures-menu* {:ids ids
                         :type type
                         :applied-tokens applied-tokens
                         :values measure-values
                         :shapes shapes}]

     [:> layout-container-menu*
      {:type type
       :ids [(:id shape)]
       :values layout-container-values
       :applied-tokens applied-tokens
       :multiple false}]

     (when (and (= (count ids) 1) is-layout-child? is-grid-parent?)
       [:> grid-cell/options*
        {:shape-id (-> (first parents)
                       :id)
         :cell (ctl/get-cell-by-shape-id (first parents) (first ids))}])

     (when is-layout-child?
       [:> layout-item-menu* {:ids ids
                              :type type
                              :values layout-item-values
                              :is-layout-child true
                              :is-layout-container false
                              :is-flex-parent is-flex-parent?
                              :is-grid-parent is-grid-parent?
                              :applied-tokens applied-tokens
                              :shape shape}])

     (when (or (not ^boolean is-layout-child?) ^boolean is-layout-child-absolute?)
       [:> constraints-menu* {:ids ids
                              :values constraint-values}])

     [:> fill/fill-menu*
      {:ids ids
       :type type
       :values shape
       :applied-tokens applied-tokens}]

     [:> stroke-menu* {:ids ids
                       :type type
                       :show-caps true
                       :values stroke-values
                       :applied-tokens applied-tokens}]
     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]
     [:> blur-menu* {:ids ids
                     :values (select-keys shape [:blur :background-blur])}]
     [:> svg-attrs-menu* {:ids ids
                          :values (select-keys shape [:svg-attrs])}]
     [:> exports-menu* {:type type
                        :ids ids
                        :shapes shapes
                        :values (select-keys shape exports-attrs)
                        :page-id page-id
                        :file-id file-id}]]))

(mf/defc path-edition-options*
  "Options shown while editing a path."
  [{:keys [shape]}]
  (let [id     (dm/get-prop shape :id)
        type   (dm/get-prop shape :type)
        ids    (mf/with-memo [id] [id])
        shapes (mf/with-memo [shape] [shape])

        applied-tokens
        (get shape :applied-tokens)

        measure-values
        (select-keys shape measure-attrs)

        stroke-values
        (select-keys shape stroke-attrs)

        ;; Read coordinates from the live editing content.
        edit-path (mf/deref refs/workspace-edit-path)
        drawing   (mf/deref refs/current-drawing-shape)
        objects   (mf/deref refs/workspace-page-objects)
        selection (get-in edit-path [id :selection])
        modifiers (get-in edit-path [id :content-modifiers])

        content
        (mf/with-memo [drawing modifiers]
          (when-let [base (get drawing :content)]
            (cpath/apply-content-modifiers base modifiers)))

        ;; Show coordinates relative to the parent frame.
        frame    (cfh/get-parent-frame objects shape)
        in-frame? (and (some? frame) (not (cfh/root? frame)))
        ox       (if in-frame? (dm/get-prop frame :x) 0)
        oy       (if in-frame? (dm/get-prop frame :y) 0)

        ;; Segments use selection bounds; nodes and handlers use their positions.
        node-values
        (mf/with-memo [content selection ox oy]
          (when (and (some? content) (some? selection))
            (let [segments (get selection :segments)
                  handlers (get selection :handlers)
                  nodes    (get selection :nodes)]
              (cond
                (seq segments)
                (when-let [rect (path.helpers/selection-coordinate-rect
                                 content selection)]
                  {:x (- (dm/get-prop rect :x) ox)
                   :y (- (dm/get-prop rect :y) oy)})

                (or (seq nodes) (seq handlers))
                (let [positions (into (path.helpers/node-positions content (set nodes))
                                      (keep (fn [[i p]] (cpath/get-handler-point content i p)))
                                      handlers)]
                  (when (seq positions)
                    (let [xs (into #{} (map #(- (:x %) ox)) positions)
                          ys (into #{} (map #(- (:y %) oy)) positions)]
                      {:x (if (= 1 (count xs)) (first xs) :multiple)
                       :y (if (= 1 (count ys)) (first ys) :multiple)})))))))

        on-node-x-change
        (mf/use-fn (mf/deps ox)
                   (fn [value] (when (some? value) (st/emit! (drp/set-selection-coordinate :x (+ value ox))))))

        on-node-y-change
        (mf/use-fn (mf/deps oy)
                   (fn [value] (when (some? value) (st/emit! (drp/set-selection-coordinate :y (+ value oy))))))]

    [:*
     (when (some? node-values)
       [:> node-position-menu* {:values node-values
                                :on-x-change on-node-x-change
                                :on-y-change on-node-y-change}])
     ;; Show read-only shape measures when no path element is selected.
     (when (nil? node-values)
       [:div {:style {:pointer-events "none" :opacity 0.6}}
        [:> measures-menu* {:ids ids
                            :type type
                            :applied-tokens applied-tokens
                            :values measure-values
                            :shapes shapes}]])
     [:> fill/fill-menu*
      {:ids ids
       :type type
       :values shape
       :applied-tokens applied-tokens}]
     [:> stroke-menu* {:ids ids
                       :type type
                       :show-caps true
                       :values stroke-values
                       :applied-tokens applied-tokens}]
     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]
     [:> blur-menu* {:ids ids
                     :values (select-keys shape [:blur :background-blur])}]]))
