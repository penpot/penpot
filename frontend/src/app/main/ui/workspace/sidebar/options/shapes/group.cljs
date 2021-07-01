;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shapes.group
  (:require
   [app.common.data :as d]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-attrs component-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu]]
   [app.main.ui.workspace.sidebar.options.menus.text :as ot]
   [app.main.ui.workspace.sidebar.options.shapes.multiple :refer [get-attrs]]
   [rumext.alpha :as mf]))

(mf/defc options
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        shape-with-children (unchecked-get props "shape-with-children")
        objects (->> shape-with-children (group-by :id) (d/mapm (fn [_ v] (first v))))

        type :group
        [measure-ids    measure-values]    (get-attrs [shape] objects :measure)
        [layer-ids      layer-values]      (get-attrs [shape] objects :layer)
        [constraint-ids constraint-values] (get-attrs [shape] objects :constraint)
        [fill-ids       fill-values]       (get-attrs [shape] objects :fill)
        [shadow-ids     shadow-values]     (get-attrs [shape] objects :shadow)
        [blur-ids       blur-values]       (get-attrs [shape] objects :blur)
        [stroke-ids     stroke-values]     (get-attrs [shape] objects :stroke)
        [text-ids       text-values]       (get-attrs [shape] objects :text)
        [svg-ids        svg-values]        [[(:id shape)] (select-keys shape [:svg-attrs])]
        [comp-ids       comp-values]       [[(:id shape)] (select-keys shape component-attrs)]]

    [:div.options
     [:& measures-menu {:type type :ids measure-ids :values measure-values}]
     [:& component-menu {:ids comp-ids :values comp-values}]
     [:& constraints-menu {:ids constraint-ids :values constraint-values}]
     [:& layer-menu {:type type :ids layer-ids :values layer-values}]

     (when-not (empty? fill-ids)
       [:& fill-menu {:type type :ids fill-ids :values fill-values}])

     (when-not (empty? shadow-ids)
       [:& shadow-menu {:type type :ids shadow-ids :values shadow-values}])

     (when-not (empty? blur-ids)
       [:& blur-menu {:type type :ids blur-ids :values blur-values}])

     (when-not (empty? stroke-ids)
       [:& stroke-menu {:type type :ids stroke-ids :values stroke-values}])

     (when-not (empty? text-ids)
       [:& ot/text-menu {:type type :ids text-ids :values text-values}])

     (when-not (empty? svg-values)
       [:& svg-attrs-menu {:ids svg-ids
                           :values svg-values}])]))


