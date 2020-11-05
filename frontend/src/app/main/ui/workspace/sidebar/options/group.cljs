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
   [app.common.geom.shapes :as geom]
   [app.common.pages-helpers :as cph]
   [app.main.refs :as refs]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.workspace.sidebar.options.multiple :refer [get-shape-attrs]]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.text :refer [text-fill-attrs
                                                         text-font-attrs
                                                         text-align-attrs
                                                         text-spacing-attrs
                                                         text-valign-attrs
                                                         text-decoration-attrs
                                                         text-transform-attrs
                                                         text-menu]]))

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
          (geom/get-attrs-multi (map #(get-shape-attrs
                                        %
                                        measure-attrs
                                        nil
                                        nil
                                        nil)
                                     [shape])
                                measure-attrs)
          (geom/get-attrs-multi (map #(get-shape-attrs
                                        %
                                        [:rx :ry]
                                        nil
                                        nil
                                        nil)
                                     shape-with-children)
                                [:rx :ry]))

        fill-values
        (geom/get-attrs-multi shape-with-children fill-attrs)

        stroke-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      stroke-attrs
                                      nil
                                      nil
                                      nil)
                                   shape-with-children)
                              stroke-attrs)

        font-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-font-attrs
                                      nil
                                      dwt/current-text-values)
                                   shape-with-children)
                              text-font-attrs)

        align-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-align-attrs
                                      nil
                                      dwt/current-paragraph-values)
                                   shape-with-children)
                              text-align-attrs)

        spacing-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-spacing-attrs
                                      nil
                                      dwt/current-text-values)
                                   shape-with-children)
                              text-spacing-attrs)

        valign-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-valign-attrs
                                      nil
                                      dwt/current-root-values)
                                   shape-with-children)
                              text-valign-attrs)

        decoration-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-decoration-attrs
                                      nil
                                      dwt/current-text-values)
                                   shape-with-children)
                              text-decoration-attrs)

        transform-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-transform-attrs
                                      nil
                                      dwt/current-text-values)
                                   shape-with-children)
                              text-transform-attrs)]
    [:*
     [:& measures-menu {:ids [id]
                        :ids-with-children ids-with-children
                        :type type
                        :values measure-values}]
     [:& fill-menu {:ids ids-with-children
                    :type type
                    :values fill-values}]

     [:& blur-menu {:ids [id]
                    :values (select-keys shape [:blur])}]
     
     (when-not (empty? other-ids)
       [:& stroke-menu {:ids other-ids
                        :type type
                        :values stroke-values}])
     (when-not (empty? text-ids)
       [:& text-menu {:ids text-ids
                      :type type
                      :editor nil
                      :font-values font-values
                      :align-values align-values
                      :spacing-values spacing-values
                      :valign-values valign-values
                      :decoration-values decoration-values
                      :transform-values transform-values}])]))

