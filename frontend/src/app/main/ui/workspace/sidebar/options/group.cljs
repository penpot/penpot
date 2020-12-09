;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns app.main.ui.workspace.sidebar.options.group
  (:require
   [rumext.alpha :as mf]
   [app.common.attrs :as attrs]
   [app.common.geom.shapes :as geom]
   [app.main.refs :as refs]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.workspace.sidebar.options.multiple :refer [get-shape-attrs extract]]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.component :refer [component-attrs component-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.text :as ot]))

(mf/defc options
  [{:keys [shape shape-with-children] :as props}]
  (let [id (:id shape)
        ids-with-children (map :id shape-with-children)
        text-ids (map :id (filter #(= (:type %) :text) shape-with-children))
        other-ids (map :id (filter #(not= (:type %) :text) shape-with-children))

        type (:type shape) ; always be :group

        measure-values
        (merge
          ;; All values extracted from the group shape, except
          ;; border radius, that needs to be looked up from children
          (attrs/get-attrs-multi (map #(get-shape-attrs
                                        %
                                        measure-attrs
                                        nil
                                        nil
                                        nil)
                                     [shape])
                                measure-attrs)
          (attrs/get-attrs-multi (map #(get-shape-attrs
                                        %
                                        [:rx :ry]
                                        nil
                                        nil
                                        nil)
                                     shape-with-children)
                                [:rx :ry]))

        component-values
        (select-keys shape component-attrs)

        fill-values
        (attrs/get-attrs-multi shape-with-children fill-attrs)

        stroke-values
        (attrs/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      stroke-attrs
                                      nil
                                      nil
                                      nil)
                                   shape-with-children)
                              stroke-attrs)

        root-values (extract {:shapes shape-with-children
                              :text-attrs ot/root-attrs
                              :extract-fn dwt/current-root-values})

        paragraph-values (extract {:shapes shape-with-children
                                   :text-attrs ot/paragraph-attrs
                                   :extract-fn dwt/current-paragraph-values})

        text-values (extract {:shapes shape-with-children
                              :text-attrs ot/text-attrs
                              :extract-fn dwt/current-text-values})]
    [:*
     [:& measures-menu {:ids [id]
                        :ids-with-children ids-with-children
                        :type type
                        :values measure-values}]
     [:& component-menu {:ids [id]
                         :values component-values}]
     [:& fill-menu {:ids ids-with-children
                    :type type
                    :values fill-values}]

     [:& shadow-menu {:ids [id]
                      :type type
                      :values (select-keys shape [:shadow])}]

     [:& blur-menu {:ids [id]
                    :type type
                    :values (select-keys shape [:blur])}]

     (when-not (empty? other-ids)
       [:& stroke-menu {:ids other-ids
                        :type type
                        :values stroke-values}])
     (when-not (empty? text-ids)
       [:& ot/text-menu {:ids text-ids
                      :type type
                      :editor nil
                      :shapes shape-with-children
                      :values (merge root-values
                                     paragraph-values
                                     text-values)}])]))

