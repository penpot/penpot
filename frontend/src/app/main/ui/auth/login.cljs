;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth.login
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [app.config :as cfg]
   [app.common.spec :as us]
   [app.main.ui.icons :as i]
   [app.main.data.auth :as da]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.messages :as msgs]
   [app.main.data.messages :as dm]
   [app.main.ui.components.forms :as fm]
   [app.util.object :as obj]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr t]]
   [app.util.router :as rt]))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]))

(defn- login-with-google
  [event params]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-google params)
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri))
                (fn [{:keys [type] :as error}]
                  (st/emit! (dm/error (tr "errors.google-auth-not-enabled")))))))

(defn- login-with-gitlab
  [event params]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-gitlab params)
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri)))))

(defn- login-with-github
  [event params]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-github params)
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri)))))

(defn- login-with-ldap
  [event params]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (let [{:keys [on-error]} (meta params)]
    (->> (rp/mutation! :login-with-ldap params)
         (rx/subs (fn [profile]
                    (if-let [token (:invitation-token profile)]
                      (st/emit! (rt/nav :auth-verify-token {} {:token token}))
                      (st/emit! (da/logged-in profile))))
                  (fn [{:keys [type code] :as error}]
                    (cond
                      (and (= type :restriction)
                           (= code :ldap-disabled))
                      (st/emit! (dm/error (tr "errors.ldap-disabled")))

                      (fn? on-error)
                      (on-error error)))))))

(mf/defc login-form
  [{:keys [params] :as props}]
  (let [error (mf/use-state false)
        form  (fm/use-form :spec ::login-form
                           :inital {})

        on-error
        (fn [_]
          (reset! error (tr "errors.wrong-credentials")))

        on-submit
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (reset! error nil)
           (let [params (with-meta (:clean-data @form)
                          {:on-error on-error})]
             (st/emit! (da/login params)))))

        on-submit-ldap
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (let [params (merge (:clean-data @form) params)]
             (login-with-ldap event (with-meta params {:on-error on-error})))))]

    [:*
     (when-let [message @error]
       [:& msgs/inline-banner
        {:type :warning
         :content message
         :on-close #(reset! error nil)}])

     [:& fm/form {:on-submit on-submit :form form}
      [:div.fields-row
       [:& fm/input
        {:name :email
         :type "text"
         :tab-index "2"
         :help-icon i/at
         :label (tr "auth.email")}]]
      [:div.fields-row
       [:& fm/input
        {:type "password"
         :name :password
         :tab-index "3"
         :help-icon i/eye
         :label (tr "auth.password")}]]
      [:& fm/submit-button
       {:label (tr "auth.login-submit")}]

      (when cfg/login-with-ldap
        [:& fm/submit-button
         {:label (tr "auth.login-with-ldap-submit")
          :on-click on-submit-ldap}])]]))

(mf/defc login-page
  [{:keys [params] :as props}]
  [:div.generic-form.login-form
   [:div.form-container
    [:h1 (tr "auth.login-title")]
    [:div.subtitle (tr "auth.login-subtitle")]

    [:& login-form {:params params}]

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-recovery-request))
           :tab-index "5"}
       (tr "auth.forgot-password")]]

     (when cfg/registration-enabled
       [:div.link-entry
        [:span (tr "auth.register") " "]
        [:a {:on-click #(st/emit! (rt/nav :auth-register {} params))
             :tab-index "6"}
         (tr "auth.register-submit")]])]

    (when cfg/google-client-id
      [:a.btn-ocean.btn-large.btn-google-auth
       {:on-click #(login-with-google % params)}
       "Login with Google"])

    (when cfg/gitlab-client-id
      [:a.btn-ocean.btn-large.btn-gitlab-auth
       {:on-click #(login-with-gitlab % params)}
       [:img.logo
        {:src "/images/icons/brand-gitlab.svg"}]
       (tr "auth.login-with-gitlab-submit")])

    (when cfg/github-client-id
      [:a.btn-ocean.btn-large.btn-github-auth
       {:on-click #(login-with-github % params)}
       [:img.logo
        {:src "/images/icons/brand-github.svg"}]
       (tr "auth.login-with-github-submit")])

    (when cfg/allow-demo-users
      [:div.links.demo
       [:div.link-entry
        [:span (tr "auth.create-demo-profile") " "]
        [:a {:on-click (st/emitf da/create-demo-profile)
             :tab-index "6"}
         (tr "auth.create-demo-account")]]])]])
