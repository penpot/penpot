;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.register
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.notifications :as ntf]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.auth.login :as login]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

;; --- PAGE: Register

(mf/defc newsletter-options*
  {::mf/private true}
  []
  (let [updates-label
        (mf/html
         [:> i18n/tr-html*
          {:tag-name "div"
           :content (tr "onboarding-v2.newsletter.updates")}])]
    [:div {:class (stl/css :fields-row :input-visible :newsletter-option-wrapper)}
     [:& fm/input {:name :accept-newsletter-updates
                   :class (stl/css :checkbox-newsletter-updates)
                   :type "checkbox"
                   :default-checked false
                   :label updates-label}]]))

(mf/defc terms-and-privacy
  {::mf/props :obj
   ::mf/private true}
  []
  (let [terms-label
        (mf/html
         [:> i18n/tr-html*
          {:tag-name "div"
           :content (tr "auth.terms-and-privacy-agreement"
                        cf/terms-of-service-uri
                        cf/privacy-policy-uri)}])]

    [:div {:class (stl/css :fields-row :input-visible :accept-terms-and-privacy-wrapper)}
     [:& fm/input {:name :accept-terms-and-privacy
                   :show-error false
                   :class (stl/css :checkbox-terms-and-privacy)
                   :type "checkbox"
                   :default-checked false
                   :label terms-label}]]))

(def ^:private schema:register-form
  [:map {:title "RegisterForm"}
   [:password ::sm/password]
   [:fullname [::sm/text {:max 250}]]
   [:email ::sm/email]
   [:accept-terms-and-privacy {:optional (not (contains? cf/flags :terms-and-privacy-checkbox))}
    [:and :boolean [:= true]]]
   [:accept-newsletter-updates {:optional true} :boolean]
   [:token {:optional true} ::sm/text]])

(mf/defc register-form
  {::mf/props :obj}
  [{:keys [params on-success-callback]}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))
        form    (fm/use-form :schema schema:register-form
                             :initial initial)

        submitted?
        (mf/use-state false)

        on-error
        (mf/use-fn
         (fn [cause]
           (let [{:keys [type code] :as edata} (ex-data cause)]
             (condp = [type code]
               [:restriction :email-does-not-match-invitation]
               (st/emit! (ntf/error (tr "errors.email-does-not-match-invitation")))

               [:restriction :registration-disabled]
               (st/emit! (ntf/error (tr "errors.registration-disabled")))

               [:restriction :email-domain-is-not-allowed]
               (st/emit! (ntf/error (tr "errors.email-domain-not-allowed")))

               [:restriction :email-has-permanent-bounces]
               (st/emit! (ntf/error (tr "errors.email-has-permanent-bounces" (:email edata))))

               [:restriction :email-has-complaints]
               (st/emit! (ntf/error (tr "errors.email-has-permanent-bounces" (:email edata))))

               [:validation :email-as-password]
               (swap! form assoc-in [:errors :password]
                      {:message (tr "errors.email-as-password")})

               (do
                 (when-let [explain (get edata :explain)]
                   (println explain))
                 (st/emit! (ntf/error (tr "errors.generic"))))))))

        on-success
        (mf/use-fn
         (mf/deps on-success-callback)
         (fn [params]
           (if (fn? on-success-callback)
             (on-success-callback (:email params))
             (cond
               (some? (:invitation-token params))
               (let [token (:invitation-token params)]
                 (st/emit! (rt/nav :auth-verify-token {:token token})))

               (:is-active params)
               (st/emit! (da/login-from-register))

               :else
               (do
                 (swap! storage/user assoc ::email (:email params))
                 (st/emit! (rt/nav :auth-register-success)))))))

        on-register-profile
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [params]
           (reset! submitted? true)
           (->> (rp/cmd! :register-profile params)
                (rx/subs! on-success on-error #(reset! submitted? false)))))

        on-submit
        (mf/use-fn
         (mf/deps on-success-callback)
         (fn [form _event]
           (reset! submitted? true)
           (let [create-welcome-file?
                 (cf/external-feature-flag "onboarding-03" "test")

                 cdata
                 (cond-> (:clean-data @form)
                   create-welcome-file?
                   (assoc :create-welcome-file true))]

             (->> (rp/cmd! :prepare-register-profile cdata)
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs! on-register-profile on-error)))))]

    [:& fm/form {:on-submit on-submit :form form}
     [:div {:class (stl/css :fields-row)}

      [:& fm/input {:name :fullname
                    :label (tr "auth.fullname")
                    :type "text"
                    :show-success? true
                    :class (stl/css :form-field)}]]
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

     (when (contains? cf/flags :terms-and-privacy-checkbox)
       [:& terms-and-privacy])

     [:> newsletter-options*]

     [:> fm/submit-button*
      {:label (tr "auth.register-submit")
       :disabled @submitted?
       :data-testid "register-form-submit"
       :class (stl/css :register-btn)}]]))

(mf/defc register-methods
  {::mf/props :obj}
  [{:keys [params hide-separator on-success-callback]}]
  [:*
   (when login/show-alt-login-buttons?
     [:& login/login-buttons {:params params}])
   (when (or login/show-alt-login-buttons? (false? hide-separator))
     [:hr {:class (stl/css :separator)}])
   (when (contains? cf/flags :login-with-password)
     [:& register-form {:params params :on-success-callback on-success-callback}])])

(mf/defc register-page
  {::mf/props :obj}
  [{:keys [params]}]
  [:div {:class (stl/css :auth-form-wrapper :register-form)}
   [:h1 {:class (stl/css :auth-title)
         :data-testid "registration-title"} (tr "auth.register-title")]

   (when (contains? cf/flags :demo-warning)
     [:& login/demo-warning])

   [:& register-methods {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :account)}
     [:span {:class (stl/css :account-text)} (tr "auth.already-have-account") " "]
     [:& lk/link {:action  #(st/emit! (rt/nav :auth-login params))
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

;; --- PAGE: register success page

(mf/defc register-success-page
  {::mf/props :obj}
  [{:keys [params]}]
  (let [email (or (:email params) (::email storage/user))]
    [:div {:class (stl/css :auth-form-wrapper :register-success)}
     [:div {:class (stl/css :auth-title-wrapper)}
      [:h2 {:class (stl/css :auth-title)}
       (tr "auth.check-mail")]
      [:div {:class (stl/css :notification-text)} (tr "auth.verification-email-sent")]]
     [:div {:class (stl/css :notification-text-email)} email]]))


(mf/defc terms-register
  []
  (let [show-all?     (and cf/terms-of-service-uri cf/privacy-policy-uri)
        show-terms?   (some? cf/terms-of-service-uri)
        show-privacy? (some? cf/privacy-policy-uri)]

    (when show-all?
      [:div {:class (stl/css :terms-register)}
       (when show-terms?
         [:a {:href cf/terms-of-service-uri :target "_blank" :class (stl/css :auth-link)}
          (tr "auth.terms-of-service")])

       (when show-all?
         [:span {:class (stl/css :and-text)}
          (dm/str " " (tr "labels.and") "  ")])

       (when show-privacy?
         [:a {:href cf/privacy-policy-uri :target "_blank" :class (stl/css :auth-link)}
          (tr "auth.privacy-policy")])])))

;; --- PAGE: register validation

(def ^:private schema:register-validate-form
  [:map {:title "RegisterValidateForm"}
   [:token ::sm/text]
   [:fullname [::sm/text {:max 250}]]
   [:accept-terms-and-privacy {:optional (not (contains? cf/flags :terms-and-privacy-checkbox))}
    [:and :boolean [:= true]]]])

(mf/defc register-validate-form
  {::mf/props :obj
   ::mf/private true}
  [{:keys [params on-success-callback]}]
  (let [form       (fm/use-form :schema schema:register-validate-form :initial params)

        submitted?
        (mf/use-state false)

        on-success
        (mf/use-fn
         (mf/deps on-success-callback)
         (fn [params]
           (if (fn? on-success-callback)
             (on-success-callback (:email params))

             (cond
               (some? (:invitation-token params))
               (let [token (:invitation-token params)]
                 (st/emit! (rt/nav :auth-verify-token {:token token})))

               (:is-active params)
               (st/emit! (da/login-from-register))

               :else
               (do
                 (swap! storage/user assoc ::email (:email params))
                 (st/emit! (rt/nav :auth-register-success)))))))

        on-error
        (mf/use-fn
         (fn [_]
           (st/emit! (ntf/error (tr "errors.generic")))))

        on-submit
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [form _]
           (reset! submitted? true)
           (let [create-welcome-file?
                 (cf/external-feature-flag "onboarding-03" "test")

                 params
                 (cond-> (:clean-data @form)
                   create-welcome-file? (assoc :create-welcome-file true))]

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
  [:div {:class (stl/css :auth-form-wrapper :register-form)}


   [:div {:class (stl/css :auth-title-wrapper)}
    [:h2 {:class (stl/css :auth-title)
          :data-testid "register-title"} (tr "auth.register-account-title")]
    [:div {:class (stl/css :auth-subtitle)} (tr "auth.register-account-tagline")]]

   [:& register-validate-form {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :go-back)}
     [:& lk/link {:action  #(st/emit! (rt/nav :auth-register {}))
                  :class (stl/css :go-back-link)}
      (tr "labels.go-back")]]]])
