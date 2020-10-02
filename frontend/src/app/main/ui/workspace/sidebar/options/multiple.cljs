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
   [app.main.ui.workspace.sidebar.options.text :as ot]))

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

        extract (fn [{:keys [attrs text-attrs convert-attrs extract-fn]}]
                  (let [mapfn
                        (fn [shape]
                          (get-shape-attrs shape
                                           attrs
                                           text-attrs
                                           convert-attrs
                                           extract-fn))]
                    (geom/get-attrs-multi (map mapfn shapes) (or attrs text-attrs))))

        measure-values (geom/get-attrs-multi shapes measure-attrs)

        fill-values (extract {:attrs fill-attrs
                              :text-attrs ot/text-fill-attrs
                              :convert-attrs fill-attrs
                              :extract-fn dwt/current-text-values})

        stroke-values (extract {:attrs stroke-attrs})

        root-values (extract {:text-attrs ot/root-attrs
                              :extract-fn dwt/current-root-values})

        paragraph-values (extract {:text-attrs ot/paragraph-attrs
                                   :extract-fn dwt/current-paragraph-values})

        text-values (extract {:text-attrs ot/text-attrs
                              :extract-fn dwt/current-text-values})]
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
       [:& ot/text-menu {:ids text-ids
                         :type :multiple
                         :editor nil
                         :values (merge root-values
                                        paragraph-values
                                        text-values)
                         :shapes shapes}])]))

