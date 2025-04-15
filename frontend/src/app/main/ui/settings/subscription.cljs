(ns app.main.ui.settings.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.refs :as refs]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.time :as dt]
   [rumext.v2 :as mf]))

(mf/defc plan-card*
  {::mf/props :obj}
  [{:keys [card-title card-title-icon price-value price-period benefits-title benefits cta-text cta-link]}]
  [:div {:class (stl/css :plan-card)}
   [:div {:class (stl/css :plan-card-header)}
    [:div {:class (stl/css :plan-card-title-container)}
     (when card-title-icon [:span {:class (stl/css :plan-title-icon)} card-title-icon])
     [:h4 {:class (stl/css :plan-card-title)}  card-title]]
    (when (and price-value price-period)
      [:div {:class (stl/css :plan-price)}
       [:span {:class (stl/css :plan-price-value)} price-value]
       [:span {:class (stl/css :plan-price-period)} " / " price-period]])]
   (when benefits-title [:h5 {:class (stl/css :benefits-title)} benefits-title])
   [:ul {:class (stl/css :benefits-list)}
    (for [benefit  benefits]
      [:li {:key (str benefit) :class (stl/css :benefit)} "- " benefit])]
   (when (and cta-link cta-text) [:a {:class (stl/css :cta-button)
                                      :href cta-link} cta-text])])

(mf/defc subscription-page*
  []
  (let [;; TODO subscription cases professional/unlimited/enterprise
        subscription-name      :unlimited
        subscription-is-trial  false
        locale                 (mf/deref i18n/locale)
        profile                (mf/deref refs/profile)
        penpot-member          (dt/format-date-locale-short (:created-at profile) {:locale locale})
        ;; TODO get subscription member date
        subscription-member    "January 17, 2024"
        ;; TODO update url to penpot payments
        go-to-payments         "https://penpot.app/pricing"]

    (mf/with-effect []
      (dom/set-html-title (tr "subscription.labels")))
    [:section {:class (stl/css :dashboard-section)}
     [:div {:class (stl/css :dashboard-content)}
      [:h2 {:class (stl/css :title-section)} (tr "subscription.labels")]


      [:div {:class (stl/css :your-subscription)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.section-plan")]
       (case subscription-name
         :professional
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :benefits [(tr "subscription.settings.professional.projects-files"),
                                    (tr "subscription.settings.professional.teams-editors"),
                                    (tr "subscription.settings.professional.storage")]}]

         :unlimited
         (if subscription-is-trial
           [:> plan-card* {:card-title (tr "subscription.settings.unlimited-trial")
                           :card-title-icon i/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                           :benefits [(tr "subscription.settings.unlimited.teams"),
                                      (tr "subscription.settings.unlimited.bill"),
                                      (tr "subscription.settings.unlimited.storage")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments}]

           [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                           :card-title-icon i/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                           :benefits [(tr "subscription.settings.unlimited.teams"),
                                      (tr "subscription.settings.unlimited.bill"),
                                      (tr "subscription.settings.unlimited.storage")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments}])

         :enterprise
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon i/character-e
                         :benefits-title (tr "subscription.settings.benefits.all-professiona-benefits")
                         :benefits [(tr "subscription.settings.enterprise.support"),
                                    (tr "subscription.settings.enterprise.security"),
                                    (tr "subscription.settings.enterprise.logs")]
                         :cta-text (tr "subscription.settings.manage-your-subscription")
                         :cta-link go-to-payments}])

       [:div {:class (stl/css :membership-container)}
        (when subscription-member [:div {:class (stl/css :membership)}
                                   [:span {:class (stl/css :subscription-member)} i/crown]
                                   [:span {:class (stl/css :membership-date)} (tr "subscription.settings.support-us-since" subscription-member)]])

        [:div {:class (stl/css :membership)}
         [:span {:class (stl/css :penpot-member)} i/user]
         [:span {:class (stl/css :membership-date)} (tr "subscription.settings.member-since" penpot-member)]]]]

      [:div {:class (stl/css :other-subscriptions)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.other-plans")]
       (when (not= subscription-name :professional)
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :price-value "$0"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits [(tr "subscription.settings.professional.projects-files"),
                                    (tr "subscription.settings.professional.teams-editors"),
                                    (tr "subscription.settings.professional.storage")]
                         :cta-text (tr "subscription.dashboard.power-up.subscribe")
                         :cta-link go-to-payments}])

       (when (not= subscription-name :unlimited)
         [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                         :card-title-icon i/character-u
                         :price-value "$7"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professiona-benefits")
                         :benefits [(tr "subscription.settings.unlimited.teams"),
                                    (tr "subscription.settings.unlimited.bill"),
                                    (tr "subscription.settings.unlimited.storage")]
                         :cta-text (tr "subscription.settings.ulimited.try-it-free")
                         :cta-link go-to-payments}])

       (when (not= subscription-name :enterprise)
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon i/character-e
                         :price-value "$950"
                         :price-period (tr "subscription.settings.price-organization-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professiona-benefits")
                         :benefits [(tr "subscription.settings.enterprise.support"),
                                    (tr "subscription.settings.enterprise.security"),
                                    (tr "subscription.settings.enterprise.logs")]
                         :cta-text (tr "subscription.dashboard.power-up.subscribe")
                         :cta-link go-to-payments}])]]]))
