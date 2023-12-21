;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.login
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.button-link :as bl]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [app.util.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(def show-alt-login-buttons?
  (some (partial contains? cf/flags)
        [:login-with-google
         :login-with-github
         :login-with-gitlab
         :login-with-oidc]))

(defn- login-with-oidc
  [event provider params]
  (dom/prevent-default event)
  (->> (rp/cmd! :login-with-oidc (assoc params :provider provider))
       (rx/subs! (fn [{:keys [redirect-uri] :as rsp}]
                  (if redirect-uri
                    (.replace js/location redirect-uri)
                    (log/error :hint "unexpected response from OIDC method"
                               :resp (pr-str rsp))))
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
    (->> (rp/cmd! :login-with-ldap params)
         (rx/subs! (fn [profile]
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

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)
(s/def ::invitation-token ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(defn handle-error-messages
  [errors _data]
  (d/update-when errors :email
                 (fn [{:keys [code] :as error}]
                   (cond-> error
                     (= code ::us/email)
                     (assoc :message (tr "errors.email-invalid"))))))

(mf/defc login-form
  [{:keys [params on-success-callback origin] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        initial (mf/use-memo (mf/deps params) (constantly params))
        error   (mf/use-state false)
        form    (fm/use-form :spec ::login-form
                             :validators [handle-error-messages]
                             :initial initial)

        on-error
        (fn [cause]
          (cond
            (and (= :restriction (:type cause))
                 (= :profile-blocked (:code cause)))
            (reset! error (tr "errors.profile-blocked"))

            (and (= :restriction (:type cause))
                 (= :admin-only-profile (:code cause)))
            (reset! error (tr "errors.profile-blocked"))

            (and (= :validation (:type cause))
                 (= :wrong-credentials (:code cause)))
            (reset! error (tr "errors.wrong-credentials"))

            (and (= :validation (:type cause))
                 (= :account-without-password (:code cause)))
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
            (on-success-callback)))

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
                                       :on-success on-success})))))

        on-recovery-request
        (mf/use-fn
         #(st/emit! (rt/nav :auth-recovery-request)))]

    (if new-css-system
      [:*
       (when-let [message @error]
         [:& msgs/inline-banner
          {:type :warning
           :content message
           :on-close #(reset! error nil)
           :data-test "login-banner"
           :role "alert"}])

       [:& fm/form {:on-submit on-submit :form form}
        [:div {:class (stl/css :fields-row)}
         [:& fm/input
          {:name :email
           :type "email"
           :label (tr "auth.email")
           :class (stl/css :form-field)}]]

        [:div {:class (stl/css :fields-row)}
         [:& fm/input
          {:type "password"
           :name :password
           :label (tr "auth.password")
           :class (stl/css :form-field)}]]

        (when (and (not= origin :viewer)
               (or (contains? cf/flags :login)
                  (contains? cf/flags :login-with-password)))
          [:div {:class (stl/css :fields-row :forgot-password)}
           [:& lk/link {:action on-recovery-request
                        :data-test "forgot-password"}
            (tr "auth.forgot-password")]])

        [:div {:class (stl/css :buttons-stack)}
         (when (or (contains? cf/flags :login)
                   (contains? cf/flags :login-with-password))
           [:> fm/submit-button*
            {:label (tr "auth.login-submit")
             :data-test "login-submit"
             :class (stl/css :login-button)}])

         (when (contains? cf/flags :login-with-ldap)
           [:> fm/submit-button*
            {:label (tr "auth.login-with-ldap-submit")
             :on-click on-submit-ldap}])]]]


      ;; OLD
      [:*
       (when-let [message @error]
         [:& msgs/inline-banner
          {:type :warning
           :content message
           :on-close #(reset! error nil)
           :data-test "login-banner"
           :role "alert"}])

       [:& fm/form {:on-submit on-submit :form form}
        [:div.fields-row
         [:& fm/input
          {:name :email
           :type "email"
           :help-icon i/at
           :label (tr "auth.email")
           :class (stl/css :form-field)}]]

        [:div.fields-row
         [:& fm/input
          {:type "password"
           :name :password
           :help-icon i/eye
           :label (tr "auth.password")
           :class (stl/css :form-field)}]]

        [:div.buttons-stack
         (when (or (contains? cf/flags :login)
                   (contains? cf/flags :login-with-password))
           [:> fm/submit-button*
            {:label (tr "auth.login-submit")
             :data-test "login-submit"}])

         (when (contains? cf/flags :login-with-ldap)
           [:> fm/submit-button*
            {:label (tr "auth.login-with-ldap-submit")
             :on-click on-submit-ldap}])]]])))

(mf/defc login-buttons
  [{:keys [params] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)

        login-with-google (mf/use-fn (mf/deps params) #(login-with-oidc % :google params))
        login-with-github (mf/use-fn (mf/deps params) #(login-with-oidc % :github params))
        login-with-gitlab (mf/use-fn (mf/deps params) #(login-with-oidc % :gitlab params))
        login-with-oidc   (mf/use-fn (mf/deps params) #(login-with-oidc % :oidc params))]

    (if new-css-system
      [:div {:class (stl/css :auth-buttons)}
       (when (contains? cf/flags :login-with-google)
         [:& bl/button-link {:on-click login-with-google
                             :icon i/brand-google
                             :label (tr "auth.login-with-google-submit")
                             :class (stl/css :login-btn :btn-google-auth)}])

       (when (contains? cf/flags :login-with-github)
         [:& bl/button-link {:on-click login-with-github
                             :icon i/brand-github
                             :label (tr "auth.login-with-github-submit")
                             :class (stl/css :login-btn :btn-github-auth)}])

       (when (contains? cf/flags :login-with-gitlab)
         [:& bl/button-link {:on-click login-with-gitlab
                             :icon i/brand-gitlab
                             :label (tr "auth.login-with-gitlab-submit")
                             :class (stl/css :login-btn :btn-gitlab-auth)}])

       (when (contains? cf/flags :login-with-oidc)
         [:& bl/button-link {:on-click login-with-oidc
                             :icon i/brand-openid
                             :label (tr "auth.login-with-oidc-submit")
                             :class (stl/css :login-btn :btn-oidc-auth)}])]

      [:div.auth-buttons
       (when (contains? cf/flags :login-with-google)
         [:& bl/button-link {:on-click login-with-google
                             :icon i/brand-google
                             :label (tr "auth.login-with-google-submit")
                             :class "btn-google-auth"}])

       (when (contains? cf/flags :login-with-github)
         [:& bl/button-link {:on-click login-with-github
                             :icon i/brand-github
                             :label (tr "auth.login-with-github-submit")
                             :class "btn-github-auth"}])

       (when (contains? cf/flags :login-with-gitlab)
         [:& bl/button-link {:on-click login-with-gitlab
                             :icon i/brand-gitlab
                             :label (tr "auth.login-with-gitlab-submit")
                             :class "btn-gitlab-auth"}])

       (when (contains? cf/flags :login-with-oidc)
         [:& bl/button-link {:on-click login-with-oidc
                             :icon i/brand-openid
                             :label (tr "auth.login-with-oidc-submit")
                             :class "btn-github-auth"}])])))

(mf/defc login-button-oidc
  [{:keys [params] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        login-oidc
        (mf/use-fn
         (mf/deps params)
         (fn [event]
           (login-with-oidc event :oidc params)))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (when (k/enter? event)
             (login-oidc event))))]
    (if new-css-system
      (when (contains? cf/flags :login-with-oidc)
        [:div {:class (stl/css :link-entry :link-oidc)}
         [:a {:tab-index "0"
              :on-key-down handle-key-down
              :on-click login-oidc}
          (tr "auth.login-with-oidc-submit")]])

      ;; OLD
      (when (contains? cf/flags :login-with-oidc)
        [:div.link-entry.link-oidc
         [:a {:tab-index "0"
              :on-key-down handle-key-down
              :on-click login-oidc}
          (tr "auth.login-with-oidc-submit")]]))))

(mf/defc login-methods
  [{:keys [params on-success-callback origin] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:*
       (when show-alt-login-buttons?
         [:*
          [:& login-buttons {:params params}]

          (when (or (contains? cf/flags :login)
                    (contains? cf/flags :login-with-password)
                    (contains? cf/flags :login-with-ldap))
            [:hr {:class (stl/css :separator)}])])

       (when (or (contains? cf/flags :login)
                 (contains? cf/flags :login-with-password)
                 (contains? cf/flags :login-with-ldap))
         [:& login-form {:params params :on-success-callback on-success-callback :origin origin}])]

      ;; OLD
      [:*
       (when show-alt-login-buttons?
         [:*
          [:span.separator
           [:span.line]
           [:span.text (tr "labels.continue-with")]
           [:span.line]]

          [:& login-buttons {:params params}]

          (when (or (contains? cf/flags :login)
                    (contains? cf/flags :login-with-password)
                    (contains? cf/flags :login-with-ldap))
            [:span.separator
             [:span.line]
             [:span.text (tr "labels.or")]
             [:span.line]])])

       (when (or (contains? cf/flags :login)
                 (contains? cf/flags :login-with-password)
                 (contains? cf/flags :login-with-ldap))
         [:& login-form {:params params :on-success-callback on-success-callback}])])))

(mf/defc login-page
  [{:keys [params] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        go-register
        (mf/use-fn
         #(st/emit! (rt/nav :auth-register {} params)))

        on-pass-recovery
        (mf/use-fn
         #(st/emit! (rt/nav :auth-recovery-request)))

        on-create-demo-profile
        (mf/use-fn
         #(st/emit! (du/create-demo-profile)))]

    (if new-css-system
      [:div {:class (stl/css :auth-form)}
       [:h1 {:class (stl/css :auth-title)
             :data-test "login-title"} (tr "auth.login-title")]

       [:hr {:class (stl/css :separator)}]

       [:& login-methods {:params params}]

       [:div {:class (stl/css :links)}
        (when (or (contains? cf/flags :login)
                  (contains? cf/flags :login-with-password))
          [:div {:class (stl/css :link-entry :register)}
           [:& lk/link {:action on-pass-recovery
                        :data-test "forgot-password"}
            (tr "auth.forgot-password")]])

        (when (contains? cf/flags :registration)
          [:div {:class (stl/css :link-entry :register)}
           [:span (tr "auth.register") " "]
           [:& lk/link {:action go-register
                        :data-test "register-submit"}
            (tr "auth.register-submit")]])]

       (when (contains? cf/flags :demo-users)
         [:div {:class (stl/css :link-entry :demo-account)}
          [:span (tr "auth.create-demo-profile") " "]
          [:& lk/link {:action on-create-demo-profile
                       :data-test "demo-account-link"}
           (tr "auth.create-demo-account")]])]

      ;; OLD
      [:div.generic-form.login-form
       [:div.form-container
        [:h1 {:data-test "login-title"} (tr "auth.login-title")]

        [:& login-methods {:params params}]

        [:div.links
         (when (or (contains? cf/flags :login)
                   (contains? cf/flags :login-with-password))
           [:div.link-entry
            [:& lk/link {:action on-pass-recovery
                         :data-test "forgot-password"}
             (tr "auth.forgot-password")]])

         (when (contains? cf/flags :registration)
           [:div.link-entry
            [:span (tr "auth.register") " "]
            [:& lk/link {:action go-register
                         :data-test "register-submit"}
             (tr "auth.register-submit")]])]

        (when (contains? cf/flags :demo-users)
          [:div.links.demo
           [:div.link-entry
            [:span (tr "auth.create-demo-profile") " "]
            [:& lk/link {:action on-create-demo-profile
                         :data-test "demo-account-link"}
             (tr "auth.create-demo-account")]]])]])))

