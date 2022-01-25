;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth.login
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(def show-alt-login-buttons?
  (or cf/google-client-id
      cf/gitlab-client-id
      cf/github-client-id
      cf/oidc-client-id))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]))

(defn- login-with-oauth
  [event provider params]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-oauth (assoc params :provider provider))
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
                      (st/emit! (du/login-from-token {:profile profile}))))
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
         (fn [_]
           (reset! error nil)
           (let [params (with-meta (:clean-data @form)
                          {:on-error on-error})]
             (st/emit! (du/login params)))))

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
         :on-close #(reset! error nil)
         :data-test "login-banner"}])

     [:& fm/form {:on-submit on-submit :form form}
      [:div.fields-row
       [:& fm/input
        {:name :email
         :type "email"
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

      [:div.buttons-stack
       [:& fm/submit-button
        {:label (tr "auth.login-submit")
         :data-test "login-submit"}]

       (when (contains? @cf/flags :login-with-ldap)
         [:& fm/submit-button
          {:label (tr "auth.login-with-ldap-submit")
           :on-click on-submit-ldap}])]]]))

(mf/defc login-buttons
  [{:keys [params] :as props}]
  [:div.auth-buttons
   (when cf/google-client-id
     [:a.btn-ocean.btn-large.btn-google-auth
      {:on-click #(login-with-oauth % :google params)}
      (tr "auth.login-with-google-submit")])

   (when cf/gitlab-client-id
     [:a.btn-ocean.btn-large.btn-gitlab-auth
      {:on-click #(login-with-oauth % :gitlab params)}
      [:img.logo
       {:src "/images/icons/brand-gitlab.svg"}]
      (tr "auth.login-with-gitlab-submit")])

   (when cf/github-client-id
     [:a.btn-ocean.btn-large.btn-github-auth
      {:on-click #(login-with-oauth % :github params)}
      [:img.logo
       {:src "/images/icons/brand-github.svg"}]
      (tr "auth.login-with-github-submit")])

   (when cf/oidc-client-id
     [:a.btn-ocean.btn-large.btn-github-auth
      {:on-click #(login-with-oauth % :oidc params)}
      (tr "auth.login-with-oidc-submit")])])

(mf/defc login-page
  [{:keys [params] :as props}]
  [:div.generic-form.login-form
   [:div.form-container
    [:h1 {:data-test "login-title"} (tr "auth.login-title")]
    [:div.subtitle (tr "auth.login-subtitle")]

    [:& login-form {:params params}]

    (when show-alt-login-buttons?
      [:*
       [:span.separator (tr "labels.or")]

       [:div.buttons
        [:& login-buttons {:params params}]]])

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-recovery-request))
           :data-test "forgot-password"}
       (tr "auth.forgot-password")]]

     (when (contains? @cf/flags :registration)
       [:div.link-entry
        [:span (tr "auth.register") " "]
        [:a {:on-click #(st/emit! (rt/nav :auth-register {} params))
             :data-test "register-submit"}
         (tr "auth.register-submit")]])]

    (when (contains? @cf/flags :demo-users)
      [:div.links.demo
       [:div.link-entry
        [:span (tr "auth.create-demo-profile") " "]
        [:a {:on-click (st/emitf (du/create-demo-profile))
             :data-test "demo-account-link"}
         (tr "auth.create-demo-account")]]])]])
