;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.components.editable-select
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.data :as d]
   [uxbox.util.dom :as dom]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]))

(mf/defc editable-select [{:keys [value type options class on-change]}]
  (let [state (mf/use-state {:id (uuid/next)
                             :is-open? false
                             :current-value value})
        open-dropdown #(swap! state assoc :is-open? true)
        close-dropdown #(swap! state assoc :is-open? false)

        select-item (fn [value]
                      (fn [event]
                        (swap! state assoc :current-value value)
                        (when on-change (on-change value))))

        as-key-value (fn [item] (if (map? item) [(:value item) (:label item)] [item item]))

        labels-map (into {} (->> options (map as-key-value)))

        value->label (fn [value] (get labels-map value value))

        handle-change-input (fn [event]
                              (let [value (-> event dom/get-target dom/get-value)
                                    value (or (d/parse-integer value) value)]
                                (swap! state assoc :current-value value)
                                (when on-change (on-change value))))]

    (mf/use-effect
     (mf/deps value)
     #(reset! state {:current-value value}))

    (mf/use-effect
     (mf/deps options)
     #(reset! state {:is-open? false
                     :current-value value}))

    [:div.editable-select {:class class}
     [:input.input-text {:value (or (-> @state :current-value value->label) "")
                         :on-change handle-change-input
                         :type type}]
     [:span.dropdown-button {:on-click open-dropdown} i/arrow-down]

     [:& dropdown {:show (:is-open? @state)
                   :on-close close-dropdown}
      [:ul.custom-select-dropdown
       (for [[index item] (map-indexed vector options)]
         (cond
           (= :separator item) [:hr {:key (str (:id @state) "-" index)}]
           :else (let [[value label] (as-key-value item)]
                   [:li.checked-element
                    {:key (str (:id @state) "-" index)
                     :class (when (= value (-> @state :current-value)) "is-selected")
                     :on-click (select-item value)}
                    [:span.check-icon i/tick]
                    [:span label]])))]]]))
