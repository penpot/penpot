;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.login
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.notifications :as ntf]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.button-link :as bl]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [app.util.storage :as s]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def show-alt-login-buttons?
  (some (partial contains? cf/flags)
        [:login-with-google
         :login-with-github
         :login-with-gitlab
         :login-with-oidc]))

(mf/defc demo-warning
  {::mf/props :obj}
  []
  [:& context-notification
   {:level :warning
    :content (tr "auth.demo-warning")}])

(defn create-demo-profile
  []
  (st/emit! (da/create-demo-profile)))

(defn- store-login-redirect
  [save-login-redirect]
  (binding [s/*sync* true]
    (if (some? save-login-redirect)
      ;; Save the current login raw uri for later redirect user back to
      ;; the same page, we need it to be synchronous because the user is
      ;; going to be redirected instantly to the oidc provider uri
      (swap! s/session assoc :login-redirect (rt/get-current-href))
      ;; Clean the login redirect
      (swap! s/session dissoc :login-redirect))))

(defn- login-with-oidc
  [event provider params]
  (dom/prevent-default event)

  (store-login-redirect (:save-login-redirect params))

  ;; FIXME: this code should be probably moved outside of the UI
  (->> (rp/cmd! :login-with-oidc (assoc params :provider provider))
       (rx/subs! (fn [{:keys [redirect-uri] :as rsp}]
                   (if redirect-uri
                     (st/emit! (rt/nav-raw :uri redirect-uri))
                     (log/error :hint "unexpected response from OIDC method"
                                :resp (pr-str rsp))))
                 (fn [cause]
                   (let [{:keys [type code] :as error} (ex-data cause)]
                     (cond
                       (and (= type :restriction)
                            (= code :provider-not-configured))
                       (st/emit! (ntf/error (tr "errors.auth-provider-not-configured")))

                       :else
                       (st/emit! (ntf/error (tr "errors.generic")))))))))

(def ^:private schema:login-form
  [:map {:title "LoginForm"}
   [:email [::sm/email {:error/code "errors.invalid-email"}]]
   [:password [:string {:min 1}]]
   [:invitation-token {:optional true}
    [:string {:min 1}]]])

(mf/defc login-form
  [{:keys [params on-success-callback on-recovery-request origin] :as props}]
  (let [initial (mf/with-memo [params] params)
        error   (mf/use-state false)
        form    (fm/use-form :schema schema:login-form
                             :initial initial)

        on-error
        (fn [cause]
          (let [cause (ex-data cause)]
            (cond
              (and (= :restriction (:type cause))
                   (= :profile-blocked (:code cause)))
              (reset! error (tr "errors.profile-blocked"))

              (and (= :restriction (:type cause))
                   (= :ldap-not-initialized (:code cause)))
              (st/emit! (ntf/error (tr "errors.ldap-disabled")))

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
              (reset! error (tr "errors.generic")))))

        on-success
        (fn [data]
          (when (fn? on-success-callback)
            (on-success-callback data)))

        on-submit
        (mf/use-callback
         (fn [form _event]
           (store-login-redirect (:save-login-redirect params))
           (reset! error nil)
           (let [params (with-meta (:clean-data @form)
                          {:on-error on-error
                           :on-success on-success})]
             (st/emit! (da/login params)))))

        on-submit-ldap
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)

           (reset! error nil)
           (let [params (:clean-data @form)
                 params (with-meta params
                          {:on-error on-error
                           :on-success on-success})]
             (st/emit! (da/login-with-ldap params)))))

        default-recovery-req
        (mf/use-fn
         #(st/emit! (rt/nav :auth-recovery-request)))

        on-recovery-request (or on-recovery-request
                                default-recovery-req)]

    [:*
     (when-let [message @error]
       [:> context-notification*
        {:level :error} message])

     [:& fm/form {:on-submit on-submit
                  :class (stl/css :login-form)
                  :form form}
      [:div {:class (stl/css :fields-row)}
       [:& fm/input
        {:name :email
         :type "email"
         :label (tr "auth.work-email")
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
                      :class (stl/css :forgot-pass-link)
                      :data-testid "forgot-password"}
          (tr "auth.forgot-password")]])

      [:div {:class (stl/css :buttons-stack)}
       (when (or (contains? cf/flags :login)
                 (contains? cf/flags :login-with-password))
         [:> fm/submit-button*
          {:label (tr "auth.login-submit")
           :data-testid "login-submit"
           :class (stl/css :login-button)}])

       (when (contains? cf/flags :login-with-ldap)
         [:> fm/submit-button*
          {:label (tr "auth.login-with-ldap-submit")
           :class (stl/css :login-ldap-button)
           :on-click on-submit-ldap}])]]]))

(mf/defc login-buttons
  [{:keys [params] :as props}]
  (let [login-with-google (mf/use-fn (mf/deps params) #(login-with-oidc % :google params))
        login-with-github (mf/use-fn (mf/deps params) #(login-with-oidc % :github params))
        login-with-gitlab (mf/use-fn (mf/deps params) #(login-with-oidc % :gitlab params))
        login-with-oidc   (mf/use-fn (mf/deps params) #(login-with-oidc % :oidc params))]

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
                           :class (stl/css :login-btn :btn-oidc-auth)}])]))

(mf/defc login-button-oidc
  [{:keys [params] :as props}]
  (let [login-oidc
        (mf/use-fn
         (mf/deps params)
         (fn [event]
           (login-with-oidc event :oidc params)))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (when (k/enter? event)
             (login-oidc event))))]
    (when (contains? cf/flags :login-with-oidc)
      [:button {:tab-index "0"
                :class (stl/css :link-entry :link-oidc)
                :on-key-down handle-key-down
                :on-click login-oidc}
       (tr "auth.login-with-oidc-submit")])))

(mf/defc login-methods
  [{:keys [params on-success-callback on-recovery-request origin] :as props}]
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
     [:& login-form {:params params :on-success-callback on-success-callback :on-recovery-request on-recovery-request :origin origin}])])

(mf/defc login-page
  [{:keys [params] :as props}]
  (let [go-register
        (mf/use-fn
         #(st/emit! (rt/nav :auth-register params)))]

    [:div {:class (stl/css :auth-form-wrapper)}
     [:h1 {:class (stl/css :auth-title)
           :data-testid "login-title"} (tr "auth.login-account-title")]

     [:p {:class (stl/css :auth-tagline)}
      (tr "auth.login-tagline")]

     (when (contains? cf/flags :demo-warning)
       [:& demo-warning])

     [:& login-methods {:params params}]

     [:hr {:class (stl/css :separator)}]

     [:div {:class (stl/css :links)}
      (when (contains? cf/flags :registration)
        [:div {:class (stl/css :register)}
         [:span {:class (stl/css :register-text)}
          (tr "auth.register") " "]
         [:& lk/link {:action go-register
                      :class (stl/css :register-link)
                      :data-testid "register-submit"}
          (tr "auth.register-submit")]])]]))
