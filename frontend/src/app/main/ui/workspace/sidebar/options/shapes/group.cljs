;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.group
  (:require
   [app.common.data :as d]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-attrs component-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu]]
   [app.main.ui.workspace.sidebar.options.menus.text :as ot]
   [app.main.ui.workspace.sidebar.options.shapes.multiple :refer [get-attrs]]
   [rumext.v2 :as mf]))

(mf/defc options
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [shape                    (unchecked-get props "shape")
        shape-with-children      (unchecked-get props "shape-with-children")
        shared-libs              (unchecked-get props "shared-libs")
        objects                  (->> shape-with-children (group-by :id) (d/mapm (fn [_ v] (first v))))
        file-id                  (unchecked-get props "file-id")
        layout-container-values  (select-keys shape layout-container-flex-attrs)
        ids                      [(:id shape)]
        is-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/is-layout-child? ids))
        is-layout-child?    (mf/deref is-layout-child-ref)

        is-flex-parent-ref (mf/use-memo (mf/deps ids) #(refs/flex-layout-child? ids))
        is-flex-parent? (mf/deref is-flex-parent-ref)

        is-grid-parent-ref (mf/use-memo (mf/deps ids) #(refs/grid-layout-child? ids))
        is-grid-parent? (mf/deref is-grid-parent-ref)

        is-layout-child-absolute? (ctl/layout-absolute? shape)

        ids (hooks/use-equal-memo ids)
        parents-by-ids-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        parents (mf/deref parents-by-ids-ref)

        type :group
        [measure-ids    measure-values]      (get-attrs [shape] objects :measure)
        [layer-ids      layer-values]        (get-attrs [shape] objects :layer)
        [constraint-ids constraint-values]   (get-attrs [shape] objects :constraint)
        [fill-ids       fill-values]         (get-attrs [shape] objects :fill)
        [shadow-ids     shadow-values]       (get-attrs [shape] objects :shadow)
        [blur-ids       blur-values]         (get-attrs [shape] objects :blur)
        [stroke-ids     stroke-values]       (get-attrs [shape] objects :stroke)
        [text-ids       text-values]         (get-attrs [shape] objects :text)
        [svg-ids        svg-values]          [[(:id shape)] (select-keys shape [:svg-attrs])]
        [comp-ids       comp-values]         [[(:id shape)] (select-keys shape component-attrs)]
        [layout-item-ids layout-item-values] (get-attrs [shape] objects :layout-item)]

    [:div.options
     [:& measures-menu {:type type :ids measure-ids :values measure-values :shape shape}]
     [:& component-menu {:ids comp-ids :values comp-values :shape shape}] ;;remove this in components-v2
     [:& layout-container-menu {:type type :ids [(:id shape)] :values layout-container-values :multiple false}]

     (when (and (= (count ids) 1) is-layout-child? is-grid-parent?)
       [:& grid-cell/options
        {:shape (first parents)
         :cell (ctl/get-cell-by-shape-id (first parents) (first ids))}])

     (when is-layout-child?
       [:& layout-item-menu
        {:type type
         :ids layout-item-ids
         :is-layout-child? true
         :is-layout-container? false
         :is-flex-parent? is-flex-parent?
         :is-grid-parent? is-grid-parent?
         :values layout-item-values}])

     (when (or (not is-layout-child?) is-layout-child-absolute?)
       [:& constraints-menu {:ids constraint-ids :values constraint-values}])

     [:& layer-menu {:type type :ids layer-ids :values layer-values}]

     (when-not (empty? fill-ids)
       [:& fill-menu {:type type :ids fill-ids :values fill-values}])

     (when-not (empty? stroke-ids)
       [:& stroke-menu {:type type :ids stroke-ids :values stroke-values}])

     (when (> (count objects) 2)
       [:& color-selection-menu {:type type
                                 :shapes (vals objects)
                                 :file-id file-id
                                 :shared-libs shared-libs}])

     (when-not (empty? shadow-ids)
       [:& shadow-menu {:type type :ids shadow-ids :values shadow-values}])

     (when-not (empty? blur-ids)
       [:& blur-menu {:type type :ids blur-ids :values blur-values}])

     (when-not (empty? text-ids)
       [:& ot/text-menu {:type type :ids text-ids :values text-values}])

     (when-not (empty? svg-values)
       [:& svg-attrs-menu {:ids svg-ids
                           :values svg-values}])]))


