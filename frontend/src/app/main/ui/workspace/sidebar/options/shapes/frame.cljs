;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shapes.frame
  (:require
   [app.main.constants :refer [has-layout-item]]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs-shape fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [rumext.alpha :as mf]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        stroke-values (select-keys shape stroke-attrs)
        layer-values (select-keys shape layer-attrs)
        measure-values (select-keys shape measure-attrs)
        constraint-values (select-keys shape constraint-attrs)
        layout-container-values (select-keys shape layout-container-attrs)
        layout-item-values (select-keys shape layout-item-attrs)]
    [:*
     [:& measures-menu {:ids [(:id shape)]
                        :values measure-values
                        :type type
                        :shape shape}]
     [:& constraints-menu {:ids ids
                           :values constraint-values}]
     [:& layout-container-menu {:type type :ids [(:id shape)] :values layout-container-values}]
     
     (when has-layout-item
       [:& layout-item-menu {:ids ids
                             :type type
                             :values layout-item-values
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
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]
     [:& frame-grid {:shape shape}]]))
