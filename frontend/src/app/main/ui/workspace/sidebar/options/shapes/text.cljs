;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.text
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.main.data.workspace.texts :as dwt]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-menu* exports-attrs]]
   [app.main.ui.workspace.sidebar.options.menus.fill :as fill]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.text :refer [text-menu]]
   [rumext.v2 :as mf]))

(mf/defc options*
  [{:keys [shape libraries file-id page-id]}]
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
        (mf/deref parents-by-ids-ref)

        state-map
        (if (features/active-feature? @st/state "text-editor/v2")
          (mf/deref refs/workspace-v2-editor-state)
          (mf/deref refs/workspace-editor-state))

        editor-state
        (when (not (features/active-feature? @st/state "text-editor/v2"))
          (get state-map id))

        editor-instance
        (when (features/active-feature? @st/state "text-editor/v2")
          (mf/deref refs/workspace-editor))

        fill-values
        (dwt/current-text-values
         {:editor-state editor-state
          :editor-instance editor-instance
          :shape shape
          :attrs (conj txt/text-fill-attrs :fills)})

        text-values
        (merge
         (select-keys shape [:grow-type])
         (select-keys shape fill/fill-attrs)
         (dwt/current-root-values
          {:shape shape
           :attrs txt/root-attrs})
         (dwt/current-paragraph-values
          {:editor-state editor-state
           :editor-instance editor-instance
           :shape shape
           :attrs txt/paragraph-attrs})
         (dwt/current-text-values
          {:editor-state editor-state
           :editor-instance editor-instance
           :shape shape
           :attrs txt/text-node-attrs}))]

    [:*
     [:> layer-menu* {:ids ids
                      :type type
                      :values layer-values}]
     [:> measures-menu*
      {:ids ids
       :type type
       :values measure-values
       :applied-tokens applied-tokens
       :shapes shapes}]

     [:& layout-container-menu
      {:type type
       :ids ids
       :values layout-container-values
       :applied-tokens applied-tokens
       :multiple false}]

     (when (and (= (count ids) 1) is-layout-child? is-grid-parent?)
       [:& grid-cell/options
        {:shape (first parents)
         :cell (ctl/get-cell-by-shape-id (first parents) (first ids))}])

     (when is-layout-child?
       [:& layout-item-menu
        {:ids ids
         :type type
         :values layout-item-values
         :is-layout-child? true
         :is-flex-parent? is-flex-parent?
         :is-grid-parent? is-grid-parent?
         :shape shape}])

     (when (or (not ^boolean is-layout-child?) ^boolean is-layout-child-absolute?)
       [:& constraints-menu
        {:ids ids
         :values (select-keys shape constraint-attrs)}])

     [:& text-menu
      {:ids ids
       :type type
       :values text-values}]

     [:> fill/fill-menu*
      {:ids ids
       :type type
       :values fill-values
       :applied-tokens applied-tokens}]

     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values
                      :disable-stroke-style true
                      :applied-tokens applied-tokens}]

     (when (= :multiple (:fills fill-values))
       [:> color-selection-menu*
        {:type type
         :shapes shapes
         :file-id file-id
         :libraries libraries}])

     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]

     [:& blur-menu
      {:ids ids
       :values (select-keys shape [:blur])}]

     [:> exports-menu* {:type type
                        :ids ids
                        :shapes shapes
                        :values (select-keys shape exports-attrs)
                        :page-id page-id
                        :file-id file-id}]]))

