;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.components.tab-container
  (:require [rumext.alpha :as mf]))

(mf/defc tab-element
  [{:keys [children id title]}]
  [:div.tab-element
   [:div.tab-element-content children]])

(mf/defc tab-container
  [{:keys [children selected on-change-tab]}]
  (let [first-id (-> children first .-props .-id)
        state (mf/use-state {:selected first-id})
        selected (or selected (:selected @state))
        handle-select (fn [tab]
                        (let [id (-> tab .-props .-id)]
                          (swap! state assoc :selected id)
                          (when on-change-tab (on-change-tab id))))]
    [:div.tab-container
     [:div.tab-container-tabs
      (for [tab children]
        [:div.tab-container-tab-title
         {:key (str "tab-" (-> tab .-props .-id))
          :on-click (partial handle-select tab)
          :class (when (= selected (-> tab .-props .-id)) "current")}
         (-> tab .-props .-title)])]
     [:div.tab-container-content
      (filter #(= selected (-> % .-props .-id)) children)]]))
