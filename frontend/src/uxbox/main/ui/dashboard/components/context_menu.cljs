(ns uxbox.main.ui.dashboard.components.context-menu
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.uuid :as uuid]))

(mf/defc context-menu
  [{ :keys [ is-open options ]}]
  [:div.context-menu
   { :class-name (when is-open "is-open")}
   [:ul.context-menu-items
    (for [[action-name action-handler] options]
      [:li.context-menu-item
       { :key (uuid/next)}
       [:a.context-menu-action {:on-click action-handler} action-name]])]])

