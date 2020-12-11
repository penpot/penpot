;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.auth.register
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.auth :as da]
   [app.main.data.users :as du]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr t]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc demo-warning
  [_]
  [:& msgs/inline-banner
   {:type :warning
    :content (tr "auth.demo-warning")}])

(defn- on-error
  [form error]
  (case (:code error)
    :registration-disabled
    (st/emit! (dm/error (tr "errors.registration-disabled")))

    :email-already-exists
    (swap! form assoc-in [:errors :email]
           {:message "errors.email-already-exists"})

    (st/emit! (dm/error (tr "errors.unexpected-error")))))

(defn- on-success
  [form data]
  (if (and (:is-active data) (:claims data))
    (let [message (tr "auth.notifications.team-invitation-accepted")]
      (st/emit! (rt/nav :dashboard-projects {:team-id (get-in data [:claims :team-id])})
                du/fetch-profile
                (dm/success message)))
    (let [message (tr "notifications.validation-email-sent" (:email data))]
      (st/emit! (rt/nav :auth-login)
                (dm/success message)))))

(defn- validate
  [data]
  (let [password (:password data)]
    (when (> 8 (count password))
      {:password {:message "errors.password-too-short"}})))

(defn- on-submit
  [form event]
  (let [data (with-meta (:clean-data @form)
               {:on-error (partial on-error form)
                :on-success (partial on-success form)})]
    (st/emit! (da/register data))))

(s/def ::fullname ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::token ::us/not-empty-string)

(s/def ::register-form
  (s/keys :req-un [::password
                   ::fullname
                   ::email]
          :opt-un [::token]))

(mf/defc register-form
  [{:keys [locale params] :as props}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))
        form    (fm/use-form :spec ::register-form
                             :validators [validate]
                             :initial initial)]

    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:name :fullname
                    :tab-index "1"
                    :label (t locale "auth.fullname")
                    :type "text"}]]
     [:div.fields-row
      [:& fm/input {:type "email"
                    :name :email
                    :tab-index "2"
                    :help-icon i/at
                    :label (t locale "auth.email")}]]
     [:div.fields-row
      [:& fm/input {:name :password
                    :tab-index "3"
                    :hint (t locale "auth.password-length-hint")
                    :label (t locale "auth.password")
                    :type "password"}]]

     [:& fm/submit-button
      {:label (t locale "auth.register-submit")}]]))

;; --- Register Page

(mf/defc register-page
  [{:keys [locale params] :as props}]
  [:div.form-container
   [:h1 (t locale "auth.register-title")]
   [:div.subtitle (t locale "auth.register-subtitle")]
   (when cfg/demo-warning
     [:& demo-warning])

   [:& register-form {:locale locale
                      :params params}]

   [:div.links
    [:div.link-entry
     [:span (t locale "auth.already-have-account") " "]
     [:a {:on-click #(st/emit! (rt/nav :auth-login))
          :tab-index "4"}
      (t locale "auth.login-here")]]

    [:div.link-entry
     [:span (t locale "auth.create-demo-profile") " "]
     [:a {:on-click #(st/emit! da/create-demo-profile)
          :tab-index "5"}
      (t locale "auth.create-demo-account")]]]])