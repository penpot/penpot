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
   [app.main.ui.auth.login :as login]
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

(defn- validate
  [data]
  (let [password (:password data)]
    (when (> 8 (count password))
      {:password {:message "errors.password-too-short"}})))

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
             (st/emit! (dm/error (tr "errors.registration-disabled")))

             :email-already-exists
             (swap! form assoc-in [:errors :email]
                    {:message "errors.email-already-exists"})

             (st/emit! (dm/error (tr "errors.unexpected-error"))))))

        on-success
        (mf/use-callback
         (fn [form data]
           (reset! submitted? false)
           (if (and (:is-active data) (:claims data))
             (let [message (tr "auth.notifications.team-invitation-accepted")]
               (st/emit! (rt/nav :dashboard-projects {:team-id (get-in data [:claims :team-id])})
                         du/fetch-profile
                         (dm/success message)))
             (st/emit! (rt/nav :auth-register-success {} {:email (:email data)})))))

        on-submit
        (mf/use-callback
         (fn [form event]
           (reset! submitted? true)
           (let [data (with-meta (:clean-data @form)
                        {:on-error (partial on-error form)
                         :on-success (partial on-success form)})]
             (st/emit! (da/register data)))))]


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

     [:& fm/submit-button
      {:label (tr "auth.register-submit")
       :disabled @submitted?
       }]]))

;; --- Register Page

(mf/defc register-success-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:div.subtitle (tr "auth.verification-email-sent" (:email params ""))]
   [:div.subtitle (tr "auth.check-your-email")]])


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
     [:a {:on-click #(st/emit! (rt/nav :auth-login))
          :tab-index "4"}
      (tr "auth.login-here")]]

    (when cfg/allow-demo-users
      [:div.link-entry
       [:span (tr "auth.create-demo-profile") " "]
       [:a {:on-click #(st/emit! da/create-demo-profile)
            :tab-index "5"}
        (tr "auth.create-demo-account")]])]

   (when cfg/google-client-id
     [:a.btn-ocean.btn-large.btn-google-auth
      {:on-click login/login-with-google}
      "Login with Google"])

   (when cfg/gitlab-client-id
     [:a.btn-ocean.btn-large.btn-gitlab-auth
      {:on-click login/login-with-gitlab}
      [:img.logo
       {:src "/images/icons/brand-gitlab.svg"}]
      (tr "auth.login-with-gitlab-submit")])

   (when cfg/github-client-id
     [:a.btn-ocean.btn-large.btn-github-auth
      {:on-click login/login-with-github}
      [:img.logo
       {:src "/images/icons/brand-github.svg"}]
      (tr "auth.login-with-github-submit")])])

