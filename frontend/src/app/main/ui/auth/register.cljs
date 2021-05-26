;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth.register
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.users :as du]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.auth.login :as login]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr t]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc demo-warning
  [_]
  [:& msgs/inline-banner
   {:type :warning
    :content (tr "auth.demo-warning")}])

(defn- validate
  [data]
  (let [password (:password data)
        terms-privacy (:terms-privacy data)]
    (cond-> {}
      (> 8 (count password))
      (assoc :password {:message "errors.password-too-short"})

      (and (not terms-privacy) false)
      (assoc :terms-privacy {:message "errors.terms-privacy-agreement-invalid"}))))

(s/def ::fullname ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::invitation-token ::us/not-empty-string)
(s/def ::terms-privacy ::us/boolean)

(s/def ::register-form
  (s/keys :req-un [::password ::fullname ::email ::terms-privacy]
          :opt-un [::invitation-token]))

(mf/defc register-form
  [{:keys [params] :as props}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))
        form    (fm/use-form :spec ::register-form
                             :validators [validate]
                             :initial initial)
        submitted? (mf/use-state false)

        on-error
        (mf/use-callback
         (fn [form error]
           (reset! submitted? false)
           (case (:code error)
             :registration-disabled
             (rx/of (dm/error (tr "errors.registration-disabled")))

             :email-has-permanent-bounces
             (let [email (get @form [:data :email])]
               (rx/of (dm/error (tr "errors.email-has-permanent-bounces" email))))

             :email-already-exists
             (swap! form assoc-in [:errors :email]
                    {:message "errors.email-already-exists"})

             (rx/throw error))))

        on-success
        (mf/use-callback
         (fn [form data]
           (reset! submitted? false)
           (if-let [token (:invitation-token data)]
             (st/emit! (rt/nav :auth-verify-token {} {:token token}))
             (st/emit! (rt/nav :auth-register-success {} {:email (:email data)})))))

        on-submit
        (mf/use-callback
         (fn [form event]
           (reset! submitted? true)
           (let [data (with-meta (:clean-data @form)
                        {:on-error (partial on-error form)
                         :on-success (partial on-success form)})]
             (st/emit! (du/register data)))))]


    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:name :fullname
                    :tab-index "1"
                    :label (tr "auth.fullname")
                    :type "text"}]]
     [:div.fields-row
      [:& fm/input {:type "email"
                    :name :email
                    :tab-index "2"
                    :help-icon i/at
                    :label (tr "auth.email")}]]
     [:div.fields-row
      [:& fm/input {:name :password
                    :tab-index "3"
                    :hint (tr "auth.password-length-hint")
                    :label (tr "auth.password")
                    :type "password"}]]

     [:div.fields-row
      [:& fm/input {:name :terms-privacy
                    :class "check-primary"
                    :tab-index "4"
                    :label (tr "auth.terms-privacy-agreement")
                    :type "checkbox"}]]

     [:& fm/submit-button
      {:label (tr "auth.register-submit")
       :disabled @submitted?}]]))

;; --- Register Page

(mf/defc register-success-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:div.notification-icon i/icon-verify]
   [:div.notification-text (tr "auth.verification-email-sent")]
   [:div.notification-text-email (:email params "")]
   [:div.notification-text (tr "auth.check-your-email")]])

(mf/defc register-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:h1 (tr "auth.register-title")]
   [:div.subtitle (tr "auth.register-subtitle")]

   (when cfg/demo-warning
     [:& demo-warning])

   [:& register-form {:params params}]

   [:div.links
    [:div.link-entry
     [:span (tr "auth.already-have-account") " "]
     [:a {:on-click #(st/emit! (rt/nav :auth-login {} params))
          :tab-index "4"}
      (tr "auth.login-here")]]

    (when cfg/allow-demo-users
      [:div.link-entry
       [:span (tr "auth.create-demo-profile") " "]
       [:a {:on-click #(st/emit! (du/create-demo-profile))
            :tab-index "5"}
        (tr "auth.create-demo-account")]])

    [:& login/login-buttons {:params params}]]])



