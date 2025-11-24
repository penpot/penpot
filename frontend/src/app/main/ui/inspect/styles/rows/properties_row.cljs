;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.rows.properties-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.tokens.format :refer [category-dictionary
                                                  format-token-value]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.inspect.styles.property-detail-copiable :refer [property-detail-copiable*]]
   [app.util.clipboard :as clipboard]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
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
  (let [copiable? (d/nilv copiable false)
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
           (clipboard/to-clipboard copiable-value)
           (tm/schedule 1000 #(reset! copied* false))))]
    [:dl {:class [(stl/css :property-row) class]
          :data-testid "property-row"}
     [:dt {:class (stl/css :property-term)} term]
     [:dd {:class (stl/css :property-detail)}
      (if copiable?
        (if token
          (let [token-type (:type token)]
            [:> tooltip* {:id (:name token)
                          :class (stl/css :tooltip-token-wrapper)
                          :content #(mf/html
                                     [:div {:class (stl/css :tooltip-token)}
                                      [:div {:class (stl/css :tooltip-token-title)}
                                       (tr "inspect.tabs.styles.token-resolved-value")]
                                      [:div {:class (stl/css :tooltip-token-value)}
                                       (cond
                                         (= :typography token-type)
                                         [:ul {:class (stl/css :tooltip-token-resolved-values)}
                                          (for [[property value] (:resolved-value token)]
                                            [:li {:key property}
                                             (str (category-dictionary property) ": " (format-token-value value))])]
                                         (= :shadow token-type)
                                         [:ul {:class (stl/css :tooltip-token-resolved-values)}
                                          (for [property (:resolved-value token)
                                                [key value] property]
                                            [:li {:key key}
                                             (str (category-dictionary key) ": " (format-token-value value))])]
                                         :else
                                         (:resolved-value token))]])}
             [:> property-detail-copiable* {:token token
                                            :copied copied
                                            :on-click copy-attr} detail]])
          [:> property-detail-copiable* {:copied copied
                                         :on-click copy-attr} detail])
        detail)]]))
