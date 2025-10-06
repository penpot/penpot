;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-menu* component-variant-main*]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-menu* exports-attrs]]
   [app.main.ui.workspace.sidebar.options.menus.fill :as fill]
   [app.main.ui.workspace.sidebar.options.menus.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [select-measure-keys measures-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [rumext.v2 :as mf]))

(mf/defc options*
  [{:keys [shape shapes-with-children libraries file-id page-id] :as props}]
  (let [shape-id   (dm/get-prop shape :id)
        shape-type (dm/get-prop shape :type)
        ids        (mf/with-memo [shape-id] [shape-id])
        shapes     (mf/with-memo [shape] [shape])

        applied-tokens
        (get shape :applied-tokens)

        stroke-values
        (select-keys shape stroke-attrs)

        layer-values
        (select-keys shape layer-attrs)

        measure-values
        (select-measure-keys shape)

        constraint-values
        (select-keys shape constraint-attrs)

        layout-container-values
        (select-keys shape layout-container-flex-attrs)

        layout-item-values
        (select-keys shape layout-item-attrs)

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

        parents-by-ids-ref
        (mf/with-memo [ids]
          (refs/parents-by-ids ids))

        parents
        (mf/deref parents-by-ids-ref)

        is-layout-container?
        (ctl/any-layout? shape)

        is-flex-layout?
        (ctl/flex-layout? shape)

        is-grid-layout?
        (ctl/grid-layout? shape)

        is-layout-child-absolute?
        (ctl/item-absolute? shape)

        is-variant?
        (ctk/is-variant-container? shape)]

    [:*
     [:> layer-menu* {:ids ids
                      :type shape-type
                      :values layer-values}]
     [:> measures-menu* {:ids ids
                         :applied-tokens applied-tokens
                         :values measure-values
                         :type shape-type
                         :shapes shapes}]

     [:> component-menu* {:shapes shapes}]

     (when is-variant?
       [:> component-variant-main* {:shapes shapes}])

     [:& layout-container-menu
      {:type shape-type
       :ids ids
       :values layout-container-values
       :multiple false}]

     (when (and (= (count ids) 1) is-layout-child? is-grid-parent?)
       [:& grid-cell/options
        {:shape (first parents)
         :cell (ctl/get-cell-by-shape-id (first parents) (first ids))}])

     (when (or is-layout-child? is-layout-container?)
       [:& layout-item-menu
        {:ids ids
         :type shape-type
         :values layout-item-values
         :is-flex-parent? is-flex-parent?
         :is-grid-parent? is-grid-parent?
         :is-flex-layout? is-flex-layout?
         :is-grid-layout? is-grid-layout?
         :is-layout-child? is-layout-child?
         :is-layout-container? is-layout-container?
         :shape shape}])

     (when (or (not ^boolean is-layout-child?) ^boolean is-layout-child-absolute?)
       [:& constraints-menu {:ids ids
                             :values constraint-values}])

     [:> fill/fill-menu*
      {:ids ids
       :type shape-type
       :values shape
       :shapes shapes
       :applied-tokens applied-tokens}]

     [:& stroke-menu {:ids ids
                      :type shape-type
                      :values stroke-values
                      :shapes shapes
                      :applied-tokens applied-tokens}]
     [:> color-selection-menu* {:type shape-type
                                :shapes shapes-with-children
                                :file-id file-id
                                :libraries libraries}]
     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]
     [:& frame-grid {:shape shape}]
     [:> exports-menu* {:type type
                        :ids ids
                        :shapes shapes
                        :values (select-keys shape exports-attrs)
                        :page-id page-id
                        :file-id file-id}]]))
