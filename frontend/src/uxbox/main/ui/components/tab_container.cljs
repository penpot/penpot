(ns uxbox.main.ui.components.tab-container
  (:require [rumext.alpha :as mf]))

(mf/defc tab-element
  [{:keys [children id title]}]
  [:div.tab-element
   [:div.tab-element-content children]])

(mf/defc tab-container
  [{:keys [children selected]}]
  (.log js/console (map #(-> % .-props .-title) children))
  (let [first-id (-> children first .-props .-id)
        state (mf/use-state {:selected first-id})]
    [:div.tab-container
     [:div.tab-container-tabs
      (for [tab children]
        [:div.tab-container-tab-title
         {:on-click #(swap! state assoc :selected (-> tab .-props .-id))
          :class (when (= (:selected @state) (-> tab .-props .-id)) "current")}
         (-> tab .-props .-title)])]
     [:div.tab-container-content
      (filter #(= (:selected @state) (-> % .-props .-id)) children)]]))
