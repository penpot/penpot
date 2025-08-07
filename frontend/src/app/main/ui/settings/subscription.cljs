(ns app.main.ui.settings.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc plan-card*
  {::mf/props :obj}
  [{:keys [card-title
           card-title-icon
           price-value price-period
           benefits-title benefits
           cta-text
           cta-link
           cta-text-trial
           cta-link-trial
           cta-text-with-icon
           cta-link-with-icon
           editors]}]
  [:div {:class (stl/css :plan-card)}
   [:div {:class (stl/css :plan-card-header)}
    [:div {:class (stl/css :plan-card-title-container)}
     (when card-title-icon [:span {:class (stl/css :plan-title-icon)} card-title-icon])
     [:h4 {:class (stl/css :plan-card-title)}  card-title]
     (when editors [:span {:class (stl/css :plan-editors)} (tr "subscription.settings.editors" editors)])]
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
(def ^:private schema:seats-form
  [:map {:title "SeatsForm"}
   [:min-members [::sm/number {:min 1 :max 9999}]]])

(mf/defc subscribe-management-dialog
  {::mf/register modal/components
   ::mf/register-as :management-dialog}
  [{:keys [subscription-type current-subscription teams subscribe-to-trial]}]

  (let [subscription-name (if subscribe-to-trial
                            (if (= subscription-type "unlimited")
                              (tr "subscription.settings.unlimited-trial")
                              (tr "subscription.settings.enterprise-trial"))
                            (case subscription-type
                              "professional" (tr "subscription.settings.professional")
                              "unlimited" (tr "subscription.settings.unlimited")
                              "enterprise" (tr "subscription.settings.enterprise")))
        initial                    (mf/with-memo []
                                     {:min-members (or (some->> teams (map :total-editors) (apply max)) 1)})
        form                       (fm/use-form :schema schema:seats-form
                                                :initial initial)
        subscribe-to-unlimited     (mf/use-fn
                                    (fn [form]
                                      (let [data (:clean-data @form)
                                            return-url (-> (rt/get-current-href) (rt/encode-url))
                                            href (dm/str "payments/subscriptions/create?type=unlimited&quantity=" (:min-members data) "&returnUrl=" return-url)]
                                        (st/emit! (ptk/event ::ev/event {::ev/name "create-trial-subscription"
                                                                         :type "unlimited"
                                                                         :quantity (:min-members data)})
                                                  (rt/nav-raw :href href)))))

        subscribe-to-enterprise   (mf/use-fn
                                   (fn []
                                     (st/emit! (ptk/event ::ev/event {::ev/name "create-trial-subscription"
                                                                      :type "enterprise"}))
                                     (let [return-url (-> (rt/get-current-href) (rt/encode-url))
                                           href (dm/str "payments/subscriptions/create?type=enterprise&returnUrl=" return-url)]
                                       (st/emit! (rt/nav-raw :href href)))))

        handle-accept-dialog       (mf/use-fn
                                    (fn []
                                      (st/emit! (ptk/event ::ev/event {::ev/name "open-subscription-management"
                                                                       ::ev/origin "settings"
                                                                       :section "subscription-management-modal"}))
                                      (let [current-href (rt/get-current-href)
                                            returnUrl (js/encodeURIComponent current-href)
                                            href (dm/str "payments/subscriptions/show?returnUrl=" returnUrl)]
                                        (st/emit! (rt/nav-raw :href href)))
                                      (modal/hide!)))
        handle-close-dialog        (mf/use-fn
                                    (fn []
                                      (st/emit! (ptk/event ::ev/event {::ev/name "close-subscription-modal"}))
                                      (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} i/close]
      [:div {:class (stl/css :modal-title :subscription-title)}
       (tr "subscription.settings.management.dialog.title" subscription-name)]

      [:div {:class (stl/css :modal-content)}
       (if (seq teams)
         [:* [:div {:class (stl/css :modal-text)}
              (tr "subscription.settings.management.dialog.choose-this-plan")]
          [:ul {:class (stl/css :teams-list)}
           (for [team teams]
             [:li {:key (dm/str (:id team)) :class (stl/css :team-name)}
              (:name team) (tr "subscription.settings.management.dialog.members" (:total-editors team))])]]
         [:div {:class (stl/css :modal-text)}
          (tr "subscription.settings.management.dialog.no-teams")])

       (when (and
              (or (and (= subscription-type "professional") (contains? #{"unlimited" "enterprise"} (:type current-subscription)))
                  (and (= subscription-type "unlimited") (= (:type current-subscription) "enterprise")))
              (not (contains? #{"unpaid" "canceled"} (:status current-subscription)))
              (not subscribe-to-trial))
         [:div {:class (stl/css :modal-text)}
          (tr "subscription.settings.management.dialog.downgrade")])

       (if (and (= subscription-type "unlimited")
                (or subscribe-to-trial (contains? #{"unpaid" "canceled"} (:status current-subscription))))
         [:& fm/form {:on-submit subscribe-to-unlimited
                      :class (stl/css :seats-form)
                      :form form}
          [:label {:for "editors-subscription" :class (stl/css :modal-text :editors-label)}
           (tr "subscription.settings.management.dialog.select-editors")]

          [:div {:class (stl/css :editors-wrapper)}
           [:div {:class (stl/css :fields-row)}
            [:& fm/input {:type "number"
                          :name :min-members
                          :show-error false
                          :label ""
                          :class (stl/css :input-field)}]]
           [:div {:class (stl/css :editors-cost)}
            [:span {:class (stl/css :modal-text-small)}
             (when (> (get-in @form [:clean-data :min-members]) 25)
               [:> i18n/tr-html*
                {:class (stl/css :modal-text-cap)
                 :tag-name "span"
                 :content (tr "subscription.settings.management.dialog.price-month" "175")}])
             [:> i18n/tr-html*
              {:class (stl/css-case :text-strikethrough (> (get-in @form [:clean-data :min-members]) 25))
               :tag-name "span"
               :content (tr "subscription.settings.management.dialog.price-month"
                            (* 7 (or (get-in @form [:clean-data :min-members]) 0)))}]]
            [:span {:class (stl/css :modal-text-small)}
             (tr "subscription.settings.management.dialog.payment-explanation")]]]

          [:div {:class (stl/css :modal-footer)}
           [:div {:class (stl/css :action-buttons)}
            [:input
             {:class (stl/css :cancel-button)
              :type "button"
              :value (tr "ds.confirm-cancel")
              :on-click handle-close-dialog}]

            [:> fm/submit-button*
             {:label (if subscribe-to-trial (tr "subscription.settings.start-trial") (tr "labels.continue"))
              :class (stl/css :primary-button)}]]]]

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
             :on-click (if (or subscribe-to-trial
                               (contains? #{"unpaid" "canceled"} (:status current-subscription)))
                         subscribe-to-enterprise handle-accept-dialog)}]]])]]]))

(mf/defc subscription-success-dialog
  {::mf/register modal/components
   ::mf/register-as :subscription-success}
  [{:keys [subscription-name]}]

  (let [profile              (mf/deref refs/profile)
        handle-close-dialog  (mf/use-fn
                              (fn []
                                (st/emit! (ptk/event ::ev/event {::ev/name "subscription-success"}))
                                (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :subscription-success)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} i/close]
      [:div {:class (stl/css :modal-success-content)}
       [:div {:class (stl/css :modal-start)}
        (if (= "light" (:theme profile))
          i/logo-subscription-light
          i/logo-subscription)]

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
  (let [route          (mf/deref refs/route)
        authenticated? (da/is-authenticated? profile)

        teams*         (mf/use-state nil)
        teams          (deref teams*)

        locale         (mf/deref i18n/locale)

        params-subscription
        (-> route :params :query :subscription)

        show-trial-subscription-modal?
        (or (= params-subscription "subscription-to-penpot-unlimited")
            (= params-subscription "subscription-to-penpot-enterprise"))

        show-subscription-success-modal?
        (or (= params-subscription "subscribed-to-penpot-unlimited")
            (= params-subscription "subscribed-to-penpot-enterprise"))

        success-modal-is-trial?
        (-> route :params :query :trial)

        subscription
        (-> profile :props :subscription)

        subscription-type
        (get-subscription-type subscription)

        subscription-is-trial?
        (= (:status subscription) "trialing")

        member-since
        (dt/format-date-locale-short (:created-at profile) {:locale locale})

        subscribed-since
        (dt/format-date-locale-short (:start-date subscription) {:locale locale})

        go-to-pricing-page
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "explore-pricing-click"
                                ::ev/origin "settings"
                                :section "subscription"}))
           (dom/open-new-window "https://penpot.app/pricing")))

        go-to-payments
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "open-subscription-management"
                                ::ev/origin "settings"
                                :section "subscription"}))
           (let [current-href (rt/get-current-href)
                 returnUrl (js/encodeURIComponent current-href)
                 href (dm/str "payments/subscriptions/show?returnUrl=" returnUrl)]
             (st/emit! (rt/nav-raw :href href)))))

        open-subscription-modal
        (mf/use-fn
         (mf/deps teams)
         (fn [subscription-type current-subscription]
           (st/emit! (ev/event {::ev/name "open-subscription-modal"
                                ::ev/origin "settings:in-app"}))
           (st/emit!
            (modal/show :management-dialog
                        {:subscription-type subscription-type
                         :current-subscription current-subscription
                         :teams teams :subscribe-to-trial (not subscription)}))))]

    (mf/with-effect []
      (->> (rp/cmd! :get-owned-teams)
           (rx/subs! (fn [teams]
                       (reset! teams* teams)))))

    (mf/with-effect []
      (dom/set-html-title (tr "subscription.labels")))

    (mf/with-effect [authenticated? show-subscription-success-modal? show-trial-subscription-modal? success-modal-is-trial? subscription]
      (when ^boolean authenticated?
        (cond
          ^boolean show-trial-subscription-modal?

          (st/emit!
           (ptk/event ::ev/event {::ev/name "open-subscription-modal"
                                  ::ev/origin "settings:from-pricing-page"})
           (modal/show :management-dialog
                       {:subscription-type (if (= params-subscription "subscription-to-penpot-unlimited")
                                             "unlimited"
                                             "enterprise")
                        :current-subscription subscription
                        :teams teams
                        :subscribe-to-trial (not subscription)})
           (rt/nav :settings-subscription {} {::rt/replace true}))

          ^boolean show-subscription-success-modal?
          (st/emit!
           (modal/show :subscription-success
                       {:subscription-name (if (= params-subscription "subscribed-to-penpot-unlimited")
                                             (if (= success-modal-is-trial? "true")
                                               (tr "subscription.settings.unlimited-trial")
                                               (tr "subscription.settings.unlimited"))
                                             (if (= success-modal-is-trial? "true")
                                               (tr "subscription.settings.enterprise-trial")
                                               (tr "subscription.settings.enterprise")))})
           (rt/nav :settings-subscription {} {::rt/replace true})))))

    [:section {:class (stl/css :dashboard-section)}
     [:div {:class (stl/css :dashboard-content)}
      [:h2 {:class (stl/css :title-section)} (tr "subscription.labels")]


      [:div {:class (stl/css :your-subscription)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.section-plan")]
       (case subscription-type
         "professional"
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :benefits [(tr "subscription.settings.professional.projects-files"),
                                    (tr "subscription.settings.professional.teams-editors"),
                                    (tr "subscription.settings.professional.storage-autosave")]}]

         "unlimited"
         (if subscription-is-trial?
           [:> plan-card* {:card-title (tr "subscription.settings.unlimited-trial")
                           :card-title-icon i/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                           :benefits [(tr "subscription.settings.unlimited.teams"),
                                      (tr "subscription.settings.unlimited.bill"),
                                      (tr "subscription.settings.unlimited.storage-autosave")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments
                           :cta-text-trial (tr "subscription.settings.add-payment-to-continue")
                           :cta-link-trial go-to-payments
                           :editors (-> profile :props :subscription :quantity)}]

           [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                           :card-title-icon i/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                           :benefits [(tr "subscription.settings.unlimited.teams"),
                                      (tr "subscription.settings.unlimited.bill"),
                                      (tr "subscription.settings.unlimited.storage-autosave")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments
                           :editors (-> profile :props :subscription :quantity)}])

         "enterprise"
         (if subscription-is-trial?
           [:> plan-card* {:card-title (tr "subscription.settings.enterprise-trial")
                           :card-title-icon i/character-e
                           :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                           :benefits [(tr "subscription.settings.enterprise.unlimited-storage"),
                                      (tr "subscription.settings.enterprise.capped-bill"),
                                      (tr "subscription.settings.enterprise.autosave")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments}]
           [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                           :card-title-icon i/character-e
                           :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                           :benefits [(tr "subscription.settings.enterprise.unlimited-storage"),
                                      (tr "subscription.settings.enterprise.capped-bill"),
                                      (tr "subscription.settings.enterprise.autosave")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments}]))

       [:div {:class (stl/css :membership-container)}
        (when (and subscribed-since (not= subscription-type "professional"))
          [:div {:class (stl/css :membership)}
           [:span {:class (stl/css :subscription-member)} i/crown]
           [:span {:class (stl/css :membership-date)}
            (tr "subscription.settings.support-us-since" subscribed-since)]])

        [:div {:class (stl/css :membership)}
         [:span {:class (stl/css :penpot-member)} i/user]
         [:span {:class (stl/css :membership-date)}
          (tr "subscription.settings.member-since" member-since)]]]]

      [:div {:class (stl/css :other-subscriptions)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.other-plans")]
       (when (not= subscription-type "professional")
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :price-value "$0"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits [(tr "subscription.settings.professional.projects-files"),
                                    (tr "subscription.settings.professional.teams-editors"),
                                    (tr "subscription.settings.professional.storage-autosave")]
                         :cta-text (tr "subscription.settings.subscribe")
                         :cta-link #(open-subscription-modal "professional")
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (not= subscription-type "unlimited")
         [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                         :card-title-icon i/character-u
                         :price-value "$7"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                         :benefits [(tr "subscription.settings.unlimited.teams"),
                                    (tr "subscription.settings.unlimited.bill"),
                                    (tr "subscription.settings.unlimited.storage-autosave")]
                         :cta-text (if subscription (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "unlimited" subscription)
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (not= subscription-type "enterprise")
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon i/character-e
                         :price-value "$950"
                         :price-period (tr "subscription.settings.price-organization-month")
                         :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                         :benefits [(tr "subscription.settings.enterprise.unlimited-storage"),
                                    (tr "subscription.settings.enterprise.capped-bill"),
                                    (tr "subscription.settings.enterprise.autosave")]
                         :cta-text (if subscription (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "enterprise" subscription)
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])]]]))
