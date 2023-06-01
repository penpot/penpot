;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.rect
  (:require
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [select-measure-keys measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu]]
   [rumext.v2 :as mf]))

(mf/defc options
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [shape]}]
  (let [shape-id                  (:id shape)
        ids                       [shape-id]
        type                      (:type shape)

        measure-values            (select-measure-keys shape)
        layer-values              (select-keys shape layer-attrs)
        constraint-values         (select-keys shape constraint-attrs)
        fill-values               (select-keys shape fill-attrs)
        stroke-values             (select-keys shape stroke-attrs)
        layout-item-values        (select-keys shape layout-item-attrs)
        layout-container-values   (select-keys shape layout-container-flex-attrs)

        is-flex-layout-child*     (mf/with-memo [shape-id]
                                    (refs/is-flex-layout-child? ids))
        is-flex-layout-child?     (mf/deref is-flex-layout-child*)
        is-layout-child-absolute? (ctl/layout-absolute? shape)]

    [:*
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values
                        :shape shape}]
     [:& layout-container-menu
      {:type type
       :ids ids
       :values layout-container-values
       :multiple false}]

     (when ^boolean is-flex-layout-child?
       [:& layout-item-menu
        {:ids ids
         :type type
         :values layout-item-values
         :is-layout-child? true
         :shape shape}])

     (when (or (not ^boolean is-flex-layout-child?)
               ^boolean is-layout-child-absolute?)
       [:& constraints-menu
        {:ids ids
         :values constraint-values}])

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
