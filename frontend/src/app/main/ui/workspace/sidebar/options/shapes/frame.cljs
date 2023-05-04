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
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-attrs component-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs-shape fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.frame-grid :refer [frame-grid]]
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
        [comp-ids comp-values] [[(:id shape)] (select-keys shape component-attrs)]

        is-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/is-layout-child? ids))
        is-layout-child? (mf/deref is-layout-child-ref)
        is-flex-layout-container? (ctl/flex-layout? shape)
        is-layout-child-absolute? (ctl/layout-absolute? shape)]
    [:*
     [:& measures-menu {:ids [(:id shape)]
                        :values measure-values
                        :type type
                        :shape shape}]
     [:& component-menu {:ids comp-ids
                         :values comp-values
                         :shape shape}]
     (when (or (not is-layout-child?) is-layout-child-absolute?)
       [:& constraints-menu {:ids ids
                             :values constraint-values}])
     [:& layout-container-menu {:type type :ids [(:id shape)] :values layout-container-values :multiple false}]

     (when (or is-layout-child? is-flex-layout-container?)
       [:& layout-item-menu
        {:ids ids
         :type type
         :values layout-item-values
         :is-layout-child? is-layout-child?
         :is-layout-container? is-flex-layout-container?
         :shape shape}])

     [:& layer-menu {:ids ids
                     :type type
                     :values layer-values}]
     [:& fill-menu {:ids ids
                    :type type
                    :values (select-keys shape fill-attrs-shape)}]
     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values}]
          (when (> (count objects) 2)
            [:& color-selection-menu {:type type
                                      :shapes (vals objects)
                                      :file-id file-id
                                      :shared-libs shared-libs}])
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]
     [:& frame-grid {:shape shape}]]))
