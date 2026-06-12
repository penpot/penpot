;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.nitrate :as dnt]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu-item*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.product.cta :refer [cta*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [lambdaisland.uri :as u]
   [rumext.v2 :as mf]))

(defn get-subscription-type
  [{:keys [type status] :as subscription}]
  (if (and subscription (:type subscription) (not (contains? #{"unpaid" "canceled"} status)))
    type
    "professional"))

(mf/defc cta-power-up*
  [{:keys [top-title top-description bottom-description bottom-button bottom-button-href has-dropdown is-highlighted]}]
  (let [show-data* (mf/use-state false)
        show-data (deref show-data*)
        handle-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-data* not)))
        handle-navigation
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (rt/nav-raw :href bottom-button-href))))]

    [:div {:class (stl/css-case :cta-power-up true
                                :highlighted is-highlighted)
           :on-click handle-click}
     [:button {:class (stl/css-case :cta-top-section true
                                    :cta-without-dropdown (not has-dropdown))}
      [:div {:class (stl/css :content)}
       [:span {:class (stl/css :cta-title)} top-title]
       [:span {:class (stl/css :cta-text) :data-testid "subscription-name"} top-description]]
      (when has-dropdown
        [:> icon* {:icon-id (if (and has-dropdown show-data) i/arrow-up i/arrow-down)
                   :class (stl/css :icon-dropdown)
                   :size "s"}])]

     (when (and has-dropdown show-data)
       [:div {:class (stl/css :cta-bottom-section)}
        [:> i18n/tr-html* {:content bottom-description
                           :class (stl/css :content)
                           :tag-name "span"}]])

     (when (and bottom-description bottom-button)
       [:div {:class (stl/css :cta-bottom-section)}
        [:span {:class (stl/css :content)}
         bottom-description]
        [:> button* {:variant "primary"
                     :type "button"
                     :class (stl/css :cta-bottom-button)
                     :on-click handle-navigation} bottom-button]])]))

(mf/defc subscription-sidebar*
  [{:keys [profile]}]
  (let [subscription           (:subscription (:props profile))
        subscription-type      (get-subscription-type subscription)
        subscription-is-trial  (= (:status subscription) "trialing")
        subscription-href      (dm/str (u/join cf/public-uri "#/settings/subscriptions"))]

    (case subscription-type
      "professional"
      [:> cta-power-up*
       {:top-title (tr "subscription.dashboard.power-up.your-subscription")
        :top-description (tr "subscription.dashboard.power-up.professional.top-title")
        :bottom-description (tr "subscription.dashboard.power-up.professional.bottom-description")
        :bottom-button (tr "subscription.dashboard.power-up.professional.bottom-button")
        :bottom-button-href subscription-href
        :has-dropdown false
        :is-highlighted true}]

      "unlimited"
      (if subscription-is-trial
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.trial.top-title")
          :bottom-description (tr "subscription.dashboard.power-up.trial.bottom-description" subscription-href)
          :has-dropdown true
          :is-highlighted false}]

        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.unlimited-plan")
          :bottom-description (tr "subscription.dashboard.power-up.unlimited.bottom-text" subscription-href)
          :has-dropdown true
          :is-highlighted false}])

      "enterprise"
      (if subscription-is-trial
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.enterprise-trial.top-title")
          :has-dropdown false
          :is-highlighted false}]
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.enterprise-plan")
          :has-dropdown false
          :is-highlighted false}]))))

(mf/defc nitrate-sidebar*
  [{:keys [profile teams]}]
  (let [nitrate? (dnt/is-valid-license? profile)
        nitrate-license (:subscription profile)
        manual-license? (:manual nitrate-license)
        subscription-warning* (mf/use-state nil)
        subscription-warning (deref subscription-warning*)
        days-until-expiry (or (:days-until-expiry subscription-warning)
                              (:daysUntilExpiry subscription-warning)
                              (:days-from-expiry subscription-warning)
                              (:daysFromExpiry subscription-warning))
        expiration-date (or (:expiration-date subscription-warning)
                            (:expirationDate subscription-warning))
        expiration-date-text (when expiration-date
                               (ct/format-inst expiration-date "MMMM d"))
        show-subscription-warning? (and manual-license?
                                        (some? days-until-expiry)
                                        (some? expiration-date-text))
        subscription-type (if nitrate? (:type nitrate-license) (get-subscription-type (-> profile :props :subscription)))
        teams-loaded? (seq teams)
        no-orgs-created? (mf/with-memo [teams]
                           (and (seq teams)
                                (->> teams
                                     vals
                                     (not-any? :organization))))

        handle-click
        (mf/use-fn
         (mf/deps nitrate-license subscription-type)
         (fn []
           (if (= subscription-type "unlimited")
             (st/emit! (dnt/show-nitrate-popup :nitrate-dialog {:nitrate-license nitrate-license :show-contact-sales-option true}))
             (st/emit! (dnt/show-nitrate-popup :nitrate-form)))))

        handle-go-to-cc
        (mf/use-fn dnt/go-to-nitrate-ac-create-org)

        handle-go-to-subscription
        (mf/use-fn #(st/emit! (rt/nav :settings-subscription)))]

    (mf/with-effect [manual-license?]
      (if manual-license?
        (->> (dnt/fetch-subscription-warning)
             (rx/subs! #(reset! subscription-warning* %)))
        (reset! subscription-warning* nil)))

    [:*
     ;; TODO add translations for this texts when we have the definitive ones
     (if (and nitrate? teams-loaded? no-orgs-created? (not show-subscription-warning?))
       ;; Banner for users with active nitrate license but no organizations created
       [:div {:class (stl/css :nitrate-banner :highlighted)}
        [:div {:class (stl/css :nitrate-content)}
         [:span {:class (stl/css :nitrate-title)} (tr "subscription.banner.see-enterprise")]]
        [:div {:class (stl/css :nitrate-content)}
         [:span {:class (stl/css :nitrate-info)} (tr "subscription.banner.create-org-info")]
         [:> button* {:variant "primary"
                      :type "button"
                      :class (stl/css :nitrate-bottom-button)
                      :on-click handle-go-to-cc} (tr "nitrate.activation-success.create-org")]]]

       ;; Banner for users without nitrate license
       (when (not nitrate?)
         [:div {:class (stl/css :nitrate-banner :highlighted)}
          [:div {:class (stl/css :nitrate-content)}
           [:span {:class (stl/css :nitrate-title)} (tr "subscription.dashboard.banner.unlock-features")]]
          [:div {:class (stl/css :nitrate-content)}
           [:span {:class (stl/css :nitrate-info)} (tr "subscription.dashboard.banner.unlock-features-description")]
           [:> button* {:variant "primary"
                        :type "button"
                        :class (stl/css :nitrate-bottom-button)
                        :on-click handle-click} (if (:subscription profile)
                                                  (tr "subscription.dashboard.banner.upgrade-nitrate")
                                                  (tr "nitrate.form.try-free"))]]]))

     ;; Banner for users with nitrate license almost expired or expired
     (when show-subscription-warning?
       [:div {:class (stl/css :nitrate-banner :highlighted)}
        [:div {:class (stl/css :nitrate-content)}
         [:span {:class (stl/css :nitrate-title)}
          (tr "subscription.dashboard.banner.renew-subscription")]]
        [:div {:class (stl/css :nitrate-content)}
         [:span {:class (stl/css :nitrate-info)}
          (if (neg? days-until-expiry)
            (tr "subscription.dashboard.banner.subscription-expired"
                expiration-date-text)
            (tr "subscription.dashboard.banner.subscription-expire-days"
                days-until-expiry
                expiration-date-text))]
         [:> button* {:variant "primary"
                      :type "button"
                      :class (stl/css :nitrate-bottom-button)
                      :on-click handle-go-to-subscription}
          (tr "subscription.dashboard.banner.renew")]]])]))

(mf/defc nitrate-current-plan*
  [{:keys [profile]}]
  (let [nitrate?              (dnt/is-valid-license? profile)
        nitrate-license       (:subscription profile)
        subscription          (-> profile :props :subscription)
        subscription-type     (if nitrate? (:type nitrate-license) (get-subscription-type subscription))
        subscription-is-trial (= "trialing" (:status (if nitrate? nitrate-license subscription)))
        go-to-subscription    (mf/use-fn #(st/emit! (rt/nav :settings-subscription)))]
    [:div {:class (stl/css :nitrate-current-plan)}
     [:div {:class (stl/css :nitrate-current-plan-label)}
      (tr "subscription.current-plan.title")]
     [:button {:class (stl/css :nitrate-current-plan-text)
               :type "button"
               :on-click go-to-subscription}
      (case subscription-type
        "professional" (tr "subscription.current-plan.professional")
        "unlimited" (if subscription-is-trial
                      (tr "subscription.current-plan.unlimited-trial")
                      (tr "subscription.current-plan.unlimited"))
        "nitrate" (if subscription-is-trial
                    (tr "subscription.current-plan.nitrate-trial")
                    (tr "subscription.current-plan.nitrate"))
        "enterprise" (tr "subscription.current-plan.enterprise"))]]))

(mf/defc team*
  [{:keys [is-owner team]}]
  (let [subscription          (:subscription team)
        subscription-type     (get-subscription-type subscription)
        subscription-is-trial (= "trialing" (:status subscription))

        go-to-manage-subscription
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "open-subscription-management"
                                ::ev/origin "dashboard"
                                :section "team-settings"}))
           (let [href (-> (rt/get-current-href)
                          (rt/encode-url))
                 href (str "payments/subscriptions/show?returnUrl=" href)]
             (st/emit! (rt/nav-raw :href href)))))]

    [:div {:class (stl/css :team)}
     [:div {:class (stl/css :team-label)}
      (tr "subscription.dashboard.team-plan")]
     [:span {:class (stl/css :team-text)}
      (case subscription-type
        "professional" (tr "subscription.settings.professional")
        "unlimited" (if subscription-is-trial
                      (tr "subscription.settings.unlimited-trial")
                      (tr "subscription.settings.unlimited"))

        "enterprise" (tr "subscription.settings.enterprise"))]
     (when (and is-owner (not= subscription-type "professional"))
       [:button {:class (stl/css :manage-subscription-link)
                 :on-click go-to-manage-subscription
                 :data-testid "manage-subscription-link"}
        (tr "subscription.settings.manage-your-subscription")])]))

(mf/defc menu-team-icon*
  [{:keys [subscription-type]}]
  [:span {:class (stl/css :subscription-icon-wrapper)}
   [:> icon* {:icon-id (case subscription-type
                         "unlimited" i/character-u
                         "enterprise" i/character-e)
              :class (stl/css :subscription-icon)
              :size "s"
              :title (if (= subscription-type "unlimited")
                       (tr "subscription.dashboard.power-up.unlimited-plan")
                       (tr "subscription.dashboard.power-up.enterprise-plan"))
              :data-testid "subscription-icon"}]])

(mf/defc main-menu-power-up*
  [{:keys [close-sub-menu]}]
  (let [go-to-subscription    (mf/use-fn #(st/emit! (rt/nav :settings-subscription)))]
    [:> dropdown-menu-item* {:class (stl/css-case :menu-item true)
                             :on-click    go-to-subscription
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (go-to-subscription)))
                             :on-pointer-enter close-sub-menu
                             :id          "file-menu-power-up"}
     [:span {:class (stl/css :item-name)} (tr "subscription.workspace.header.menu.option.power-up")]]))

(mf/defc members-cta*
  []
  [:> cta* {:class (stl/css :members-cta)
            :title (tr "subscription.dashboard.unlimited-members-extra-editors-cta-title")}
   [:> i18n/tr-html*
    {:tag-name "span"
     :class (stl/css :cta-message)
     :content (tr "subscription.dashboard.unlimited-members-extra-editors-cta-text")}]])

(mf/defc dashboard-cta*
  [{:keys [profile]}]
  (let [subscription          (-> profile :props :subscription)
        subscription-type     (get-subscription-type subscription)
        go-to-subscription    (dm/str (u/join cf/public-uri "#/settings/subscriptions"))
        seats                 (:quantity subscription)
        editors               (count (:editors subscription))
        cta-title
        (cond
          (= "professional" subscription-type)
          (tr "subscription.dashboard.professional-dashboard-cta-title" editors)

          (= "unlimited" subscription-type)
          (tr "subscription.dashboard.unlimited-dashboard-cta-title" seats editors))

        cta-message
        (cond
          (= "professional" subscription-type)
          (tr "subscription.dashboard.professional-dashboard-cta-upgrade-owner" go-to-subscription)

          (= "unlimited" subscription-type)
          (tr "subscription.dashboard.unlimited-dashboard-cta-upgrade-owner" go-to-subscription))]

    [:> cta* {:class (stl/css :dashboard-cta) :title cta-title}
     [:> i18n/tr-html*
      {:tag-name "span"
       :class (stl/css :cta-message)
       :content cta-message}]]))

(defn show-subscription-dashboard-banner?
  [profile]
  (let [subscription      (-> profile :props :subscription)
        subscription-type (get-subscription-type subscription)
        seats             (:quantity subscription)
        editors           (count (:editors subscription))]

    (or
     (and (= subscription-type "professional")
          (> editors 8))
     (and
      (= subscription-type "unlimited")
      (or
       ;; common: seats < 25 and diff >= 4
       (and (< seats 25)
            (>= (- editors seats) 4))
       ;; special: reached 25+ editors, seats < 25 and there is overuse
       (and (< seats 25)
            (>= editors 25)
            (> editors seats)))))))

(defn show-subscription-members-banner?
  [team profile]
  (let [subscription      (:subscription team)
        subscription-type (get-subscription-type subscription)
        seats             (:seats subscription)
        editors           (count (-> profile :props :subscription :editors))
        is-owner          (-> team :permissions :is-owner)]
    (and
     is-owner
     (= subscription-type "unlimited")
     ;; common: seats < 25 and diff >= 4 between editors/seats and there is overuse
     (and (< seats 25)
          (>= (- editors seats) 4)))))
