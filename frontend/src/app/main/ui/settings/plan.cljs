(ns app.main.ui.settings.plan
  (:require-macros [app.main.style :as stl])
  (:require
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc plan-page*
  []
  (mf/with-effect []
    (dom/set-html-title "Plan"))

  [:section {:class (stl/css :dashboard-settings)}
   [:div
    [:h2 "newwww page"]]])