;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.rows.properties-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.inspect.styles.property-detail-copiable :refer [property-detail-copiable*]]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:properties-row
  [:map
   [:term :string]
   [:detail :string]
   [:property {:optional true} :string] ;; CSS valid property
   [:token {:optional true} :any] ;; resolved token object
   [:copiable {:optional true} :boolean]])

(mf/defc properties-row*
  {::mf/schema schema:properties-row}
  [{:keys [class term detail token property copiable]}]
  (let [copiable? (or copiable false)
        detail? (not (or (nil? detail) (str/blank? detail)))
        detail (if detail? detail "-")
        copied* (mf/use-state false)
        copied (deref copied*)
        copiable-value (if (some? token)
                         (:name token)
                         property)
        copy-attr
        (mf/use-fn
         (mf/deps copied)
         (fn []
           (reset! copied* true)
           (wapi/write-to-clipboard copiable-value)
           (tm/schedule 1000 #(reset! copied* false))))]
    [:dl {:class [(stl/css :property-row) class]}
     [:dt {:class (stl/css :property-term)} term]
     [:dd {:class (stl/css :property-detail)}
      (if copiable?
        (if token
          [:> tooltip* {:id (:name token)
                        :class (stl/css :tooltip-token-wrapper)
                        :content #(mf/html
                                   [:div {:class (stl/css :tooltip-token)}
                                    [:div {:class (stl/css :tooltip-token-title)} (tr "inspect.tabs.styles.token.resolved-value")]
                                    [:div {:class (stl/css :tooltip-token-value)} (:value token)]])}
           [:> property-detail-copiable* {:detail detail
                                          :token token
                                          :copied copied
                                          :on-click copy-attr}]]
          [:> property-detail-copiable* {:detail detail
                                         :copied copied
                                         :on-click copy-attr}])
        detail)]]))
