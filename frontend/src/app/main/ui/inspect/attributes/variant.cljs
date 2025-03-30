;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.variant
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc variant-block*
  [{:keys [name value]}]
  [:div {:title value
         :class (stl/css :variant-row)}
   [:div {:class (stl/css :global/attr-label)} name]
   [:div {:class (stl/css :global/attr-value)}
    [:> copy-button* {:data value}
     [:div {:class (stl/css :button-children)} value]]]])


(mf/defc variant-panel*
  [{:keys [objects shapes libraries file-id] :as kk}]
  (let [shape           (->> shapes first)
        is-container?   (ctc/is-variant-container? shape)
        properties      (mf/with-memo [objects shape]
                          (let [data          (dm/get-in libraries [file-id :data])
                                component     (when-not is-container? (ctkl/get-component data (:component-id shape)))]
                            (if is-container?
                              (->> (cfv/extract-properties-values data objects (:id shape))
                                   (map #(update % :value (partial str/join ", "))))
                              (->> (:variant-properties component)
                                   (map #(update % :value (fn [v] (if (str/blank? v) "--" v))))))))]
    [:div {:class (stl/css :attributes-block)}
     [:> inspect-title-bar*
      {:title (if is-container? (tr "inspect.attributes.variants") (tr "inspect.attributes.variant"))
       :class (stl/css :title-spacing-variant)}]

     (for [[pos property] (map-indexed vector properties)]
       [:> variant-block* {:key (dm/str "variant-property-" pos) :name (:name property) :value (:value property)}])]))
