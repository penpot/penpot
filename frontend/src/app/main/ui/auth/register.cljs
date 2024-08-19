;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.register
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as msg]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.auth.login :as login]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.main.ui.icons :as i]
   [app.util.i18n :refer [tr tr-html]]
   [app.util.router :as rt]
   [app.util.storage :as sto]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

;; --- PAGE: Register

(defn- validate-password-length
  [errors data]
  (let [password (:password data)]
    (cond-> errors
      (> 8 (count password))
      (assoc :password {:message "errors.password-too-short"}))))

(defn- validate-email
  [errors _]
  (d/update-when errors :email
                 (fn [{:keys [code] :as error}]
                   (cond-> error
                     (= code ::us/email)
                     (assoc :message (tr "errors.email-invalid"))))))

(s/def ::fullname ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::invitation-token ::us/not-empty-string)
(s/def ::terms-privacy ::us/boolean)

(s/def ::register-form
  (s/keys :req-un [::password ::email]
          :opt-un [::invitation-token]))

(defn- on-prepare-register-error
  [form cause]
  (let [{:keys [type code] :as edata} (ex-data cause)]
    (condp = [type code]
      [:restriction :registration-disabled]
      (st/emit! (msg/error (tr "errors.registration-disabled")))

      [:restriction :email-domain-is-not-allowed]
      (st/emit! (msg/error (tr "errors.email-domain-not-allowed")))

      [:restriction :email-has-permanent-bounces]
      (st/emit! (msg/error (tr "errors.email-has-permanent-bounces" (:email edata))))

      [:restriction :email-has-complaints]
      (st/emit! (msg/error (tr "errors.email-has-permanent-bounces" (:email edata))))

      [:validation :email-as-password]
      (swap! form assoc-in [:errors :password]
             {:message "errors.email-as-password"})

      (st/emit! (msg/error (tr "errors.generic"))))))

(defn- on-prepare-register-success
  [params]
  (st/emit! (rt/nav :auth-register-validate {} params)))

(mf/defc register-form
  [{:keys [params on-success-callback]}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))
        form    (fm/use-form :spec ::register-form
                             :validators [validate-password-length
                                          validate-email
                                          (fm/validate-not-empty :password (tr "auth.password-not-empty"))]
                             :initial initial)

        submitted? (mf/use-state false)

        on-submit
        (mf/use-fn
         (mf/deps on-success-callback)
         (fn [form _event]
           (reset! submitted? true)
           (let [cdata      (:clean-data @form)
                 on-success (fn [data]
                              (if (nil? on-success-callback)
                                (on-prepare-register-success data)
                                (on-success-callback data)))
                 on-error   (fn [data]
                              (on-prepare-register-error form data))]

             (->> (rp/cmd! :prepare-register-profile cdata)
                  (rx/map #(merge % params))
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs! on-success on-error)))))]

    [:& fm/form {:on-submit on-submit :form form}
     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:type "text"
                    :name :email
                    :label (tr "auth.work-email")
                    :data-testid "email-input"
                    :show-success? true
                    :class (stl/css :form-field)}]]
     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:name :password
                    :hint (tr "auth.password-length-hint")
                    :label (tr "auth.password")
                    :show-success? true
                    :type "password"
                    :class (stl/css :form-field)}]]

     [:> fm/submit-button*
      {:label (tr "auth.register-submit")
       :disabled @submitted?
       :data-testid "register-form-submit"
       :class (stl/css :register-btn)}]]))

(mf/defc register-methods
  {::mf/props :obj}
  [{:keys [params on-success-callback]}]
  [:*
   (when login/show-alt-login-buttons?
     [:& login/login-buttons {:params params}])
   [:hr {:class (stl/css :separator)}]
   (when (contains? cf/flags :login-with-password)
     [:& register-form {:params params :on-success-callback on-success-callback}])])

(mf/defc register-page
  {::mf/props :obj}
  [{:keys [params]}]
  [:div {:class (stl/css :auth-form-wrapper :register-form)}
   [:h1 {:class (stl/css :auth-title)
         :data-testid "registration-title"} (tr "auth.register-title")]
   [:p {:class (stl/css :auth-tagline)}
    (tr "auth.register-tagline")]

   (when (contains? cf/flags :demo-warning)
     [:& login/demo-warning])

   [:& register-methods {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :account)}
     [:span {:class (stl/css :account-text)} (tr "auth.already-have-account") " "]
     [:& lk/link {:action  #(st/emit! (rt/nav :auth-login {} params))
                  :class (stl/css :account-link)
                  :data-testid "login-here-link"}
      (tr "auth.login-here")]]

    (when (contains? cf/flags :demo-users)
      [:*
       [:hr {:class (stl/css :separator)}]
       [:div {:class (stl/css :demo-account)}
        [:& lk/link {:action login/create-demo-profile
                     :class (stl/css :demo-account-link)}
         (tr "auth.create-demo-account")]]])]])

;; --- PAGE: register validation

(defn- on-register-success
  [data]
  (cond
    (some? (:invitation-token data))
    (let [token (:invitation-token data)]
      (st/emit! (rt/nav :auth-verify-token {} {:token token})))

    (:is-active data)
    (st/emit! (du/login-from-register))

    :else
    (do
      (swap! sto/storage assoc ::email (:email data))
      (st/emit! (rt/nav :auth-register-success)))))

(s/def ::accept-terms-and-privacy (s/and ::us/boolean true?))
(s/def ::accept-newsletter-subscription ::us/boolean)

(if (contains? cf/flags :terms-and-privacy-checkbox)
  (s/def ::register-validate-form
    (s/keys :req-un [::token ::fullname ::accept-terms-and-privacy]
            :opt-un [::accept-newsletter-subscription]))
  (s/def ::register-validate-form
    (s/keys :req-un [::token ::fullname]
            :opt-un [::accept-terms-and-privacy
                     ::accept-newsletter-subscription])))

(mf/defc terms-and-privacy
  {::mf/props :obj
   ::mf/private true}
  []
  (let [terms-label
        (mf/html
         [:& tr-html
          {:tag-name "div"
           :label "auth.terms-and-privacy-agreement"
           :params [cf/terms-of-service-uri cf/privacy-policy-uri]}])]

    [:div {:class (stl/css :fields-row :input-visible :accept-terms-and-privacy-wrapper)}
     [:& fm/input {:name :accept-terms-and-privacy
                   :class (stl/css :checkbox-terms-and-privacy)
                   :type "checkbox"
                   :default-checked false
                   :label terms-label}]]))

(mf/defc register-validate-form
  {::mf/props :obj}
  [{:keys [params on-success-callback]}]
  (let [validators (mf/with-memo []
                     [(fm/validate-not-empty :fullname (tr "auth.name.not-all-space"))
                      (fm/validate-length :fullname fm/max-length-allowed (tr "auth.name.too-long"))])

        form       (fm/use-form :spec ::register-validate-form
                                :validators validators
                                :initial params)

        submitted? (mf/use-state false)
        theme      (when (cf/external-feature-flag "onboarding-02" "test") "light")

        on-success
        (mf/use-fn
         (mf/deps on-success-callback)
         (fn [params]
           (if (nil? on-success-callback)
             (on-register-success params)
             (on-success-callback (:email params)))))

        on-error
        (mf/use-fn
         (fn [_cause]
           (st/emit! (msg/error (tr "errors.generic")))))

        on-submit
        (mf/use-fn
         (fn [form _]
           (reset! submitted? true)
           (let [params (cond-> (:clean-data @form)
                          (some? theme) (assoc :theme theme))]
             (->> (rp/cmd! :register-profile params)
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs! on-success on-error)))))]

    [:& fm/form {:on-submit on-submit
                 :form form
                 :class (stl/css :register-validate-form)}

     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:name :fullname
                    :label (tr "auth.fullname")
                    :type "text"
                    :show-success? true
                    :class (stl/css :form-field)}]]

     (when (contains? cf/flags :terms-and-privacy-checkbox)
       [:& terms-and-privacy])

     [:> fm/submit-button*
      {:label (tr "auth.register-submit")
       :disabled @submitted?
       :class (stl/css :register-btn)}]]))


(mf/defc register-validate-page
  {::mf/props :obj}
  [{:keys [params]}]
  [:div {:class (stl/css :auth-form-wrapper)}
   [:h1 {:class (stl/css :logo-container)}
    [:a {:href "#/" :title "Penpot" :class (stl/css :logo-btn)} i/logo]]
   [:div {:class (stl/css :auth-title-wrapper)}
    [:h2 {:class (stl/css :auth-title)
          :data-testid "register-title"} (tr "auth.register-account-title")]
    [:div {:class (stl/css :auth-subtitle)} (tr "auth.register-account-tagline")]]

   [:& register-validate-form {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :go-back)}
     [:& lk/link {:action  #(st/emit! (rt/nav :auth-register {} {}))
                  :class (stl/css :go-back-link)}
      (tr "labels.go-back")]]]])

(mf/defc register-success-page
  {::mf/props :obj}
  []
  (let [email (::email @sto/storage)]
    [:div {:class (stl/css :auth-form-wrapper :register-success)}
     [:h1 {:class (stl/css :logo-container)}
      [:a {:href "#/" :title "Penpot" :class (stl/css :logo-btn)} i/logo]]
     [:div {:class (stl/css :auth-title-wrapper)}
      [:h2 {:class (stl/css :auth-title)}
       (tr "auth.check-mail")]
      [:div {:class (stl/css :notification-text)} (tr "auth.verification-email-sent")]]
     [:div {:class (stl/css :notification-text-email)} email]
     [:div {:class (stl/css :notification-text)} (tr "auth.check-your-email")]]))
