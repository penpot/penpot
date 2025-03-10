;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.variant
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.logic.variants :as clv]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc variant-block*
  [{:keys [name value]}]
  [:div {:title value
         :class (stl/css :variant-row)}
   [:div {:class (stl/css :global/attr-label)} name]
   [:div {:class (stl/css :global/attr-value)}
    [:& copy-button {:data value}
     [:div {:class (stl/css :button-children)} value]]]])


(mf/defc variant-panel
  [{:keys [objects shapes libraries fid]}]
  (let [data            (dm/get-in libraries [fid :data])
        variant-id      (->> shapes first :id)
        properties      (mf/with-memo [data objects variant-id]
                          (clv/extract-properties-values data objects variant-id))]
    [:div {:class (stl/css :attributes-block)}
     [:& inspect-title-bar
      {:title (tr "inspect.attributes.variant")
       :class (stl/css :title-spacing-variant)}]

     (for [[pos property] (map vector (range) properties)]
       (let [val (str/join ", " (:values property))]
         [:> variant-block* {:key (dm/str "variant-property-" pos) :name (:name property) :value val}]))]))
