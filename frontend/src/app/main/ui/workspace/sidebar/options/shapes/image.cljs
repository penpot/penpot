;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shapes.image
  (:require
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [rumext.alpha :as mf]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        measure-values (select-keys shape measure-attrs)
        layer-values (select-keys shape layer-attrs)]
    [:*
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values}]
     [:& layer-menu {:ids ids
                     :type type
                     :values layer-values}]
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]

     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]]))
