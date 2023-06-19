;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.select
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn- as-key-value
  [item]
  (if (map? item)
    [(:value item) (:label item)]
    [item item]))

(mf/defc select
  [{:keys [default-value options class is-open? on-change on-pointer-enter-option on-pointer-leave-option]}]
  (let [label-index    (mf/with-memo [options]
                         (into {} (map as-key-value) options))

        state*         (mf/use-state
                        {:id (uuid/next)
                         :is-open? (or is-open? false)
                         :current-value default-value})

        state          (deref state*)
        current-id     (get state :id)
        current-value  (get state :current-value)
        current-label  (get label-index current-value)
        is-open?       (:is-open? state)

        open-dropdown  (mf/use-fn #(swap! state* assoc :is-open? true))
        close-dropdown (mf/use-fn #(swap! state* assoc :is-open? false))

        select-item
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (d/read-string))]

             (swap! state* assoc :current-value value)
             (when (fn? on-change)
               (on-change value)))))

        highlight-item
        (mf/use-fn
         (mf/deps on-pointer-enter-option)
         (fn [event]
           (when (fn? on-pointer-enter-option)
             (let [value (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (d/read-string))]
               (on-pointer-enter-option value)))))

        unhighlight-item
        (mf/use-fn
         (mf/deps on-pointer-leave-option)
         (fn [event]
           (when (fn? on-pointer-leave-option)
             (let [value (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (d/read-string))]
               (on-pointer-leave-option value)))))]

    (mf/with-effect [default-value]
      (swap! state* assoc :current-value default-value))

    [:div.custom-select {:on-click open-dropdown :class class}
     [:span current-label]
     [:span.dropdown-button i/arrow-down]
     [:& dropdown {:show is-open? :on-close close-dropdown}
      [:ul.custom-select-dropdown
       (for [[index item] (d/enumerate options)]
         (if (= :separator item)
           [:hr {:key (dm/str current-id "-" index)}]
           (let [[value label] (as-key-value item)]
             [:li.checked-element
              {:key (dm/str current-id "-" index)
               :class (when (= value current-value) "is-selected")
               :data-value (pr-str value)
               :on-pointer-enter highlight-item
               :on-pointer-leave unhighlight-item
               :on-click select-item}
              [:span.check-icon i/tick]
              [:span label]])))]]]))
