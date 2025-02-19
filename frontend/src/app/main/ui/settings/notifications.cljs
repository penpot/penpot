;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.notifications
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.main.data.profile :as dp]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def default-notification-settings
  {:dashboard-comments :all
   :email-comments :partial
   :email-invites :all})

(defn- on-submit
  [form _event]
  (let [params (:clean-data @form)]
    (st/emit! (dp/update-notifications params))))

(def ^:private schema:notifications-form
  [:map {:title "NotificationsForm"}
   [:dashboard-comments [::sm/one-of #{:all :partial :none}]]
   [:email-comments [::sm/one-of #{:all :partial :none}]]
   [:email-invites [::sm/one-of #{:all :partial :none}]]])

(mf/defc notifications-page*
  [{:keys [profile]}]
  (let [settings (mf/with-memo [profile]
                   (-> (merge default-notification-settings
                              (-> profile :props :notifications))
                       (update-vals d/name)))
        form     (fm/use-form :schema schema:notifications-form
                              :initial settings)]

    (mf/with-effect []
      (dom/set-html-title (tr "title.settings.notifications")))

    [:section {:class (stl/css :notifications-page)}
     [:& fm/form {:class (stl/css :notifications-form)
                  :on-submit on-submit
                  :form form}
      [:div {:class (stl/css :form-container)}
       [:h2 (tr "dashboard.settings.notifications.title")]
       [:h3 (tr "dashboard.settings.notifications.dashboard.title")]
       [:h4 (tr "dashboard.settings.notifications.dashboard-comments.title")]
       [:div {:class (stl/css :fields-row)}
        [:& fm/radio-buttons
         {:options [{:label (tr "dashboard.settings.notifications.dashboard-comments.all") :value "all"}
                    {:label (tr "dashboard.settings.notifications.dashboard-comments.partial") :value "partial"}
                    {:label (tr "dashboard.settings.notifications.dashboard-comments.none") :value "none"}]
          :name :dashboard-comments
          :class (stl/css :radio-btns)}]]

       [:h3 (tr "dashboard.settings.notifications.email.title")]
       [:h4 (tr "dashboard.settings.notifications.email-comments.title")]
       [:div {:class (stl/css :fields-row)}
        [:& fm/radio-buttons
         {:options [{:label (tr "dashboard.settings.notifications.email-comments.all") :value "all"}
                    {:label (tr "dashboard.settings.notifications.email-comments.partial") :value "partial"}
                    {:label (tr "dashboard.settings.notifications.email-comments.none") :value "none"}]
          :name :email-comments
          :class (stl/css :radio-btns)}]]

       [:h4 (tr "dashboard.settings.notifications.email-invites.title")]
       [:div {:class (stl/css :fields-row)}
        [:& fm/radio-buttons
         {:options [{:label (tr "dashboard.settings.notifications.email-invites.all") :value "all"}
                    ;; This type of notifications doesnt't exist yet
                    ;; {:label "Only invites and requests that my response" :value "partial"}
                    {:label (tr "dashboard.settings.notifications.email-invites.none") :value "none"}]
          :name :email-invites
          :class (stl/css :radio-btns)}]]

       [:> fm/submit-button*
        {:label (tr "dashboard.settings.notifications.submit")
         :data-testid "submit-settings"
         :class (stl/css :update-btn)}]]]]))

