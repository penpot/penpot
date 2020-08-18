;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.multiple
  (:require
   [rumext.alpha :as mf]
   [app.common.geom.shapes :as geom]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.text :refer [text-fill-attrs
                                                         text-font-attrs
                                                         text-align-attrs
                                                         text-spacing-attrs
                                                         text-valign-attrs
                                                         text-decoration-attrs
                                                         text-transform-attrs
                                                         text-menu]]))

(defn get-shape-attrs
  [shape attrs text-attrs convert-attrs extract-fn]
  (if (not= (:type shape) :text)
    (when attrs
      (select-keys shape attrs))
    (when text-attrs
      (let [text-values (extract-fn {:editor nil
                                     :shape shape
                                     :attrs text-attrs})

            converted-values (if convert-attrs
                               (zipmap convert-attrs
                                       (map #(% text-values) text-attrs))
                               text-values)]
        converted-values))))

(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shapes] :as props}]
  (let [ids (map :id shapes)
        text-ids (map :id (filter #(= (:type %) :text) shapes))
        other-ids (map :id (filter #(not= (:type %) :text) shapes))

        measure-values
        (geom/get-attrs-multi shapes measure-attrs)

        fill-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      fill-attrs
                                      text-fill-attrs
                                      fill-attrs
                                      dwt/current-text-values)
                                   shapes)
                              fill-attrs)

        stroke-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      stroke-attrs
                                      nil
                                      nil
                                      nil)
                                   shapes)
                              stroke-attrs)

        font-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-font-attrs
                                      nil
                                      dwt/current-text-values)
                                   shapes)
                              text-font-attrs)

        align-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-align-attrs
                                      nil
                                      dwt/current-paragraph-values)
                                   shapes)
                              text-align-attrs)

        spacing-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-spacing-attrs
                                      nil
                                      dwt/current-text-values)
                                   shapes)
                              text-spacing-attrs)

        valign-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-valign-attrs
                                      nil
                                      dwt/current-root-values)
                                   shapes)
                              text-valign-attrs)

        decoration-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-decoration-attrs
                                      nil
                                      dwt/current-text-values)
                                   shapes)
                              text-decoration-attrs)

        transform-values
        (geom/get-attrs-multi (map #(get-shape-attrs
                                      %
                                      nil
                                      text-transform-attrs
                                      nil
                                      dwt/current-text-values)
                                   shapes)
                              text-transform-attrs)]
    [:*
     [:& measures-menu {:ids ids
                        :type :multiple
                        :values measure-values}]
     [:& fill-menu {:ids ids
                    :type :multiple
                    :values fill-values}]
     (when-not (empty? other-ids)
       [:& stroke-menu {:ids other-ids
                        :type :multiple
                        :values stroke-values}])
     (when-not (empty? text-ids)
       [:& text-menu {:ids text-ids
                      :type :multiple
                      :editor nil
                      :font-values font-values
                      :align-values align-values
                      :spacing-values spacing-values
                      :valign-values valign-values
                      :decoration-values decoration-values
                      :transform-values transform-values}])]))

