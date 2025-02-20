;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.bool
  (:require
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [rumext.v2 :as mf]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        measure-values (select-keys shape measure-attrs)
        stroke-values (select-keys shape stroke-attrs)
        layer-values (select-keys shape layer-attrs)
        constraint-values (select-keys shape constraint-attrs)
        layout-item-values (select-keys shape layout-item-attrs)
        layout-container-values (select-keys shape layout-container-flex-attrs)

        is-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/is-layout-child? ids))
        is-layout-child? (mf/deref is-layout-child-ref)

        is-flex-parent-ref (mf/use-memo (mf/deps ids) #(refs/flex-layout-child? ids))
        is-flex-parent? (mf/deref is-flex-parent-ref)

        is-grid-parent-ref (mf/use-memo (mf/deps ids) #(refs/grid-layout-child? ids))
        is-grid-parent? (mf/deref is-grid-parent-ref)

        is-layout-child-absolute? (ctl/item-absolute? shape)

        ids (hooks/use-equal-memo ids)
        parents-by-ids-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        parents (mf/deref parents-by-ids-ref)]
    [:*
     [:& layer-menu {:ids ids
                     :type type
                     :values layer-values}]

     [:> measures-menu* {:ids ids
                         :type type
                         :values measure-values
                         :shape shape}]

     [:& layout-container-menu
      {:type type
       :ids [(:id shape)]
       :values layout-container-values
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
       [:& constraints-menu {:ids ids
                             :values constraint-values}])

     [:& fill-menu {:ids ids
                    :type type
                    :values (select-keys shape fill-attrs)}]
     [:& stroke-menu {:ids ids
                      :type type
                      :show-caps true
                      :values stroke-values}]

     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]]))
