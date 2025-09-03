(ns app.main.ui.settings.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr c]]
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
                                                               :on-click cta-link-with-icon} cta-text-with-icon deprecated-icon/open-link])
   (when (and cta-link cta-text) [:button {:class (stl/css-case :cta-button true
                                                                :bottom-link (not (and cta-link-trial cta-text-trial)))
                                           :on-click cta-link} cta-text])
   (when (and cta-link-trial cta-text-trial) [:button {:class (stl/css :cta-button :bottom-link)
                                                       :on-click cta-link-trial} cta-text-trial])])
(defn schema:seats-form [min-editors]
  [:map {:title "SeatsForm"}
   [:min-members [::sm/number {:min min-editors
                               :max 9999}]]])

(mf/defc subscribe-management-dialog
  {::mf/register modal/components
   ::mf/register-as :management-dialog}
  [{:keys [subscription-type current-subscription editors subscribe-to-trial]}]

  (let [subscription-name (if subscribe-to-trial
                            (if (= subscription-type "unlimited")
                              (tr "subscription.settings.unlimited-trial")
                              (tr "subscription.settings.enterprise-trial"))
                            (case subscription-type
                              "professional" (tr "subscription.settings.professional")
                              "unlimited" (tr "subscription.settings.unlimited")
                              "enterprise" (tr "subscription.settings.enterprise")))
        min-editors                (or (count editors) 1)
        initial                    (mf/with-memo [min-editors]
                                     {:min-members min-editors})
        form                       (fm/use-form :schema (schema:seats-form min-editors)
                                                :initial initial)
        submit-in-progress*        (mf/use-state false)
        subscribe-to-unlimited     (mf/use-fn
                                    (fn [form]
                                      (when (not @submit-in-progress*)
                                        (reset! submit-in-progress* true)
                                        (let [data (:clean-data @form)
                                              return-url (-> (rt/get-current-href) (rt/encode-url))
                                              href (dm/str "payments/subscriptions/create?type=unlimited&quantity=" (:min-members data) "&returnUrl=" return-url)]
                                          (reset! form nil)
                                          (st/emit! (ptk/event ::ev/event {::ev/name "create-trial-subscription"
                                                                           :type "unlimited"
                                                                           :quantity (:min-members data)})
                                                    (rt/nav-raw :href href))))))

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
                                      (modal/hide!)))

        show-editors-list*         (mf/use-state false)
        show-editors-list          (deref show-editors-list*)
        handle-click               (mf/use-fn
                                    (fn [event]
                                      (dom/stop-propagation event)
                                      (swap! show-editors-list* not)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} deprecated-icon/close]
      [:div {:class (stl/css :modal-title :subscription-title)}
       (tr "subscription.settings.management.dialog.title" subscription-name)]

      [:div {:class (stl/css :modal-content)}
       (when (seq editors)
         [:* [:p {:class (stl/css :editors-text)}
              (tr "subscription.settings.management.dialog.currently-editors-title" (c (count editors)))]
          [:button {:class (stl/css :cta-button :show-editors-button) :on-click handle-click}
           (tr "subscription.settings.management.dialog.editors")
           [:span {:class (stl/css :icon-dropdown)}  deprecated-icon/arrow]]
          (when show-editors-list
            [:*
             [:p {:class (stl/css :editors-text :editors-list-warning)}
              (tr "subscription.settings.management.dialog.editors-explanation")]
             [:ul {:class (stl/css :editors-list)}
              (for [editor editors]
                [:li {:key (dm/str (:id editor)) :class (stl/css :team-name)} "- " (:name editor)])]])])

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

          [:div {:class (stl/css :editors-wrapper)}
           [:div {:class (stl/css :fields-row)}
            [:& fm/input {:type "number"
                          :name :min-members
                          :show-error false
                          :label ""
                          :class (stl/css :input-field)}]]
           [:div {:class (stl/css :editors-cost)}
            [:span {:class (stl/css :modal-text-medium)}
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
            [:span {:class (stl/css :modal-text-medium)}
             (tr "subscription.settings.management.dialog.payment-explanation")]]]

          (when (get-in @form [:errors :min-members])
            [:div {:class (stl/css :error-message)}
             (tr "subscription.settings.management.dialog.input-error")])

          [:div {:class (stl/css :unlimited-capped-warning)}
           (tr "subscription.settings.management.dialog.unlimited-capped-warning")]

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
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} deprecated-icon/close]
      [:div {:class (stl/css :modal-success-content)}
       [:div {:class (stl/css :modal-start)}
        (if (= "light" (:theme profile))
          deprecated-icon/logo-subscription-light
          deprecated-icon/logo-subscription)]

       [:div {:class (stl/css :modal-end)}
        [:div {:class (stl/css :modal-title)} (tr "subscription.settings.sucess.dialog.title" subscription-name)]
        (when (not= subscription-name "professional")
          [:p {:class (stl/css :modal-text-large)} (tr "subscription.settings.success.dialog.thanks" subscription-name)])
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

        subscription-editors
        (-> profile :props :subscription :editors)

        subscription
        (-> profile :props :subscription)

        subscription-type
        (get-subscription-type subscription)

        subscription-is-trial?
        (= (:status subscription) "trialing")

        member-since
        (ct/format-inst (:created-at profile) "d MMMM, yyyy")

        subscribed-since
        (ct/format-inst (:start-date subscription) "d MMMM, yyyy")

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
         (mf/deps subscription-editors)
         (fn [subscription-type current-subscription]
           (st/emit! (ev/event {::ev/name "open-subscription-modal"
                                ::ev/origin "settings:in-app"}))
           (st/emit!
            (modal/show :management-dialog
                        {:subscription-type subscription-type
                         :current-subscription current-subscription
                         :editors subscription-editors :subscribe-to-trial (not (:type subscription))}))))]

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
                        :editors subscription-editors
                        :subscribe-to-trial (not (:type subscription))})
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
                         :benefits [(tr "subscription.settings.professional.storage-benefit"),
                                    (tr "subscription.settings.professional.autosave-benefit"),
                                    (tr "subscription.settings.professional.teams-editors-benefit")]}]

         "unlimited"
         (if subscription-is-trial?
           [:> plan-card* {:card-title (tr "subscription.settings.unlimited-trial")
                           :card-title-icon deprecated-icon/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-professional-benefits"),
                           :benefits [(tr "subscription.settings.unlimited.storage-benefit")
                                      (tr "subscription.settings.unlimited.autosave-benefit"),
                                      (tr "subscription.settings.unlimited.bill")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments
                           :cta-text-trial (tr "subscription.settings.add-payment-to-continue")
                           :cta-link-trial go-to-payments
                           :editors (-> profile :props :subscription :quantity)}]

           [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                           :card-title-icon deprecated-icon/character-u
                           :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                           :benefits [(tr "subscription.settings.unlimited.storage-benefit"),
                                      (tr "subscription.settings.unlimited.autosave-benefit"),
                                      (tr "subscription.settings.unlimited.bill")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments
                           :editors (-> profile :props :subscription :quantity)}])

         "enterprise"
         (if subscription-is-trial?
           [:> plan-card* {:card-title (tr "subscription.settings.enterprise-trial")
                           :card-title-icon deprecated-icon/character-e
                           :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits"),
                           :benefits [(tr "subscription.settings.enterprise.unlimited-storage-benefit"),
                                      (tr "subscription.settings.enterprise.autosave"),
                                      (tr "subscription.settings.enterprise.capped-bill")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments
                           :cta-text-trial (tr "subscription.settings.add-payment-to-continue")
                           :cta-link-trial go-to-payments}]
           [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                           :card-title-icon deprecated-icon/character-e
                           :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits"),
                           :benefits [(tr "subscription.settings.enterprise.unlimited-storage-benefit"),
                                      (tr "subscription.settings.enterprise.autosave"),
                                      (tr "subscription.settings.enterprise.capped-bill")]
                           :cta-text (tr "subscription.settings.manage-your-subscription")
                           :cta-link go-to-payments}]))

       [:div {:class (stl/css :membership-container)}
        (when (and subscribed-since (not= subscription-type "professional"))
          [:div {:class (stl/css :membership)}
           [:span {:class (stl/css :subscription-member)} deprecated-icon/crown]
           [:span {:class (stl/css :membership-date)}
            (tr "subscription.settings.support-us-since" subscribed-since)]])

        [:div {:class (stl/css :membership)}
         [:span {:class (stl/css :penpot-member)} deprecated-icon/user]
         [:span {:class (stl/css :membership-date)}
          (tr "subscription.settings.member-since" member-since)]]]]

      [:div {:class (stl/css :other-subscriptions)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.other-plans")]
       (when (not= subscription-type "professional")
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :price-value "$0"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits [(tr "subscription.settings.professional.storage-benefit"),
                                    (tr "subscription.settings.professional.autosave-benefit"),
                                    (tr "subscription.settings.professional.teams-editors-benefit")]
                         :cta-text (tr "subscription.settings.subscribe")
                         :cta-link #(open-subscription-modal "professional")
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (not= subscription-type "unlimited")
         [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                         :card-title-icon deprecated-icon/character-u
                         :price-value "$7"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                         :benefits [(tr "subscription.settings.unlimited.storage-benefit"),
                                    (tr "subscription.settings.unlimited.autosave-benefit"),
                                    (tr "subscription.settings.unlimited.bill")]
                         :cta-text (if (:type subscription) (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "unlimited" subscription)
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (not= subscription-type "enterprise")
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon deprecated-icon/character-e
                         :price-value "$950"
                         :price-period (tr "subscription.settings.price-organization-month")
                         :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                         :benefits [(tr "subscription.settings.enterprise.unlimited-storage-benefit"),
                                    (tr "subscription.settings.enterprise.autosave"),
                                    (tr "subscription.settings.enterprise.capped-bill")]
                         :cta-text (if (:type subscription) (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "enterprise" subscription)
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])]]]))
