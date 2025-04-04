(ns app.main.ui.settings.plans
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc plan-card*
  {::mf/props :obj}
  [{:keys [card-title card-title-icon price-value price-period benefits cta-text cta-link]}]
  [:div {:class (stl/css :plan-card)}
   [:div {:class (stl/css :plan-header)}
    [:div {:class (stl/css :plan-title-container)}
     (when card-title-icon [:span {:class (stl/css :plan-title-icon)}
                            [:span {:class (stl/css :title-icon)} card-title-icon]])
     [:h4 {:class (stl/css :plan-title)}  card-title]]
    (when (and price-value price-period)
      [:div {:class (stl/css :plan-price)}
       [:span {:class (stl/css :plan-price-value)} price-value]
       [:span {:class (stl/css :plan-price-period)} price-period]])]
   [:ul {:class (stl/css :benefits-list)}
    (for [benefit  benefits]
      [:li {:key (str benefit) :class (stl/css :benefit)} "- " benefit])]
   (when (and cta-link cta-text) [:a {:class (stl/css :cta-button)
                                      :href cta-link} cta-text])])

(mf/defc plans-page*
  []
  (let [
        subscription-type :unlimited
        penpot-member "January 4, 2024"
        subscription-member "January 17, 2024"]

  (mf/with-effect []
    (dom/set-html-title (tr "labels.subscription")))
  [:section {:class (stl/css :dashboard-section)}
   [:div {:class (stl/css :dashboard-content)}
    [:h2 {:class (stl/css :title-section)} (tr "labels.subscription")]


    [:div {:class (stl/css :your-subscription)}
     [:h3 {:class (stl/css :plan-section-title)} (tr "settings.plans.section-plan")]
     (case subscription-type
       :profesional
       [:> plan-card* {:card-title (tr "settings.plans.professional")
                       :benefits [(tr "settings.plans.professional.projects-files"),
                                  (tr "settings.plans.professional.teams-editors"),
                                  (tr "settings.plans.professional.storage")]}]

       :unlimited
       [:> plan-card* {:card-title (tr "settings.plans.unlimited")
                       :benefits [(tr "settings.plans.unlimited.teams"),
                                  (tr "settings.plans.unlimited.bill"),
                                  (tr "settings.plans.unlimited.storage")]}]

       :enterprise
       [:> plan-card* {:card-title (tr "settings.plans.enterprise")
                       :benefits [(tr "settings.plans.enterprise.support"),
                                  (tr "settings.plans.enterprise.security"),
                                  (tr "settings.plans.enterprise.logs")]}])


     (when subscription-member [:div {:class (stl/css :membership)}
                                [:span {:class (stl/css :member-icon)} i/user]
                                [:span {:class (stl/css :membership-date)} "You support us since January 17, 2024"]])

     (when penpot-member [:div {:class (stl/css :membership)}
                          [:span {:class (stl/css :user-icon)} i/user]
                          [:span {:class (stl/css :membership-date)} "Penpot member since January 4 2024"]])]

    [:div {:class (stl/css :other-subscriptions)}
     [:h3 {:class (stl/css :plan-section-title)} (tr "settings.plans.other-plans")]
       (when (not= subscription-type :professional)
         [:> plan-card* {:card-title (tr "settings.plans.professional")
                         :benefits [(tr "settings.plans.professional.projects-files"),
                                    (tr "settings.plans.professional.teams-editors"),
                                    (tr "settings.plans.professional.storage")]}])

       (when (not= subscription-type :unlimited)
         [:> plan-card* {:card-title (tr "settings.plans.unlimited")
                         :card-title-icon i/user
                         :benefits [(tr "settings.plans.unlimited.teams"),
                                    (tr "settings.plans.unlimited.bill"),
                                    (tr "settings.plans.unlimited.storage")]}])

       (when (not= subscription-type :enterprise)
         [:> plan-card* {:card-title (tr "settings.plans.enterprise")
                         :card-title-icon i/user
                         :benefits [(tr "settings.plans.enterprise.support"),
                                    (tr "settings.plans.enterprise.security"),
                                    (tr "settings.plans.enterprise.logs")]}])
     ]
    ]]))
