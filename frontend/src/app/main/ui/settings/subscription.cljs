(ns app.main.ui.settings.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.data.notifications :as ntf]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.main.ui.nitrate.nitrate-activation-success-modal]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr c]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc plan-card*
  {::mf/wrap [mf/memo]}
  [{:keys [card-title
           card-title-icon
           price-value price-period
           cancel-at
           benefits-title benefits
           cta-text cta-link
           cta-text-trial cta-link-trial
           cta-text-with-icon cta-link-with-icon
           code-action
           editors
           recommended
           show-button-cta
           inline-error]}]

  (let [has-trial? (and cta-link-trial cta-text-trial)
        has-cta-with-icon? (and cta-link-with-icon cta-text-with-icon)
        has-cta-button (and cta-link cta-text show-button-cta)
        has-cta-link (and cta-link cta-text (not show-button-cta))]
    [:div {:class (stl/css-case :plan-card true
                                :plan-card-highlight recommended)}
     [:div {:class (stl/css :plan-card-header)}
      [:div {:class (stl/css :plan-card-title-container)}
       (when card-title-icon
         [:span {:class (stl/css :plan-title-icon)}
          [:> icon* {:icon-id card-title-icon
                     :size "s"}]])
       [:h4 {:class (stl/css :plan-card-title)} card-title]
       (when recommended
         [:& badge-notification {:content (tr "subscription.settings.recommended")
                                 :size :small
                                 :is-focus true}])
       (when editors
         [:span {:class (stl/css :plan-editors)} (tr "subscription.settings.editors" editors)])]
      (when (and price-value price-period)
        [:div {:class (stl/css :plan-price)}
         [:span {:class (stl/css :plan-price-value)} price-value]
         [:span {:class (stl/css :plan-price-period)} " / " price-period]])
      (when cancel-at
        [:div {:class (stl/css :plan-cancel)}
         [:span {:class (stl/css :plan-cancel-date)} cancel-at]])]
     (when benefits-title
       [:h5 {:class (stl/css :benefits-title)} benefits-title])
     [:ul {:class (stl/css :benefits-list)}
      (for [benefit benefits]
        [:li {:key (dm/str benefit) :class (stl/css :benefit)} "- " benefit])]

     (when has-cta-button
       [:> button* {:variant "primary"
                    :type "button"
                    :class (stl/css-case :bottom-button (not has-trial?))
                    :on-click cta-link} cta-text])
     (when has-trial?
       [:button {:class (stl/css :cta-button :bottom-link)
                 :on-click cta-link-trial} cta-text-trial])
     (when has-cta-with-icon?
       [:button {:class (stl/css :cta-button :more-info)
                 :on-click cta-link-with-icon} cta-text-with-icon
        [:> icon* {:icon-id "open-link"
                   :size "s"}]])
     (when has-cta-link
       [:button {:class (stl/css-case :cta-button true
                                      :bottom-link (not (or has-trial? code-action)))
                 :on-click cta-link} cta-text])
     (when code-action
       [:button {:class (stl/css-case :cta-button true
                                      :activate-by-code (= code-action :activate)
                                      :renew-by-code (= code-action :renovate)
                                      :bottom-link (= code-action :renovate))
                 :on-click (cond
                             (= code-action :activate)
                             #(st/emit! (modal/show {:type :nitrate-code-activation}))
                             (= code-action :renovate)
                             #(st/emit! (modal/show :nitrate-code-activation {:renew? true})))}
        (if (= code-action :activate)
          (tr "subscription.settings.activate-by-code")
          (tr "nitrate.subscription.settings.renew-with-code"))])
     (when inline-error
       [:p {:class (stl/css :inline-error)} inline-error])]))

(defn- get-subscription-name [subscription-type subscribe-to-trial?]
  (if subscribe-to-trial?
    (if (= subscription-type "unlimited")
      (tr "subscription.settings.unlimited-trial")
      (tr "subscription.settings.enterprise-trial"))
    (case subscription-type
      "professional" (tr "subscription.settings.professional")
      "unlimited"    (tr "subscription.settings.unlimited")
      "enterprise"   (tr "subscription.settings.enterprise"))))

(mf/defc ^:private editors-section*
  [{:keys [editors]}]
  (let [show-editors-list* (mf/use-state false)
        show-editors-list  (deref show-editors-list*)
        handle-click       (mf/use-fn
                            (fn [event]
                              (dom/stop-propagation event)
                              (swap! show-editors-list* not)))]
    [:*
     [:p {:class (stl/css :editors-text)}
      (tr "subscription.settings.management.dialog.currently-editors-title" (c (count editors)))]
     [:button {:class (stl/css :cta-button :show-editors-button) :on-click handle-click}
      (tr "subscription.settings.management.dialog.editors")
      [:> icon* {:icon-id (if show-editors-list i/arrow-up i/arrow-down)
                 :class (stl/css :icon-dropdown)
                 :size "s"}]]
     (when show-editors-list
       [:*
        [:p {:class (stl/css :editors-text :editors-list-warning)}
         (tr "subscription.settings.management.dialog.editors-explanation")]
        [:ul {:class (stl/css :editors-list)}
         (for [editor editors]
           [:li {:key (dm/str (:id editor)) :class (stl/css :team-name)} "- " (:name editor)])]])]))

(defn- make-management-form-schema [min-editors]
  [:map {:title "SeatsForm"}
   [:min-members [::sm/number {:min min-editors
                               :max 9999}]]
   [:redirect-to-payment-details :boolean]])

(mf/defc subscribe-management-dialog
  {::mf/register modal/components
   ::mf/register-as :management-dialog}
  [{:keys [subscription-type current-subscription editors subscribe-to-trial]}]
  (let [unlimited-modal-step*
        (mf/use-state 1)

        unlimited-modal-step
        (deref unlimited-modal-step*)

        subscription-name
        (get-subscription-name subscription-type subscribe-to-trial)

        min-editors
        (if (seq editors) (count editors) 1)

        initial
        (mf/with-memo [min-editors]
          {:min-members min-editors
           :redirect-to-payment-details false})

        schema
        (mf/with-memo [min-editors]
          (make-management-form-schema min-editors))

        form
        (fm/use-form :schema schema :initial initial)

        submit-in-progress
        (mf/use-ref false)

        subscribe-to-unlimited
        (mf/use-fn
         (fn [min-members add-payment-details?]
           (when-not (mf/ref-val submit-in-progress)
             (mf/set-ref-val! submit-in-progress true)
             (let [return-url (-> (rt/get-current-href)
                                  (rt/encode-url))
                   href       (dm/str "payments/subscriptions/create?type=unlimited&show="
                                      add-payment-details? "&quantity="
                                      min-members "&returnUrl=" return-url)]
               (reset! form nil)
               (st/emit! (ev/event {::ev/name "create-trial-subscription"
                                    :type "unlimited"
                                    :quantity min-members})
                         (rt/nav-raw :href href))))))

        subscribe-to-enterprise
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "create-trial-subscription"
                                :type "enterprise"}))
           (let [return-url (-> (rt/get-current-href) (rt/encode-url))
                 href (dm/str "payments/subscriptions/create?type=enterprise&returnUrl=" return-url)]
             (st/emit! (rt/nav-raw :href href)))))

        handle-accept-dialog
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "open-subscription-management"
                                ::ev/origin "settings"
                                :section "subscription-management-modal"}))
           (let [current-href (rt/get-current-href)
                 returnUrl (js/encodeURIComponent current-href)
                 href (dm/str "payments/subscriptions/show?returnUrl=" returnUrl)]
             (st/emit! (rt/nav-raw :href href)))
           (modal/hide!)))

        handle-close-dialog
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "close-subscription-modal"}))
           (modal/hide!)))

        on-submit
        (mf/use-fn
         (mf/deps current-subscription unlimited-modal-step*)
         (fn [form]
           (let [clean-data  (get @form :clean-data)
                 min-members (get clean-data :min-members)
                 redirect?   (get clean-data :redirect-to-payment-details)]
             (if (or (contains? #{"unpaid" "canceled"} (:status current-subscription))
                     (= @unlimited-modal-step* 2))
               (subscribe-to-unlimited min-members redirect?)
               (swap! unlimited-modal-step* inc)))))

        on-add-payments-click
        (mf/use-fn
         (fn []
           (swap! form update :data assoc :redirect-to-payment-details true)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog}
       [:> icon* {:icon-id "close"
                  :size "m"}]]
      [:div {:class (stl/css :modal-title :subscription-title)}
       (if (= unlimited-modal-step 2)
         (tr "subscription.settings.management-dialog.step-2-title")
         (tr "subscription.settings.management.dialog.title" subscription-name))]

      [:div {:class (stl/css :modal-content)}
       (when (and (seq editors) (not= unlimited-modal-step 2))
         [:> editors-section* {:editors editors}])

       (when (and
              (or (and (= subscription-type "professional")
                       (contains? #{"unlimited" "enterprise"} (:type current-subscription)))
                  (and (= subscription-type "unlimited") (= (:type current-subscription) "enterprise")))
              (not (contains? #{"unpaid" "canceled"} (:status current-subscription)))
              (not subscribe-to-trial))
         [:div {:class (stl/css :modal-text)}
          (tr "subscription.settings.management.dialog.downgrade")])

       (if (and (= subscription-type "unlimited")
                (or subscribe-to-trial (contains? #{"unpaid" "canceled"} (:status current-subscription))))
         [:& fm/form {:on-submit on-submit
                      :class (stl/css :seats-form)
                      :form form}
          (when (= unlimited-modal-step 1)
            [:*
             [:div {:class (stl/css :editors-wrapper)}
              [:div {:class (stl/css :fields-row)}
               [:& fm/input {:type "number"
                             :name :min-members
                             :show-error false
                             :label ""
                             :class (stl/css :input-field)}]]
              [:div {:class (stl/css :editors-cost)}
               [:span {:class (stl/css :modal-text-medium)}
                (when (> (dm/get-in @form [:clean-data :min-members]) 25)
                  [:> i18n/tr-html*
                   {:class (stl/css :modal-text-cap)
                    :tag-name "span"
                    :content (tr "subscription.settings.management.dialog.price-month" "175")}])
                [:> i18n/tr-html*
                 {:class (stl/css-case :text-strikethrough (> (dm/get-in @form [:clean-data :min-members]) 25))
                  :tag-name "span"
                  :content (tr "subscription.settings.management.dialog.price-month"
                               (* 7 (or (dm/get-in @form [:clean-data :min-members]) 0)))}]]
               [:span {:class (stl/css :modal-text-medium)}
                (tr "subscription.settings.management.dialog.payment-explanation")]]]

             (when (dm/get-in @form [:errors :min-members])
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
                {:label (if (contains? #{"unpaid" "canceled"} (:status current-subscription))
                          (tr "subscription.settings.subscribe")
                          (tr "labels.continue"))
                 :class (stl/css :primary-button)}]]]])

          (when (= unlimited-modal-step 2)
            [:*
             [:p {:class (stl/css :modal-text-medium)}
              (tr "subscription.settings.management-dialog.step-2-description")]

             [:div {:class (stl/css :modal-footer)}
              [:div {:class (stl/css :action-buttons)}

               [:input
                {:class (stl/css :cancel-button)
                 :type "submit"
                 :value (tr "subscription.settings.management-dialog.step-2-skip-button")}]

               [:input
                {:class (stl/css :primary-button)
                 :type "submit"
                 :value (tr "subscription.settings.management-dialog.step-2-add-payment-button")
                 :on-click on-add-payments-click}]]]])]

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
                                (st/emit! (ev/event {::ev/name "subscription-success"}))
                                (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :subscription-success)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog}
       [:> icon* {:icon-id "close"
                  :size "m"}]]
      [:div {:class (stl/css :modal-success-content)}
       [:div {:class (stl/css :modal-start)}
        [:> raw-svg* {:id (if (= "light" (:theme profile)) "logo-subscription-light" "logo-subscription")}]]

       [:div {:class (stl/css :modal-end)}
        [:div {:class (stl/css :modal-title)}
         (tr "subscription.settings.success.dialog.title" subscription-name)]
        (when (not= subscription-name "professional")
          [:p {:class (stl/css :modal-text-large)}
           (tr "subscription.settings.success.dialog.thanks" subscription-name)])
        [:p {:class (stl/css :modal-text-large)}
         (tr "subscription.settings.success.dialog.description")]
        [:p {:class (stl/css :modal-text-large)}
         (tr "subscription.settings.success.dialog.footer")]

        [:div {:class (stl/css :success-action-buttons)}
         [:input
          {:class (stl/css :primary-button)
           :type "button"
           :value (tr "labels.close")
           :on-click handle-close-dialog}]]]]]]))

(mf/defc subscription-page*
  [{:keys [profile]}]
  (let [route           (mf/deref refs/route)
        authenticated?  (da/is-authenticated? profile)
        nitrate-license (:subscription profile)
        nitrate?        (dnt/is-valid-license? profile)

        params-subscription
        (-> route :params :query :subscription)

        show-trial-subscription-modal?
        (or (= params-subscription "subscription-to-penpot-unlimited")
            (= params-subscription "subscription-to-penpot-enterprise"))

        show-subscription-success-modal?
        (or (= params-subscription "subscribed-to-penpot-unlimited")
            (= params-subscription "subscribed-to-penpot-enterprise")
            (= params-subscription "subscribed-to-penpot-nitrate"))

        nitrate-toast-message
        (condp = params-subscription
          dnt/nitrate-checkout-finish-error-token (tr "subscription.error.nitrate.checkout-finish-failed")
          dnt/nitrate-checkout-cancelled-token    (tr "subscription.error.nitrate.checkout-cancelled")
          nil)

        nitrate-toast-level
        (cond
          (= params-subscription dnt/nitrate-checkout-cancelled-token) :info
          (some? nitrate-toast-message)                                :error)

        show-nitrate-start-error?
        (= params-subscription dnt/nitrate-checkout-error-token)

        nitrate-start-error*
        (mf/use-state false)

        nitrate-start-error?
        (deref nitrate-start-error*)

        nitrate-start-error-message
        (when nitrate-start-error?
          (tr "subscription.error.nitrate.checkout-failed"))

        success-modal-is-trial?
        (-> route :params :query :trial)

        subscription-editors
        (-> profile :props :subscription :editors)

        subscription
        (-> profile :props :subscription)

        subscription-type
        (if (and (contains? cf/flags :nitrate) nitrate?) (:type nitrate-license) (get-subscription-type subscription))

        subscription-is-trial?
        (= (:status subscription) "trialing")

        member-since
        (ct/format-inst (:created-at profile) "d MMMM, yyyy")

        subscribed-since
        (if nitrate?
          (ct/format-inst (:created-at nitrate-license) "d MMMM, yyyy")
          (ct/format-inst (:start-date subscription) "d MMMM, yyyy"))

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
         (mf/deps subscription-editors nitrate-license)
         (fn [subscription-type current-subscription]
           (st/emit! (ev/event {::ev/name "open-subscription-modal"
                                ::ev/origin "settings"}))
           (if (= subscription-type "nitrate")
             (st/emit! (dnt/show-nitrate-popup :nitrate-dialog {:nitrate-license nitrate-license}))
             (st/emit!
              (modal/show :management-dialog
                          {:subscription-type subscription-type
                           :current-subscription current-subscription
                           :editors subscription-editors :subscribe-to-trial (not (:type subscription))})))))

        open-contact-sales-modal
        (mf/use-fn
         (mf/deps nitrate-license)
         (fn [current-subscription subscription-type]
           (if (= current-subscription "unlimited")
             (st/emit! (dnt/show-nitrate-popup :nitrate-dialog {:nitrate-license nitrate-license :show-contact-sales-option true}))
             (st/emit! (modal/show :nitrate-contact-sales-dialog {:subscription-type subscription-type})))))

        open-cancel-contact-sales-modal
        (mf/use-fn
         (fn []
           (st/emit! (modal/show :nitrate-cancel-contact-sales-dialog {:email (:email profile)}))))

        connectivity*
        (mf/use-state nil)

        connectivity
        (deref connectivity*)]

    (mf/with-effect []
      (dom/set-html-title (tr "subscription.labels")))

    (mf/with-effect [nitrate?]
      (when nitrate?
        (->> (dnt/fetch-connectivity)
             (rx/subs! #(reset! connectivity* %)))))

    (mf/with-effect [authenticated?
                     show-subscription-success-modal?
                     show-trial-subscription-modal?
                     show-nitrate-start-error?
                     success-modal-is-trial?
                     nitrate-toast-message
                     nitrate-toast-level
                     subscription]
      (when ^boolean authenticated?
        (when ^boolean show-nitrate-start-error?
          (reset! nitrate-start-error* true))
        (cond
          (some? nitrate-toast-message)
          (st/emit!
           (ntf/show {:content nitrate-toast-message
                      :type :toast
                      :level nitrate-toast-level
                      :timeout 7000})
           (rt/nav :settings-subscription {} {::rt/replace true}))

          ^boolean show-nitrate-start-error?
          (st/emit! (rt/nav :settings-subscription {} {::rt/replace true}))

          ^boolean show-trial-subscription-modal?

          (st/emit!
           (ev/event {::ev/name "open-subscription-modal"
                      ::ev/origin "settings"})
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
           (if (= params-subscription "subscribed-to-penpot-nitrate")
             (modal/show :nitrate-activation-success {})
             (modal/show :subscription-success
                         {:subscription-name (if (= params-subscription "subscribed-to-penpot-unlimited")
                                               (if (= success-modal-is-trial? "true")
                                                 (tr "subscription.settings.unlimited-trial")
                                                 (tr "subscription.settings.unlimited"))
                                               (if (= success-modal-is-trial? "true")
                                                 (tr "subscription.settings.enterprise-trial")
                                                 (tr "subscription.settings.enterprise")))}))
           (rt/nav :settings-subscription {} {::rt/replace true})))))

    [:section {:class (stl/css :dashboard-section)}
     [:div {:class (stl/css :dashboard-content)}
      [:h2 {:class (stl/css :title-section)} (tr "subscription.labels")]


      [:div {:class (stl/css :your-subscription)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.section-plan")]
       (if nitrate?
         ;; TODO add translations for this texts when we have the definitive ones
         [:> plan-card* {:card-title "Business Nitrate"
                         :card-title-icon i/character-b
                         :cancel-at (when (:cancel-at nitrate-license)
                                      (tr "nitrate.subscription.active-until" (ct/format-inst (:cancel-at nitrate-license) "d MMMM, yyyy")))
                         :benefits-title "Loren ipsum",
                         :benefits ["Loren ipsum",
                                    "Loren ipsum",
                                    "Loren ipsum"]
                         :cta-text-with-icon (when (not (:manual nitrate-license)) "Admin Console")
                         :cta-link-with-icon (when (not (:manual nitrate-license)) dnt/go-to-nitrate-ac)
                         :cta-text (if (and (:licenses connectivity) (not (:manual nitrate-license)))
                                     (tr "subscription.settings.manage-your-subscription")
                                     (tr "nitrate.subscription.settings.manual-cancel"))
                         :cta-link (if (and (:licenses connectivity) (not (:manual nitrate-license)))
                                     dnt/go-to-nitrate-billing
                                     open-cancel-contact-sales-modal)
                         :code-action (when (:manual nitrate-license) :renovate)}]
         (case subscription-type
           "professional"
           [:> plan-card* {:card-title (tr "subscription.settings.professional")
                           :benefits [(if cf/saas?
                                        (tr "subscription.settings.professional.storage-benefit")
                                        (tr "subscription.settings.professional.selfhost.control-over-data")),
                                      (if cf/saas?
                                        (tr "subscription.settings.professional.autosave-benefit")
                                        (tr "subscription.settings.professional.selfhost.unlimited-users")),
                                      (if cf/saas?
                                        (tr "subscription.settings.professional.teams-editors-benefit")
                                        (tr "subscription.settings.professional.selfhost.community-support"))]}]

           "unlimited"
           (if subscription-is-trial?
             [:> plan-card* {:card-title (tr "subscription.settings.unlimited-trial")
                             :card-title-icon i/character-u
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
                             :card-title-icon i/character-u
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
                             :card-title-icon i/character-e
                             :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits"),
                             :benefits [(tr "subscription.settings.enterprise.unlimited-storage-benefit"),
                                        (tr "subscription.settings.enterprise.autosave"),
                                        (tr "subscription.settings.enterprise.capped-bill")]
                             :cta-text (tr "subscription.settings.manage-your-subscription")
                             :cta-link go-to-payments
                             :cta-text-trial (tr "subscription.settings.add-payment-to-continue")
                             :cta-link-trial go-to-payments}]
             [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                             :card-title-icon i/character-e
                             :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits"),
                             :benefits [(tr "subscription.settings.enterprise.unlimited-storage-benefit"),
                                        (tr "subscription.settings.enterprise.autosave"),
                                        (tr "subscription.settings.enterprise.capped-bill")]
                             :cta-text (tr "subscription.settings.manage-your-subscription")
                             :cta-link go-to-payments}])))

       [:div {:class (stl/css :membership-container)}
        (when (or nitrate?
                  (and subscribed-since (not= subscription-type "professional")))
          [:div {:class (stl/css :membership)}
           [:> icon* {:class (stl/css :subscription-member)
                      :icon-id "crown"
                      :size "m"}]
           [:span {:class (stl/css :membership-date)}
            (tr "subscription.settings.support-us-since" subscribed-since)]])

        [:div {:class (stl/css :membership)}
         [:> icon* {:class (stl/css :penpot-member)
                    :icon-id "user"
                    :size "m"}]
         [:span {:class (stl/css :membership-date)}
          (tr "subscription.settings.member-since" member-since)]]]]

      [:div {:class (stl/css :other-subscriptions)}
       [:h3 {:class (stl/css :plan-section-title)} (tr "subscription.settings.other-plans")]
       (when (not= subscription-type "professional")
         [:> plan-card* {:card-title (tr "subscription.settings.professional")
                         :price-value "$0"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits [(if cf/saas?
                                      (tr "subscription.settings.professional.storage-benefit")
                                      (tr "subscription.settings.professional.selfhost.control-over-data")),
                                    (if cf/saas?
                                      (tr "subscription.settings.professional.autosave-benefit")
                                      (tr "subscription.settings.professional.selfhost.unlimited-users")),
                                    (if cf/saas?
                                      (tr "subscription.settings.professional.teams-editors-benefit")
                                      (tr "subscription.settings.professional.selfhost.community-support"))]
                         :cta-text (tr "subscription.settings.subscribe")
                         :cta-link (if (and (contains? cf/flags :nitrate) nitrate? (= subscription-type "nitrate"))
                                     (if (and (:licenses connectivity) (not (:manual nitrate-license)))
                                       dnt/go-to-nitrate-billing
                                       open-cancel-contact-sales-modal)
                                     go-to-payments)
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page}])

       (when (and (not= subscription-type "unlimited") cf/saas?)
         [:> plan-card* {:card-title (tr "subscription.settings.unlimited")
                         :card-title-icon i/character-u
                         :price-value "$7"
                         :price-period (tr "subscription.settings.price-editor-month")
                         :benefits-title (tr "subscription.settings.benefits.all-professional-benefits")
                         :benefits [(tr "subscription.settings.unlimited.storage-benefit"),
                                    (tr "subscription.settings.unlimited.autosave-benefit"),
                                    (tr "subscription.settings.unlimited.bill")]
                         :cta-text (if (:type subscription) (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link (if (and (contains? cf/flags :nitrate) nitrate?) #(open-contact-sales-modal subscription-type "Unlimited") #(open-subscription-modal "unlimited" subscription))
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page
                         :recommended (= subscription-type "professional")
                         :show-button-cta (= subscription-type "professional")}])

       (when (and (not= subscription-type "enterprise") cf/saas? (not (contains? cf/flags :nitrate)))
         [:> plan-card* {:card-title (tr "subscription.settings.enterprise")
                         :card-title-icon i/character-e
                         :price-value "$950"
                         :price-period (tr "subscription.settings.price-organization-month")
                         :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                         :benefits [(tr "subscription.settings.enterprise.unlimited-storage-benefit"),
                                    (tr "subscription.settings.enterprise.autosave"),
                                    (tr "subscription.settings.enterprise.capped-bill")]
                         :cta-text (if (:type subscription) (tr "subscription.settings.subscribe") (tr "subscription.settings.try-it-free"))
                         :cta-link #(open-subscription-modal "enterprise" subscription)
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page
                         :show-button-cta (= subscription-type "professional")}])

       ;; TODO add translations for this texts when we have the definitive ones
       (when (and (contains? cf/flags :nitrate) (not nitrate?))
         [:> plan-card* {:card-title "Business Nitrate"
                         :card-title-icon i/character-n
                         :price-value "$25"
                         :price-period (tr "subscription.settings.organization-member-month")
                         :benefits-title (tr "subscription.settings.benefits.all-unlimited-benefits")
                         :benefits ["Crea organizaciones y añade personas, que usarán Penpot con las reglas que configures."
                                    "Acceso exclusivo a la Admin Console"
                                    "Lorem ipsum"]
                         :cta-text (if nitrate-license (tr "subscription.settings.subscribe") "Try 14 days for free")
                         :cta-link (if (= subscription-type "unlimited") #(open-contact-sales-modal subscription-type "Nitrate") #(open-subscription-modal "nitrate" subscription))
                         :cta-text-with-icon (tr "subscription.settings.more-information")
                         :cta-link-with-icon go-to-pricing-page
                         :code-action :activate
                         :show-button-cta (not nitrate-license)
                         :inline-error nitrate-start-error-message}])]]]))


(def ^:private schema:nitrate-form
  [:map {:title "NitrateForm"}
   [:subscription [::sm/one-of #{:monthly :yearly}]]])

(mf/defc subscribe-nitrate-dialog
  {::mf/register modal/components
   ::mf/register-as :nitrate-dialog}
  [{:keys [nitrate-license show-contact-sales-option] :as connectivity}]
  ;; TODO add translations for this texts when we have the definitive ones
  (let [online? (:licenses connectivity)
        initial (mf/with-memo []
                  {:subscription "yearly"})
        form     (fm/use-form :schema schema:nitrate-form
                              :initial initial)

        handle-close-dialog
        (mf/use-fn
         (fn []
           (modal/hide!)))

        on-submit
        (mf/use-fn
         (mf/deps form)
         (fn []
           (let [subscription (-> @form :clean-data :subscription name)]
             (dnt/go-to-buy-nitrate-license subscription (rt/get-current-href)))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog}
       [:> icon* {:icon-id "close"
                  :size "m"}]]
      [:div {:class (stl/css :modal-title :subscription-title)}
       "Subcribe to the Business Nitrate plan"]

      (if (and online? (not show-contact-sales-option))
        [:div {:class (stl/css :modal-content)}



         [:div {:class (stl/css :modal-text)}
          "Lorem ipsum lorem ipsum:"]


         [:& fm/form {:on-submit on-submit
                      :class (stl/css :seats-form)
                      :form form}

          [:*
           [:div {:class (stl/css :editors-wrapper)}
            [:div {:class (stl/css :fields-row)}
             [:& fm/radio-buttons
              {:options [{:label "Price Tag Yearly (Discount)" :value "yearly"}
                         {:label "Price Tag Montly" :value "monthly"}]
               :name :subscription
               :class (stl/css :radio-btns)}]]]
           [:div {:class (stl/css :modal-text)}
            "You won’t be charged right now. Payment will be processed at the end of the trial. Cancel anytime."]



           [:div {:class (stl/css :modal-footer)}
            [:div {:class (stl/css :action-buttons)}
             [:input
              {:class (stl/css :cancel-button)
               :type "button"
               :value (tr "ds.confirm-cancel")
               :on-click handle-close-dialog}]

             [:> fm/submit-button*
              {:label (if nitrate-license (tr "subscription.settings.subscribe") "TRY 14 DAYS FOR FREE")
               :class (stl/css :primary-button)}]]]]]]
        [:div {:class (stl/css :modal-content :modal-contact-content)}
         [:div {:class (stl/css :modal-text)}
          "Lorem ipsum lorem ipsum Lorem ipsum lorem ipsum Lorem ipsum lorem ipsum"]
         [:div {:class (stl/css :modal-text)}
          (if nitrate-license "Contact us to upgrade to Nitrate:" "Contact us to try Nitrate for 14 days:")]
         [:div {:class (stl/css :modal-text)}
          [:a {:class (stl/css :cta-button) :href "mailto:sales@penpot.app"}
           "sales@penpot.app"]]])]]))

(mf/defc nitrate-contact-sales-dialog
  {::mf/register modal/components
   ::mf/register-as :nitrate-contact-sales-dialog}
  [{:keys [subscription-type]}]
  (let [handle-close-dialog
        (mf/use-fn
         (fn []
           (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog}
       [:> icon* {:icon-id "close"
                  :size "m"}]]
      [:div {:class (stl/css :modal-title :subscription-title)}
       (dm/str "Switch to " subscription-type " plan?")]
      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-text-medium)}
        "When you downgrade:"]
       [:ul {:class (stl/css :downgrade-list)}
        [:li {:class (stl/css :downgrade-item)} "Your organization will be deleted."]
        [:li {:class (stl/css :downgrade-item)} "The teams, projects and files will no longer be part of any organization but they will remain available."]
        [:li {:class (stl/css :downgrade-item)} "Your total storage, auto-version history, and file recovery period will be limited."]]

       [:div {:class (stl/css :downgrade-warning)}
        "To switch to this plan, please contact our sales team.
We’ll help you update your subscription and ensure everything is set up correctly."]
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:variant "secondary"
                     :type "button"
                     :on-click handle-close-dialog} (tr "ds.confirm-cancel")]
        [:> button* {:variant "primary"
                     :type "button"
                     :on-click #(dom/open-new-window "mailto:sales@penpot.app?subject=Switch%20to%20the%20Unlimited%20plan")} "Contact sales"]]]]]))

(mf/defc nitrate-cancel-contact-sales-dialog
  {::mf/register modal/components
   ::mf/register-as :nitrate-cancel-contact-sales-dialog}
  [{:keys [email]}]
  (let [encoded-email
        (js/encodeURIComponent email)

        mailto-url
        (dm/str "mailto:sales@penpot.net"
                "?subject=Request%20to%20Cancel%20Nitrate%20Subscription"
                "&body=Hello%2C%0A%0A"
                "I%20would%20like%20to%20cancel%20my%20Enterprise%20subscription.%0A"
                "Account%20email%3A%20" encoded-email ".%0A%0AThank%20you.")

        handle-close-dialog
        (mf/use-fn
         (fn []
           (modal/hide!)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog}
       [:> icon* {:icon-id "close"
                  :size "m"}]]
      [:div {:class (stl/css :modal-title :subscription-title)}
       "Cancel subscription"]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-text-medium)}
        "To cancel your Nitrate subscription, please contact us at:"]
       [:a {:class (stl/css :cta-link) :href "mailto:sales@penpot.net"}
        "sales@penpot.net"]
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:class (stl/css :button-full-width)
                     :variant "primary"
                     :type "button"
                     :on-click #(dom/open-new-window mailto-url)} "Contact us"]]]]]))
