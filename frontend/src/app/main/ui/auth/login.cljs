;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
  (some (partial contains? @cf/flags)
        [:login-with-google
         :login-with-github
         :login-with-gitlab
         :login-with-oidc]))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)
(s/def ::invitation-token ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(defn- login-with-oidc
  [event provider params]
  (dom/prevent-default event)
  (->> (rp/command! :login-with-oidc (assoc params :provider provider))
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri))
                (fn [{:keys [type code] :as error}]
                  (cond
                    (and (= type :restriction)
                         (= code :provider-not-configured))
                    (st/emit! (dm/error (tr "errors.auth-provider-not-configured")))

                    :else
                    (st/emit! (dm/error (tr "errors.generic"))))))))

(defn- login-with-ldap
  [event params]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (let [{:keys [on-error]} (meta params)]
    (->> (rp/command! :login-with-ldap params)
         (rx/subs (fn [profile]
                    (if-let [token (:invitation-token profile)]
                      (st/emit! (rt/nav :auth-verify-token {} {:token token}))
                      (st/emit! (du/login-from-token {:profile profile}))))
                  (fn [{:keys [type code] :as error}]
                    (cond
                      (and (= type :restriction)
                           (= code :ldap-not-initialized))
                      (st/emit! (dm/error (tr "errors.ldap-disabled")))

                      (fn? on-error)
                      (on-error error)

                      :else
                      (st/emit! (dm/error (tr "errors.generic")))))))))


(mf/defc login-form
  [{:keys [params on-success-callback] :as props}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))

        error   (mf/use-state false)
        form    (fm/use-form :spec ::login-form :initial initial)

        on-error
        (fn [cause]
          (cond
            (and (= :restriction (:type cause))
                 (= :profile-blocked (:code cause)))
            (reset! error (tr "errors.profile-blocked"))

            (and (= :validation (:type cause))
                 (= :wrong-credentials (:code cause)))
            (reset! error (tr "errors.wrong-credentials"))

            :else
            (reset! error (tr "errors.generic"))))

        on-success-default
        (fn [data]
          (when-let [token (:invitation-token data)]
            (st/emit! (rt/nav :auth-verify-token {} {:token token}))))

        on-success
        (fn [data]
          (if (nil? on-success-callback)
            (on-success-default data)
            (on-success-callback)
          ))

        on-submit
        (mf/use-callback
         (fn [form _event]
           (reset! error nil)
           (let [params (with-meta (:clean-data @form)
                          {:on-error on-error
                           :on-success on-success})]
             (st/emit! (du/login params)))))

        on-submit-ldap
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (reset! error nil)
           (let [params (:clean-data @form)]
             (login-with-ldap event (with-meta params
                                      {:on-error on-error
                                       :on-success on-success})))))]
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
       (when (contains? @cf/flags :login)
         [:& fm/submit-button
          {:label (tr "auth.login-submit")
           :data-test "login-submit"}])

       (when (contains? @cf/flags :login-with-ldap)
         [:& fm/submit-button
          {:label (tr "auth.login-with-ldap-submit")
           :on-click on-submit-ldap}])]]]))

(mf/defc login-buttons
  [{:keys [params] :as props}]
  [:div.auth-buttons
   (when (contains? @cf/flags :login-with-google)
     [:a.btn-primary.btn-large.btn-google-auth
      {:on-click #(login-with-oidc % :google params)}
      [:span.logo i/brand-google]
      (tr "auth.login-with-google-submit")])

   (when (contains? @cf/flags :login-with-github)
     [:a.btn-primary.btn-large.btn-github-auth
      {:on-click #(login-with-oidc % :github params)}
      [:span.logo i/brand-github]
      (tr "auth.login-with-github-submit")])

   (when (contains? @cf/flags :login-with-gitlab)
     [:a.btn-primary.btn-large.btn-gitlab-auth
      {:on-click #(login-with-oidc % :gitlab params)}
      [:span.logo i/brand-gitlab]
      (tr "auth.login-with-gitlab-submit")])

   (when (contains? @cf/flags :login-with-oidc)
     [:a.btn-primary.btn-large.btn-github-auth
      {:on-click #(login-with-oidc % :oidc params)}
      [:span.logo i/brand-openid]
      (tr "auth.login-with-oidc-submit")])])

(mf/defc login-button-oidc
  [{:keys [params] :as props}]
  (when (contains? @cf/flags :login-with-oidc)
    [:div.link-entry.link-oidc
     [:a {:on-click #(login-with-oidc % :oidc params)}
      (tr "auth.login-with-oidc-submit")]]))

(mf/defc login-methods
  [{:keys [params on-success-callback] :as props}]
  [:*
   (when show-alt-login-buttons?
     [:*
      [:span.separator
       [:span.line]
       [:span.text (tr "labels.continue-with")]
       [:span.line]]

      [:div.buttons
       [:& login-buttons {:params params}]]

      (when (or (contains? @cf/flags :login)
                (contains? @cf/flags :login-with-ldap))
        [:span.separator
         [:span.line]
         [:span.text (tr "labels.or")]
         [:span.line]])])

   (when (or (contains? @cf/flags :login)
             (contains? @cf/flags :login-with-ldap))
     [:& login-form {:params params :on-success-callback on-success-callback}])])

(mf/defc login-page
  [{:keys [params] :as props}]
  [:div.generic-form.login-form
   [:div.form-container
    [:h1 {:data-test "login-title"} (tr "auth.login-title")]

    [:& login-methods {:params params}]

    [:div.links
     (when (contains? @cf/flags :login)
       [:div.link-entry
        [:a {:on-click #(st/emit! (rt/nav :auth-recovery-request))
             :data-test "forgot-password"}
         (tr "auth.forgot-password")]])

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
        [:a {:on-click #(st/emit! (du/create-demo-profile))
             :data-test "demo-account-link"}
         (tr "auth.create-demo-account")]]])]])
