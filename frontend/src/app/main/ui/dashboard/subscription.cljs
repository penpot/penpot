;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.subscription
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu-item*]]
   [app.main.ui.ds.product.cta :refer [cta*]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc cta-power-up*
  [{:keys [top-title top-description bottom-description cta-text cta-link has-dropdown]}]
  (let [show-data* (mf/use-state false)
        show-data (deref show-data*)
        handle-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-data* not)))]

    [:div {:class (stl/css :cta-power-up)
           :on-click handle-click}
     [:button {:class (stl/css :cta-top-section)}
      [:div {:class (stl/css :content)}
       [:span {:class (stl/css :cta-title)} top-title]
       [:span {:class (stl/css :cta-text)} top-description]]
      (when has-dropdown [:span {:class (stl/css :icon-dropdown)}  i/arrow])]

     (when (and has-dropdown show-data)
       [:div {:class (stl/css :cta-bottom-section)}
        [:> i18n/tr-html* {:content bottom-description
                           :class (stl/css :content)
                           :tag-name "button"}]
        [:button {:class (stl/css :cta-highlight :cta-link) :on-click cta-link}
         cta-text]])]))

(mf/defc subscription-sidebar*
  []
  (let [;; TODO subscription cases professional/unlimited/enterprise
        subscription-name :unlimited
        subscription-is-trial false

        go-to-subscription
        (mf/use-fn #(st/emit! (rt/nav :settings-subscription)))]

    (case subscription-name
      :professional
      [:> cta-power-up*
       {:top-title (tr "subscription.dashboard.power-up.professional.top-title")
        :top-description (tr "dashboard.upgrade-plan.no-limits")
        :bottom-description (tr "subscription.dashboard.power-up.professional.bottom-description")
        :cta-text (tr "subscription.dashboard.upgrade-plan.power-up")
        :cta-link go-to-subscription
        :has-dropdown true}]

      :unlimited
      (if subscription-is-trial
        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.trial.top-title")
          :top-description (tr "subscription.dashboard.power-up.trial.top-description")
          :bottom-description (tr "subscription.dashboard.power-up.trial.bottom-description")
          :cta-text (tr "subscription.dashboard.power-up.subscribe")}]

        [:> cta-power-up*
         {:top-title (tr "subscription.dashboard.power-up.unlimited-plan")
          :top-description (tr "subscription.dashboard.power-up.unlimited.top-description")
          :bottom-description (tr "subscription.dashboard.power-up.unlimited.bottom-description")
          :cta-text (tr "subscription.dashboard.power-up.unlimited.cta")
          :cta-link go-to-subscription
          :has-dropdown true}])

      :enterprise
      [:> cta-power-up*
       {:top-title (tr "subscription.dashboard.power-up.enterprise-plan")
        :top-description (tr "subscription.dashboard.power-up.enterprise.description")
        :has-dropdown false}])))

(mf/defc team*
  [{:keys [is-owner]}]
  (let [;; TODO subscription cases professional/unlimited/enterprise
        subscription-name :unlimited
        subscription-is-trial false
        go-to-manage-subscription
        (mf/use-fn
         (fn []
           ;; TODO add event tracking and update url to penpot payments
           (dom/open-new-window "https://penpot.app/pricing")))]

    [:div {:class (stl/css :team)}
     [:div {:class (stl/css :team-label)}
      (tr "subscription.dashboard.team-plan")]
     [:span {:class (stl/css :team-text)}
      (case subscription-name
        :professional (tr "subscription.settings.professional")
        :unlimited (if subscription-is-trial (tr "subscription.settings.unlimited-trial") (tr "subscription.settings.unlimited"))
        :enterprise (tr "subscription.settings.enterprise"))]
     (when is-owner [:button {:class (stl/css :manage-subscription-link) :on-click go-to-manage-subscription}
                     (tr "subscription.settings.manage-your-subscription")])]))

(mf/defc menu-team-icon*
  [{:keys [subscription-name]}]
  [:span {:class (stl/css :subscription-icon)}
   (case subscription-name
     :unlimited i/character-u
     :enterprise i/character-e)])

(mf/defc main-menu-power-up*
  [{:keys [close-sub-menu]}]
  (let [on-power-up-click
        (mf/use-fn
         (fn []
           ;; TODO update url to penpot payments
           (dom/open-new-window "https://penpot.app/pricing")))]
    [:> dropdown-menu-item* {:class (stl/css-case :menu-item true)
                             :on-click    on-power-up-click
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (on-power-up-click)))
                             :on-pointer-enter close-sub-menu
                             :id          "file-menu-power-up"}
     [:span {:class (stl/css :item-name)} (tr "subscription.workspace.header.menu.option.power-up")]]))

(mf/defc members-cta*
  [{:keys [banner-is-expanded]}]
  (let [;; TODO subscription cases professional/unlimited/enterprise -> it should come from team
        subscription-name :professional
        team    (mf/deref refs/team)
        is-owner (:is-owner (:permissions team))
        email-owner (:email (some #(when (:is-admin %) %) (:members team)))
        mail-to-owner (str "<a href=\"" "mailto:" email-owner "\">" email-owner "</a>")
        go-to-subscription (mf/use-fn #(st/emit! (rt/nav :settings-subscription)))
        link (if is-owner
               go-to-subscription
               mail-to-owner)
        cta-title (cond
                    (= :professional subscription-name) (tr "subscription.dashboard.cta.professional-plan-designed")
                    (= :trial subscription-name) (tr "subscription.dashboard.cta.trial-plan-designed"))

        cta-message (cond
                      (and (= :professional subscription-name) is-owner) (tr "subscription.dashboard.cta.upgrade-to-unlimited-enterprise-owner"
                                                                             link)
                      (and (= :professional subscription-name) (not is-owner)) (tr "subscription.dashboard.cta.upgrade-to-unlimited-enterprise-member"
                                                                                   link)
                      (and (= :trial subscription-name) is-owner) (tr "subscription.dashboard.cta.upgrade-to-full-access-owner"
                                                                      link)
                      (and (= :trial subscription-name) (not is-owner)) (tr "subscription.dashboard.cta.upgrade-to-full-access-member"
                                                                            link))]

    [:> cta* {:class (stl/css-case ::members-cta-full-width banner-is-expanded :members-cta (not banner-is-expanded)) :title cta-title}
     [:> i18n/tr-html*
      {:tag-name "span"
       :class (stl/css :cta-message)
       :content cta-message}]]))
