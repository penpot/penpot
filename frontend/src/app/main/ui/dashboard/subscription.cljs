;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu-item*]]
   [app.main.ui.ds.product.cta :refer [cta*]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [lambdaisland.uri :as u]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn get-subscription-type
  [{:keys [type status] :as subscription}]
  (if (and subscription (not (contains? #{"unpaid" "canceled"} status)))
    type
    "professional"))

(mf/defc cta-power-up*
  [{:keys [top-title top-description bottom-description has-dropdown]}]
  (let [show-data* (mf/use-state false)
        show-data (deref show-data*)
        handle-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-data* not)))]

    [:div {:class (stl/css :cta-power-up)
           :on-click handle-click}
     [:button {:class (stl/css-case :cta-top-section true
                                    :cta-without-dropdown (not has-dropdown))}
      [:div {:class (stl/css :content)}
       [:span {:class (stl/css :cta-title)} top-title]
       [:span {:class (stl/css :cta-text) :data-testid "subscription-name"} top-description]]
      (when has-dropdown [:span {:class (stl/css :icon-dropdown)}  i/arrow])]

     (when (and has-dropdown show-data)
       [:div {:class (stl/css :cta-bottom-section)}
        [:> i18n/tr-html* {:content bottom-description
                           :class (stl/css :content)
                           :tag-name "span"}]])]))

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
        :bottom-description (tr "subscription.dashboard.power-up.professional.bottom", subscription-href)
        :has-dropdown true}]

      "unlimited"
      (if subscription-is-trial
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.trial.top-title")
          :bottom-description (tr "subscription.dashboard.power-up.trial.bottom-description", subscription-href)
          :has-dropdown true}]

        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.unlimited-plan")
          :bottom-description (tr "subscription.dashboard.power-up.unlimited.bottom-text", subscription-href)
          :has-dropdown true}])

      "enterprise"
      (if subscription-is-trial
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.enterprise-trial.top-title")
          :has-dropdown false}]
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.your-subscription")
          :top-description (tr "subscription.dashboard.power-up.enterprise-plan")
          :has-dropdown false}]))))

(mf/defc team*
  [{:keys [is-owner team]}]
  (let [subscription          (:subscription team)
        subscription-type     (get-subscription-type subscription)
        subscription-is-trial (= "trialing" (:status subscription))

        go-to-manage-subscription
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "open-subscription-management"
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
  [:span {:class (stl/css :subscription-icon)
          :title (if (= subscription-type "unlimited")
                   (tr "subscription.dashboard.power-up.unlimited-plan")
                   (tr "subscription.dashboard.power-up.enterprise-plan"))
          :data-testid "subscription-icon"}
   (case subscription-type
     "unlimited" i/character-u
     "enterprise" i/character-e)])

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
  [{:keys [banner-is-expanded team]}]
  (let [subscription          (:subscription team)
        subscription-type     (get-subscription-type subscription)
        is-owner              (-> team :permissions :is-owner)

        email-owner           (:email (some #(when (:is-owner %) %) (:members team)))
        mail-to-owner         (str "<a href=\"" "mailto:" email-owner "\">" email-owner "</a>")
        go-to-subscription    (dm/str (u/join cf/public-uri "#/settings/subscriptions"))
        seats                 (or (:seats subscription) 0)
        editors               (count (filterv :can-edit (:members team)))

        link
        (if is-owner
          go-to-subscription
          mail-to-owner)

        cta-title
        (cond
          (and (= "professional" subscription-type) (>= editors 8))
          (tr "subscription.dashboard.cta.professional-plan-designed")

          (and (= "unlimited" subscription-type) (< seats editors))
          (tr "subscription.dashboard.cta.unlimited-many-editors" seats editors))

        cta-message
        (cond
          (and (= "professional" subscription-type) (>= editors 8) is-owner)
          (tr "subscription.dashboard.cta.upgrade-to-unlimited-enterprise-owner" link)

          (and (= "professional" subscription-type) (>= editors 8) (not is-owner))
          (tr "subscription.dashboard.cta.upgrade-to-unlimited-enterprise-member" link)

          (and (= "unlimited" subscription-type) (< seats editors) is-owner)
          (tr "subscription.dashboard.cta.upgrade-more-seats-owner" link)

          (and (= "unlimited" subscription-type) (< seats editors) (not is-owner))
          (tr "subscription.dashboard.cta.upgrade-more-seats-member" link))]

    [:> cta* {:class (stl/css-case ::members-cta-full-width banner-is-expanded :members-cta (not banner-is-expanded)) :title cta-title}
     [:> i18n/tr-html*
      {:tag-name "span"
       :class (stl/css :cta-message)
       :content cta-message}]]))

(defn show-subscription-members-main-banner?
  [team]
  (let [subscription-type (get-subscription-type (:subscription team))
        seats             (-> team :subscription :seats)
        editors           (count (filter :can-edit (:members team)))]
    (or
     (and (= subscription-type "professional")
          (> editors 8))
     (and
      (= subscription-type "unlimited")
      (>= editors 8)
      (< seats editors)))))

(defn show-subscription-members-small-banner?
  [team]
  (let [subscription-type (get-subscription-type (:subscription team))
        seats   (-> team :subscription :seats)
        editors (count (filterv :can-edit (:members team)))]
    (or
     (and (= subscription-type "professional")
          (= editors 8))
     (and (= subscription-type "unlimited")
          (< editors 8)
          (< seats editors)))))
