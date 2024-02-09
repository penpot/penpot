;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs-shape fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [select-measure-keys measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [rumext.v2 :as mf]))

(mf/defc options
  [{:keys [shape file-id shape-with-children shared-libs] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        objects                 (->> shape-with-children (group-by :id) (d/mapm (fn [_ v] (first v))))
        stroke-values (select-keys shape stroke-attrs)
        layer-values (select-keys shape layer-attrs)
        measure-values (select-measure-keys shape)
        constraint-values (select-keys shape constraint-attrs)
        layout-container-values (select-keys shape layout-container-flex-attrs)
        layout-item-values (select-keys shape layout-item-attrs)

        ids (hooks/use-equal-memo ids)

        is-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/is-layout-child? ids))
        is-layout-child? (mf/deref is-layout-child-ref)

        is-flex-parent-ref (mf/use-memo (mf/deps ids) #(refs/flex-layout-child? ids))
        is-flex-parent? (mf/deref is-flex-parent-ref)

        is-grid-parent-ref (mf/use-memo (mf/deps ids) #(refs/grid-layout-child? ids))
        is-grid-parent? (mf/deref is-grid-parent-ref)

        is-layout-container? (ctl/any-layout? shape)
        is-flex-layout? (ctl/flex-layout? shape)
        is-grid-layout? (ctl/grid-layout? shape)
        is-layout-child-absolute? (ctl/item-absolute? shape)

        ids (hooks/use-equal-memo ids)
        parents-by-ids-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        parents (mf/deref parents-by-ids-ref)]
    [:*
     [:& layer-menu {:ids ids
                     :type type
                     :values layer-values}]
     [:& measures-menu {:ids [(:id shape)]
                        :values measure-values
                        :type type
                        :shape shape}]

     [:& component-menu {:shapes [shape]}]

     [:& layout-container-menu
      {:type type
       :ids [(:id shape)]
       :values layout-container-values
       :multiple false}]

     (when (and (= (count ids) 1) is-layout-child? is-grid-parent?)
       [:& grid-cell/options
        {:shape (first parents)
         :cell (ctl/get-cell-by-shape-id (first parents) (first ids))}])

     (when (or is-layout-child? is-layout-container?)
       [:& layout-item-menu
        {:ids ids
         :type type
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

     [:& fill-menu {:ids ids
                    :type type
                    :values (select-keys shape fill-attrs-shape)}]
     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values}]
     [:& color-selection-menu {:type type
                               :shapes (vals objects)
                               :file-id file-id
                               :shared-libs shared-libs}]
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]
     [:& frame-grid {:shape shape}]]))
