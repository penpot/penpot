(ns app.main.ui.dashboard.subscription-sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc cta-power-up*
  {::mf/props :obj}
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
  {::mf/props :obj}
  [{:keys [go-to-subscription]}]
  (let [;; TODO subscription cases professional/unlimited/enterprise
        subscription-name :unlimited
        subscription-is-trial false]
    (case subscription-name
      :professional
      [:> cta-power-up*
       {:top-title (tr "subscription.dashboard.power-up.professional.top-title")
        :top-description (tr "dashboard.upgrade-plan.no-limits")
        :bottom-description (tr "subscription.dashboard.power-up.professional.bottom-description")
        :cta-text (tr "dashboard.upgrade-plan.power-up")
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