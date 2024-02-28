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
  (let [{:keys [type code]} (ex-data cause)]
    (condp = [type code]
      [:restriction :registration-disabled]
      (st/emit! (msg/error (tr "errors.registration-disabled")))

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
                    :label (tr "auth.email")
                    :data-test "email-input"
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
       :data-test "register-form-submit"
       :class (stl/css :register-btn)}]]))

(mf/defc register-methods
  {::mf/props :obj}
  [{:keys [params on-success-callback]}]
  [:*
   (when login/show-alt-login-buttons?
     [:& login/login-buttons {:params params}])
   [:hr {:class (stl/css :separator)}]
   [:& register-form {:params params :on-success-callback on-success-callback}]])

(mf/defc register-page
  {::mf/props :obj}
  [{:keys [params]}]
  [:div {:class (stl/css :auth-form-wrapper)}
   [:h1 {:class (stl/css :auth-title)
         :data-test "registration-title"} (tr "auth.register-title")]
   [:p {:class (stl/css :auth-tagline)}
    (tr "auth.login-tagline")]

   (when (contains? cf/flags :demo-warning)
     [:& login/demo-warning])

   [:& register-methods {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :account)}
     [:span {:class (stl/css :account-text)} (tr "auth.already-have-account") " "]
     [:& lk/link {:action  #(st/emit! (rt/nav :auth-login {} params))
                  :class (stl/css :account-link)
                  :data-test "login-here-link"}
      (tr "auth.login-here")]]

    (when (contains? cf/flags :demo-users)
      [:*
       [:hr {:class (stl/css :separator)}]
       [:div {:class (stl/css :demo-account)}
        [:& lk/link {:action login/create-demo-profile
                     :class (stl/css :demo-account-link)}
         (tr "auth.create-demo-account")]]])]])

;; --- PAGE: register validation

(defn- handle-register-error
  [_form _data]
  (st/emit! (msg/error (tr "errors.generic"))))

(defn- handle-register-success
  [data]
  (cond
    (some? (:invitation-token data))
    (let [token (:invitation-token data)]
      (st/emit! (rt/nav :auth-verify-token {} {:token token})))

    (:is-active data)
    (st/emit! (du/login-from-register))

    :else
    (st/emit! (rt/nav :auth-register-success {} {:email (:email data)}))))

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

(mf/defc register-validate-form
  [{:keys [params on-success-callback]}]
  (let [form       (fm/use-form :spec ::register-validate-form
                                :validators [(fm/validate-not-empty :fullname (tr "auth.name.not-all-space"))
                                             (fm/validate-length :fullname fm/max-length-allowed (tr "auth.name.too-long"))]
                                :initial params)
        submitted? (mf/use-state false)

        on-success (fn [p]
                     (if (nil? on-success-callback)
                       (handle-register-success p)
                       (on-success-callback (:email p))))

        on-submit
        (mf/use-fn
         (fn [form _event]
           (reset! submitted? true)
           (let [params (:clean-data @form)]
             (->> (rp/cmd! :register-profile params)
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs! on-success
                            (partial handle-register-error form))))))]

    [:& fm/form {:on-submit on-submit :form form
                 :class (stl/css :register-validate-form)}
     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:name :fullname
                    :label (tr "auth.fullname")
                    :type "text"
                    :show-success? true
                    :class (stl/css :form-field)}]]

     (when (contains? cf/flags :terms-and-privacy-checkbox)
       (let [terms-label
             (mf/html
              [:& tr-html
               {:tag-name "div"
                :label "auth.terms-privacy-agreement-md"
                :params [cf/terms-of-service-uri cf/privacy-policy-uri]}])]
         [:div {:class (stl/css :fields-row :input-visible :accept-terms-and-privacy-wrapper)}
          [:& fm/input {:name :accept-terms-and-privacy
                        :class "check-primary"
                        :type "checkbox"
                        :default-checked false
                        :label terms-label}]]))

     [:> fm/submit-button*
      {:label (tr "auth.register-submit")
       :disabled @submitted?
       :class (stl/css :register-btn)}]]))


(mf/defc register-validate-page
  [{:keys [params]}]
  [:div {:class (stl/css :auth-form-wrapper)}
   [:h1 {:class (stl/css :auth-title)
         :data-test "register-title"} (tr "auth.register-title")]
   [:div {:class (stl/css :auth-subtitle)} (tr "auth.register-subtitle")]

   [:hr {:class (stl/css :separator)}]

   [:& register-validate-form {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :go-back)}
     [:& lk/link {:action  #(st/emit! (rt/nav :auth-register {} {}))
                  :class (stl/css :go-back-link)}
      (tr "labels.go-back")]]]])

(mf/defc register-success-page
  [{:keys [params]}]
  [:div {:class (stl/css :auth-form-wrapper :register-success)}
   [:div {:class (stl/css :notification-icon)} i/icon-verify]
   [:div {:class (stl/css :notification-text)} (tr "auth.verification-email-sent")]
   [:div {:class (stl/css :notification-text-email)} (:email params "")]
   [:div {:class (stl/css :notification-text)} (tr "auth.check-your-email")]])
