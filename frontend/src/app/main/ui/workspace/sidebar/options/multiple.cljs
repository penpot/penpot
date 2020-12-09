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
   [app.common.attrs :as attrs]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-attrs shadow-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-attrs blur-menu]]
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

(defn extract [{:keys [shapes attrs text-attrs convert-attrs extract-fn]}]
  (let [mapfn
        (fn [shape]
          (get-shape-attrs shape
                           attrs
                           text-attrs
                           convert-attrs
                           extract-fn))]
    (attrs/get-attrs-multi (map mapfn shapes) (or attrs text-attrs))))

(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shapes shapes-with-children] :as props}]
  (let [ids (map :id shapes)
        ids-with-children (map :id shapes-with-children)
        text-ids (map :id (filter #(= (:type %) :text) shapes-with-children))
        other-ids (map :id (filter #(not= (:type %) :text) shapes-with-children))


        measure-values (attrs/get-attrs-multi shapes measure-attrs)

        shadow-values (let [keys [:style :color :offset-x :offset-y :blur :spread]]
                        (attrs/get-attrs-multi
                         shapes shadow-attrs
                         (fn [s1 s2]
                           (and (= (count s1) (count s2))
                                (->> (map vector s1 s2)
                                     (every? (fn [[v1 v2]]
                                               (= (select-keys v1 keys) (select-keys v2 keys)))))))
                         (fn [v]
                           (mapv #(select-keys % keys) v))))

        blur-values (let [keys [:type :value]]
                      (attrs/get-attrs-multi
                       shapes blur-attrs
                       (fn [v1 v2]
                         (= (select-keys v1 keys) (select-keys v2 keys)))
                       (fn [v] (select-keys v keys))))

        fill-values (extract {:shapes shapes-with-children
                              :attrs fill-attrs
                              :text-attrs ot/text-fill-attrs
                              :convert-attrs fill-attrs
                              :extract-fn dwt/current-text-values})

        stroke-values (extract {:shapes shapes-with-children
                                :attrs stroke-attrs})

        root-values (extract {:shapes shapes-with-children
                              :text-attrs ot/root-attrs
                              :extract-fn dwt/current-root-values})

        paragraph-values (extract {:shapes shapes-with-children
                                   :text-attrs ot/paragraph-attrs
                                   :extract-fn dwt/current-paragraph-values})

        text-values (extract {:shapes shapes-with-children
                              :text-attrs ot/text-attrs
                              :extract-fn dwt/current-text-values})]
    [:*
     [:& measures-menu {:ids ids
                        :type :multiple
                        :values measure-values}]
     [:& fill-menu {:ids ids-with-children
                    :type :multiple
                    :values fill-values}]

     [:& shadow-menu {:ids ids
                      :type :multiple
                      :values shadow-values}]

     [:& blur-menu {:ids ids
                    :type :multiple
                    :values blur-values}]

     (when-not (empty? other-ids)
       [:& stroke-menu {:ids other-ids
                        :type :multiple
                        :values stroke-values}])
     (when-not (empty? text-ids)
       [:& ot/text-menu {:ids text-ids
                         :type :multiple
                         :editor nil
                         :shapes shapes-with-children
                         :values (merge root-values
                                        paragraph-values
                                        text-values)}])]))

