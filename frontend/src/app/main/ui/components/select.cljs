;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.select
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(mf/defc select [{:keys [default-value options class on-change]}]
  (let [state (mf/use-state {:id (uuid/next)
                             :is-open? false
                             :current-value default-value})
        open-dropdown #(swap! state assoc :is-open? true)
        close-dropdown #(swap! state assoc :is-open? false)
        select-item (fn [value]
                      (fn [_]
                        (swap! state assoc :current-value value)
                        (when on-change (on-change value))))
        as-key-value (fn [item] (if (map? item) [(:value item) (:label item)] [item item]))
        value->label (into {} (->> options
                                   (map as-key-value))) ]

    (mf/use-effect
     (mf/deps options)
     #(reset! state {:is-open? false
                     :current-value default-value}))

    [:div.custom-select {:on-click open-dropdown
                         :class class}
     [:span (-> @state :current-value value->label)]
     [:span.dropdown-button i/arrow-down]
     [:& dropdown {:show (:is-open? @state)
                   :on-close close-dropdown}
      [:ul.custom-select-dropdown
       (for [[index item] (map-indexed vector options)]
         (cond
           (= :separator item) [:hr {:key (dm/str (:id @state) "-" index)}]
           :else (let [[value label] (as-key-value item)]
                   [:li.checked-element
                    {:key (dm/str (:id @state) "-" index)
                     :class (when (= value (-> @state :current-value)) "is-selected")
                     :on-click (select-item value)}
                    [:span.check-icon i/tick]
                    [:span label]])))]]]))
