;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shapes.rect
  (:require
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu]]
   [rumext.alpha :as mf]))

(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        measure-values (select-keys shape measure-attrs)
        layer-values (select-keys shape layer-attrs)
        constraint-values (select-keys shape constraint-attrs)
        fill-values (select-keys shape fill-attrs)
        stroke-values (select-keys shape stroke-attrs)]
    [:*
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values}]

     [:& constraints-menu {:ids ids
                           :values constraint-values}]

     [:& layer-menu {:ids ids
                     :type type
                     :values layer-values}]

     [:& fill-menu {:ids ids
                    :type type
                    :values fill-values}]

     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values}]

     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]

     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]

     [:& svg-attrs-menu {:ids ids
                         :values (select-keys shape [:svg-attrs])}]]))
