;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.select
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))


(defn- as-key-value
  [item]
  (if (map? item)
    [(:value item) (:label item) (:icon item)]
    [item item item]))

(mf/defc select
  [{:keys [default-value options class dropdown-class is-open? on-change on-pointer-enter-option on-pointer-leave-option disabled data-direction]}]
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

        dropdown-element* (mf/use-ref nil)
        dropdown-direction* (mf/use-state "down")
        dropdown-direction-change* (mf/use-ref 0)

        open-dropdown
        (mf/use-fn
         (mf/deps disabled)
         (fn []
           (when-not disabled
             (swap! state* assoc :is-open? true))))

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

    (mf/with-effect [is-open?]
      (when (and (not= 0 (mf/ref-val dropdown-direction-change*)) (= false is-open?))
        (reset! dropdown-direction* "down")
        (mf/set-ref-val! dropdown-direction-change* 0)))

    (mf/with-effect [is-open? dropdown-element*]
      (let [dropdown-element (mf/ref-val dropdown-element*)]
        (when (and (= 0 (mf/ref-val dropdown-direction-change*)) dropdown-element)
          (let [is-outside? (dom/is-element-outside? dropdown-element)]
            (reset! dropdown-direction* (if is-outside? "up" "down"))
            (mf/set-ref-val! dropdown-direction-change* (inc (mf/ref-val dropdown-direction-change*)))))))

    (let [selected-option (first (filter #(= (:value %) default-value) options))
          current-icon (:icon selected-option)
          current-icon-ref (deprecated-icon/key->icon current-icon)]
      [:div {:on-click open-dropdown
             :role "combobox"
             :class (dm/str (stl/css-case :custom-select true
                                          :disabled disabled
                                          :icon (some? current-icon-ref))
                            " " class)}
       (when (and current-icon current-icon-ref)
         [:span {:class (stl/css :current-icon)} current-icon-ref])
       [:span {:class (stl/css :current-label)} current-label]
       [:span {:class (stl/css :dropdown-button)} deprecated-icon/arrow]
       [:& dropdown {:show is-open? :on-close close-dropdown}
        [:ul {:ref dropdown-element* :data-direction (or data-direction @dropdown-direction*)
              :class (dm/str dropdown-class " " (stl/css :custom-select-dropdown))}
         (for [[index item] (d/enumerate options)]
           (if (= :separator item)
             [:li {:class (dom/classnames (stl/css :separator) true)
                   :role "option"
                   :key (dm/str current-id "-" index)}]
             (let [[value label icon] (as-key-value item)
                   icon-ref (deprecated-icon/key->icon icon)]
               [:li
                {:key (dm/str current-id "-" index)
                 :role "option"
                 :class (stl/css-case
                         :checked-element true
                         :disabled (:disabled item)
                         :is-selected (= value current-value))
                 :data-value (pr-str value)
                 :on-pointer-enter highlight-item
                 :on-pointer-leave unhighlight-item
                 :on-click select-item}
                (when (and icon icon-ref) [:span {:class (stl/css :icon)} icon-ref])
                [:span {:class (stl/css :label)} label]
                [:span {:class (stl/css :check-icon)} deprecated-icon/tick]])))]]])))
