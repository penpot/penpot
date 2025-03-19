(ns app.main.ui.settings.plans
  (:require-macros [app.main.style :as stl])
  (:require
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc plans-page*
  []
  (mf/with-effect []
    (dom/set-html-title (tr "labels.plans")))

  [:section {:class (stl/css :dashboard-section)}
   [:div {:class (stl/css :dashboard-content)}
    [:h2 {:class (stl/css :plan-title)} (tr "labels.plans")]
    [:h3 {:class (stl/css :plan-section)} (tr "settings.plans.section-plan")]
    [:div {:class (stl/css :plan-card)}
     [:h4 {:class (stl/css :plan-card-title)}  (tr "dashboard.team-plan.professional")]
     [:p {:class (stl/css :plan-card-description)} (tr "settings.plans.extras-description")]]
    [:h3 {:class (stl/css :plan-section)} (tr "settings.plans.other-plans")]]])