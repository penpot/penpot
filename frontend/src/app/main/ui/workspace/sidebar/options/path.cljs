;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.path
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.svg-attrs :refer [svg-attrs-menu]]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        measure-values (select-keys shape measure-attrs)
        stroke-values (select-keys shape stroke-attrs)]
    [:*
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values}]
     [:& fill-menu {:ids ids
                    :type type
                    :values (select-keys shape fill-attrs)}]
     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values}]
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]

     [:& svg-attrs-menu {:ids ids
                         :values (select-keys shape [:svg-attrs])}]]))
