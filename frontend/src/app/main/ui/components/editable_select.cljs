;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.editable-select
  (:require
   [rumext.alpha :as mf]
   [app.common.uuid :as uuid]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [app.main.ui.icons :as i]
   [app.main.ui.components.dropdown :refer [dropdown]]))

(mf/defc editable-select [{:keys [value type options class on-change placeholder]}]
  (let [state (mf/use-state {:id (uuid/next)
                             :is-open? false
                             :current-value value
                             :top nil
                             :left nil
                             :bottom nil})
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
                                (when on-change (on-change value))))

        on-node-load
        (fn [node]
          ;; There is a problem when changing the state in this callback that
          ;; produces the dropdown to close in the same event
          (timers/schedule
           #(when-let [bounds (when node (dom/get-bounding-rect node))]
              (let [{window-height :height} (dom/get-window-size)
                    {:keys [left top height]} bounds
                    bottom (when (< (- window-height top) 300) (- window-height top))
                    top (when (>= (- window-height top) 300) (+ top height))]
                (swap! state
                       assoc
                       :left left
                       :top top
                       :bottom bottom)))))]

    (mf/use-effect
     (mf/deps value)
     #(reset! state {:current-value value}))

    (mf/use-effect
     (mf/deps options)
     #(reset! state {:is-open? false
                     :current-value value}))

    [:div.editable-select {:class class
                           :ref on-node-load}
     [:input.input-text {:value (or (-> @state :current-value value->label) "")
                         :on-change handle-change-input
                         :placeholder placeholder
                         :type type}]
     [:span.dropdown-button {:on-click open-dropdown} i/arrow-down]

     [:& dropdown {:show (get @state :is-open? false)
                   :on-close close-dropdown}
      [:ul.custom-select-dropdown {:style {:position "fixed"
                                           :top (:top @state)
                                           :left (:left @state)
                                           :bottom (:bottom @state)}}
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
