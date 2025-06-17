(ns app.main.ui.settings.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc plan-card*
  {::mf/props :obj}
  [{:keys [card-title card-title-icon price-value price-period benefits-title benefits cta-text cta-link cta-text-trial cta-link-trial cta-text-with-icon cta-link-with-icon]}]
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
      [:li {:key (dm/str benefit) :class (stl/css :benefit)} "- " benefit])]
   (when (and cta-link-with-icon cta-text-with-icon) [:button {:class (stl/css :cta-button :more-info)
                                                               :on-click cta-link-with-icon} cta-text-with-icon i/open-link])
   (when (and cta-link cta-text) [:button {:class (stl/css-case :cta-button true
                                                                :bottom-link (not (and cta-link-trial cta-text-trial)))
                                           :on-click cta-link} cta-text])
   (when (and cta-link-trial cta-text-trial) [:button {:class (stl/css :cta-button :bottom-link)
                                                       :on-click cta-link-trial} cta-text-trial])])

(mf/defc subscribe-management-dialog
  {::mf/register modal/components
   ::mf/register-as :management-dialog}
  [{:keys [subscription-name teams subscribe-to-trial]}]

  (let [min-members*                (mf/use-state (or (some->> teams (map :total-members) (apply max)) 1))
        min-members                 (deref min-members*)
        formatted-subscription-name (if subscribe-to-trial
                                      (if (= subscription-name "unlimited")
                                        (tr "subscription.settings.unlimited-trial")
                                        (tr "subscription.settings.enterprise-trial"))
                                      (case subscription-name
                                        "professional" (tr "subscription.settings.professional")
                                        "unlimited" (tr "subscription.settings.unlimited")
                                        "enterprise" (tr "subscription.settings.enterprise")))
        handle-subscription-trial   (if (= subscription-name "unlimited")
                                      (mf/use-fn
                                       (mf/deps min-members)
                                       (fn []
                                         (st/emit! (ptk/event ::ev/event {::ev/name "create-trial-subscription"
                                                                          :type "unlimited"
                                                                          :quantity min-members}))
                                         (let [current-href (rt/get-current-href)
                                               returnUrl (js/encodeURIComponent current-href)
                                               href (dm/str "payments/subscriptions/create?type=unlimited&quantity=" min-members "&returnUrl=" returnUrl)]
                                           (st/emit! (rt/nav-raw :href href)))))

                                      (mf/use-fn
                                       (fn []
                                         (st/emit! (ptk/event ::ev/event {::ev/name "create-trial-subscription"
                                                                          :type "enterprise"}))
                                         (let [current-href (rt/get-current-href)
                                               returnUrl (js/encodeURIComponent current-href)
                                               href (dm/str "payments/subscriptions/create?type=enterprise&returnUrl=" returnUrl)]
                                           (st/emit! (rt/nav-raw :href href))))))
        handle-accept-dialog       (mf/use-callback
                                    (fn []
                                      (st/emit! (ptk/event ::ev/event {::ev/name "open-subscription-management"
                                                                       ::ev/origin "profile"
                                                                       :section "subscription-management-modal"}))
                                      (let [current-href (rt/get-current-href)
                                            returnUrl (js/encodeURIComponent current-href)
                                            href (dm/str "payments/subscriptions/show?returnUrl=" returnUrl)]
                                        (st/emit! (rt/nav-raw :href href)))
                                      (modal/hide!)))
        handle-close-dialog        (mf/use-callback
                                    (fn []
                                      (st/emit! (ptk/event ::ev/event {::ev/name "close-subscription-modal"}))
                                      (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} i/close]
      [:div {:class (stl/css :modal-title :subscription-title)}
       (tr "subscription.settings.management.dialog.title" formatted-subscription-name)]

      [:div {:class (stl/css :modal-content)}
       (if (seq teams)
         [* [:div {:class (stl/css :modal-text)}
             (tr "subscription.settings.management.dialog.choose-this-plan")]
          [:ul {:class (stl/css :teams-list)}
           (for [team (js->clj teams :keywordize-keys true)]
             [:li {:key (dm/str (:id team)) :class (stl/css :team-name)}
              (:name team) (tr "subscription.settings.management.dialog.members" (:total-members team))])]]
         [:div {:class (stl/css :modal-text)}
          (tr "subscription.settings.management.dialog.no-teams")])

       (when (and (= subscription-name "unlimited") subscribe-to-trial)
         [[:label {:for "editors-subscription" :class (stl/css :modal-text :editors-label)}
           (tr "subscription.settings.management.dialog.select-editors")]
          [:div {:class (stl/css :editors-wrapper)}
           [:div {:class (stl/css :input-wrapper)}
            [:input {:id "editors-subscription"
                     :class (stl/css :input-field)
                     :type "number"
                     :value min-members
                     :min 1
                     :on-change #(let [new-value (js/parseInt (.. % -target -value))]
                                   (reset! min-members* (if (or (js/isNaN new-value) (zero? new-value)) 1 (max 1 new-value))))}]]
           [:div {:class (stl/css :editors-cost)}
            [:span {:class (stl/css :modal-text-small)}
             (tr "subscription.settings.management.dialog.price-month" min-members)]
            [:span {:class (stl/css :modal-text-small)}
             (tr "subscription.settings.management.dialog.payment-explanation")]]]])

       (when (and
              (or (= subscription-name "professional") (= subscription-name "unlimited"))
              (not subscribe-to-trial))
         [:div {:class (stl/css :modal-text)}
          (tr "subscription.settings.management.dialog.downgrade")])

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)}
         [:input
          {:class (stl/css :cancel-button)
           :type "button"
           :value (tr "ds.confirm-cancel")
           :on-click handle-close-dialog}]

         [:input
          {:class (stl/css :primary-button)
           :type "button"
           :value (if subscribe-to-trial (tr "subscription.settings.start-trial") (tr "labels.continue"))
           :on-click (if subscribe-to-trial handle-subscription-trial handle-accept-dialog)}]]]]]]))

(mf/defc subscription-success-dialog
  {::mf/register modal/components
   ::mf/register-as :subscription-success}
  [{:keys [subscription-name]}]

  (let [handle-close-dialog  (mf/use-callback
                              (fn []
                                (st/emit! (ptk/event ::ev/event {::ev/name "subscription-success"}))
                                (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :subscription-success)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} i/close]
      [:div {:class (stl/css :modal-success-content)}
       [:div {:class (stl/css :modal-start)}
        i/logo-subscription]

       [:div {:class (stl/css :modal-end)}
        [:div {:class (stl/css :modal-title)} (tr "subscription.settings.sucess.dialog.title" subscription-name)]
        [:p {:class (stl/css :modal-text-large)} (tr "subscription.settings.success.dialog.description")]
        [:p {:class (stl/css :modal-text-large)} (tr "subscription.settings.sucess.dialog.footer")]

        [:div {:class (stl/css :success-action-buttons)}
         [:input
          {:class (stl/css :primary-button)
           :type "button"
           :value (tr "labels.close")
           :on-click handle-close-dialog}]]]]]]))

(mf/defc subscription-page*
  [{:keys [profile]}]
  (let [route                           (mf/deref refs/route)
        params                          (:params route)
        show-subscription-success-modal (and (:query params)
                                             (or (= (:subscription (:query params)) "subscribed-to-penpot-unlimited")
                                                 (= (:subscription (:query params)) "subscribed-to-penpot-enterprise")))
        subscription                    (:subscription (:props profile))
        subscription-name               (if subscription
                                          (:type subscription)
                                          "professional")
        subscription-is-trial           (= (:status subscription) "trialing")
        teams*                          (mf/use-state nil)
        teams                           (deref teams*)
        locale                          (mf/deref i18n/locale)
        penpot-member                   (dt/format-date-locale-short (:created-at profile) {:locale locale})
        subscription-member             (dt/format-date-locale-short (:start-date subscription) {:locale locale})
        go-to-pricing-page              (mf/use-fn
                                         (fn []
                                           (st/emit! (ptk/event ::ev/event {::ev/name "explore-pricing-click" ::ev/origin "settings" :section "subscription"}))
                                           (dom/open-new-window "https://penpot.app/pricing")))
        go-to-payments                  (mf/use-fn
                                         (fn []
                                           (st/emit! (ptk/event ::ev/event {::ev/name "open-subscription-management"
                                                                            ::ev/origin "profile"
                                                                            :section "subscription"}))
                                           (let [current-href (rt/get-current-href)
                                                 returnUrl (js/encodeURIComponent current-href)
                                                 href (dm/str "payments/subscriptions/show?returnUrl=" returnUrl)]
                                             (st/emit! (rt/nav-raw :href href)))))
        open-subscription-modal         (mf/use-fn
                                         (mf/deps teams)
                                         (fn [subscription-name]
                                           (st/emit! (ptk/event ::ev/event {::ev/name "open-subscription-modal"}))
                                           (st/emit!
                                            (modal/show :management-dialog
                                                        {:subscription-name subscription-name
                                                         :teams teams :subscribe-to-trial (not subscription)}))))]

    (mf/with-effect []
      (->> (rp/cmd! :get-owned-teams)
           (rx/subs! (fn [teams]
                       (reset! teams* teams)))))

    (mf/with-effect []
      (dom/set-html-title (tr "subscription.labels")))

    (when show-subscription-success-modal
      (st/emit! (modal/show :subscription-success
                            {:subscription-name (if (= (:subscription (:query params)) "subscribed-to-penpot-unlimited")
                                                  (tr "subscription.settings.unlimited-trial-modal")
                                                  (tr "subscription.settings.enterprise-trial-modal"))})))
    [:section {:class (stl/css :dashboard-section)}
     [:div {:class (stl/css :dashboard-content)}
      [:h2 {:class (stl/css :title-section)} (tr "subscription.labels")]


      [:div {:class (stl/css :your-subscription)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.section-plan")]
       (case subscription-name
         "professional"
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :benefits [(tr "subscription.settings.professional.projects-files"),
                                    (tr "subscription.settings.professional.teams-editors"),
                                    (tr "subscription.settings.professional.storage")]}]

         "unlimited"
         (if subscription-is-trial
           [:> plan-card* {:card-title (tr "subscription.settings.unlimited-trial")
                           :card-title-icon i/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                           :benefits [(tr "subscription.settings.unlimited.teams"),
                                      (tr "subscription.settings.unlimited.bill"),
                                      (tr "subscription.settings.unlimited.storage")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments
                           :cta-text-trial (tr "subscription.settings.add-payment-to-continue")
                           :cta-link-trial go-to-payments}]

           [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                           :card-title-icon i/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                           :benefits [(tr "subscription.settings.unlimited.teams"),
                                      (tr "subscription.settings.unlimited.bill"),
                                      (tr "subscription.settings.unlimited.storage")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments}])

         "enterprise"
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon i/character-e
                         :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
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
       (when (not= subscription-name "professional")
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :price-value "$0"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits [(tr "subscription.settings.professional.projects-files"),
                                    (tr "subscription.settings.professional.teams-editors"),
                                    (tr "subscription.settings.professional.storage")]
                         :cta-text (tr "subscription.settings.subscribe")
                         :cta-link #(open-subscription-modal "professional")
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (not= subscription-name "unlimited")
         [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                         :card-title-icon i/character-u
                         :price-value "$7"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                         :benefits [(tr "subscription.settings.unlimited.teams"),
                                    (tr "subscription.settings.unlimited.bill"),
                                    (tr "subscription.settings.unlimited.storage")]
                         :cta-text (if subscription (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "unlimited")
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (not= subscription-name "enterprise")
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon i/character-e
                         :price-value "$950"
                         :price-period (tr "subscription.settings.price-organization-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                         :benefits [(tr "subscription.settings.enterprise.support"),
                                    (tr "subscription.settings.enterprise.security"),
                                    (tr "subscription.settings.enterprise.logs")]
                         :cta-text (if subscription (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "enterprise")
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])]]]))
