;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.register
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.auth.login :as login]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(mf/defc demo-warning
  [_]
  [:& msgs/inline-banner
   {:type :warning
    :content (tr "auth.demo-warning")}])

;; --- PAGE: Register

(defn- validate
  [data]
  (let [password (:password data)]
    (cond-> {}
      (> 8 (count password))
      (assoc :password {:message "errors.password-too-short"}))))

(s/def ::fullname ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::invitation-token ::us/not-empty-string)
(s/def ::terms-privacy ::us/boolean)

(s/def ::register-form
  (s/keys :req-un [::password ::email]
          :opt-un [::invitation-token]))

(defn- handle-prepare-register-error
  [form {:keys [type code] :as cause}]
  (condp = [type code]
    [:restriction :registration-disabled]
    (st/emit! (dm/error (tr "errors.registration-disabled")))

    [:restriction :profile-blocked]
    (st/emit! (dm/error (tr "errors.profile-blocked")))

    [:validation :email-has-permanent-bounces]
    (let [email (get @form [:data :email])]
      (st/emit! (dm/error (tr "errors.email-has-permanent-bounces" email))))

    [:validation :email-already-exists]
    (swap! form assoc-in [:errors :email]
           {:message "errors.email-already-exists"})

    [:validation :email-as-password]
    (swap! form assoc-in [:errors :password]
           {:message "errors.email-as-password"})

    (st/emit! (dm/error (tr "errors.generic")))))

(defn- handle-prepare-register-success
  [params]
  (st/emit! (rt/nav :auth-register-validate {} params)))


(mf/defc register-form
  [{:keys [params on-success-callback] :as props}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))
        form    (fm/use-form :spec ::register-form
                             :validators [validate]
                             :initial initial)
        submitted? (mf/use-state false)

        on-success (fn [p]
                     (if (nil? on-success-callback)
                       (handle-prepare-register-success p)
                       (on-success-callback p)))

        on-submit
        (mf/use-callback
         (fn [form _event]
           (reset! submitted? true)
           (let [cdata (:clean-data @form)]
             (->> (rp/command! :prepare-register-profile cdata)
                  (rx/map #(merge % params))
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs
                   on-success
                   (partial handle-prepare-register-error form))))))]


    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:type "email"
                    :name :email
                    :tab-index "2"
                    :help-icon i/at
                    :label (tr "auth.email")
                    :data-test "email-input"}]]
     [:div.fields-row
      [:& fm/input {:name :password
                    :tab-index "3"
                    :hint (tr "auth.password-length-hint")
                    :label (tr "auth.password")
                    :type "password"}]]

     [:& fm/submit-button
      {:label (tr "auth.register-submit")
       :disabled @submitted?
       :data-test "register-form-submit"}]]))


(mf/defc register-methods
  [{:keys [params on-success-callback] :as props}]
  [:*
   (when login/show-alt-login-buttons?
     [:*
      [:span.separator
       [:span.line]
       [:span.text (tr "labels.continue-with")]
       [:span.line]]

      [:div.buttons
       [:& login/login-buttons {:params params}]]

      (when (or (contains? @cf/flags :login)
                (contains? @cf/flags :login-with-ldap))
        [:span.separator
         [:span.line]
         [:span.text (tr "labels.or")]
         [:span.line]])])

   [:& register-form {:params params :on-success-callback on-success-callback}]])

(mf/defc register-page
  [{:keys [params] :as props}]
  [:div.form-container

   [:h1 {:data-test "registration-title"} (tr "auth.register-title")]
   [:div.subtitle (tr "auth.register-subtitle")]

   (when (contains? @cf/flags :demo-warning)
     [:& demo-warning])

   [:& register-methods {:params params}]

   [:div.links
    [:div.link-entry
     [:span (tr "auth.already-have-account") " "]
     [:a {:on-click #(st/emit! (rt/nav :auth-login {} params))
          :tab-index "4"
          :data-test "login-here-link"}
      (tr "auth.login-here")]]

    (when (contains? @cf/flags :demo-users)
      [:div.link-entry
       [:span (tr "auth.create-demo-profile") " "]
       [:a {:on-click #(st/emit! (du/create-demo-profile))
            :tab-index "5"}
        (tr "auth.create-demo-account")]])]])

;; --- PAGE: register validation

(defn- handle-register-error
  [form error]
  (case (:code error)
    :email-already-exists
    (swap! form assoc-in [:errors :email]
           {:message "errors.email-already-exists"})

    (do
      (println (:explain error))
      (st/emit! (dm/error (tr "errors.generic"))))))

(defn- handle-register-success
  [data]
  (cond
    (some? (:invitation-token data))
    (let [token (:invitation-token data)]
      (st/emit! (rt/nav :auth-verify-token {} {:token token})))

    ;; The :is-active flag is true, when insecure-register is enabled
    ;; or the user used external auth provider.
    (:is-active data)
    (st/emit! (du/login-from-register))

    :else
    (st/emit! (rt/nav :auth-register-success {} {:email (:email data)}))))

(s/def ::accept-terms-and-privacy (s/and ::us/boolean true?))
(s/def ::accept-newsletter-subscription ::us/boolean)

(if (contains? @cf/flags :terms-and-privacy-checkbox)
  (s/def ::register-validate-form
    (s/keys :req-un [::token ::fullname ::accept-terms-and-privacy]
            :opt-un [::accept-newsletter-subscription]))
  (s/def ::register-validate-form
    (s/keys :req-un [::token ::fullname]
            :opt-un [::accept-terms-and-privacy
                     ::accept-newsletter-subscription])))

(mf/defc register-validate-form
  [{:keys [params on-success-callback] :as props}]
  (let [form       (fm/use-form :spec ::register-validate-form
                                :initial params)
        submitted? (mf/use-state false)

        on-success (fn [p]
                     (if (nil? on-success-callback)
                       (handle-register-success p)
                       (on-success-callback (:email p))))

        on-submit
        (mf/use-callback
         (fn [form _event]
           (reset! submitted? true)
           (let [params (:clean-data @form)]
             (->> (rp/command! :register-profile params)
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs on-success
                           (partial handle-register-error form))))))]

    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:name :fullname
                    :tab-index "1"
                    :label (tr "auth.fullname")
                    :type "text"}]]

     (when (contains? @cf/flags :terms-and-privacy-checkbox)
       [:div.fields-row
        [:& fm/input {:name :accept-terms-and-privacy
                      :class "check-primary"
                      :type "checkbox"}
         [:span
          (tr "auth.terms-privacy-agreement")
          [:div
           [:a {:href "https://penpot.app/terms.html" :target "_blank"} (tr "auth.terms-of-service")]
           [:span ",\u00A0"]
           [:a {:href "https://penpot.app/privacy.html" :target "_blank"} (tr "auth.privacy-policy")]]]]])

     [:& fm/submit-button
      {:label (tr "auth.register-submit")
       :disabled @submitted?}]]))


(mf/defc register-validate-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:h1 {:data-test "register-title"} (tr "auth.register-title")]
   [:div.subtitle (tr "auth.register-subtitle")]

   [:& register-validate-form {:params params}]

   [:div.links
    [:div.link-entry
     [:a {:on-click #(st/emit! (rt/nav :auth-register {} {}))
          :tab-index "4"}
      (tr "labels.go-back")]]]])

(mf/defc register-success-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:div.notification-icon i/icon-verify]
   [:div.notification-text (tr "auth.verification-email-sent")]
   [:div.notification-text-email (:email params "")]
   [:div.notification-text (tr "auth.check-your-email")]])

