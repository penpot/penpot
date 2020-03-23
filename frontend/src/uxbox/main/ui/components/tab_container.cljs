(ns uxbox.main.ui.components.tab-container
  (:require [rumext.alpha :as mf]))

(mf/defc tab-element
  [{:keys [children id title]}]
  [:div.tab-element
   [:div.tab-element-content children]])

(mf/defc tab-container
  [{:keys [children selected on-change-tab]}]
  (.log js/console (map #(-> % .-props .-title) children))
  (let [first-id (-> children first .-props .-id)
        state (mf/use-state {:selected first-id})
        selected (or selected (:selected @state))
        handle-select (fn [tab]
                        (let [id (-> tab .-props .-id)]
                          (swap! state assoc :selected id)
                          (on-change-tab id)))]
    [:div.tab-container
     [:div.tab-container-tabs
      (for [tab children]
        [:div.tab-container-tab-title
         {:on-click (partial handle-select tab)
          :class (when (= selected (-> tab .-props .-id)) "current")}
         (-> tab .-props .-title)])]
     [:div.tab-container-content
      (filter #(= selected (-> % .-props .-id)) children)]]))
