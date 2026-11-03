;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.auth.login-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-dialog*]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page*]]
   [app.main.ui.auth.register :refer [register-methods* register-success-page*
                                      register-validate-form* terms-service-privacy-policy*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [rumext.v2 :as mf]))

(mf/defc login-register-modal*
  [{:keys [params on-close on-register]}]
  (let [user-email     (mf/use-state "")
        register-token (mf/use-state "")

        current-section* (mf/use-state :login)
        current-section  (deref current-section*)

        set-current-section
        (mf/use-fn
         #(reset! current-section* %))

        set-section
        (mf/use-fn
         (fn [event]
           (let [section (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (keyword))]
             (set-current-section section))))

        go-back-to-login
        (mf/use-fn
         #(set-current-section :login))

        main-section (or
                      (= current-section :login)
                      (= current-section :register)
                      (= current-section :register-validate))
        close
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (on-close))

        success-email-sent
        (fn [email]
          (reset! user-email email)
          (set-current-section :email-sent))

        success-login
        (fn [& _]
          (.reload js/window.location true))

        success-register
        (fn [data]
          (cond
            (:invitation-token data)
            (st/emit! (rt/nav :auth-verify-token {:token (:invitation-token data)}))

            (:is-active data)
            (if on-register (on-register) (on-close))

            :else
            (do
              (reset! user-email (:email data))
              (set-current-section :email-sent))))]

    [:div {:class (stl/css :modal-overlay)
           :on-key-down #(when (k/esc? %) (close %))}
     [:div {:class (stl/css :modal-container)
            :role "dialog"
            :aria-modal true
            :aria-labelledby "login-modal-title"}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-header-title)
             :id "login-modal-title"} (tr "labels.continue-with-penpot")]
       [:> icon-button* {:variant "ghost"
                         :class (stl/css :modal-close)
                         :aria-label (tr "labels.close")
                         :on-click close
                         :icon i/close}]]

      [:div  {:class (stl/css :modal-content)}
       (case current-section
         :login
         [:div {:class (stl/css :login-form)}
          [:> login-dialog* {:params params
                             :on-success-callback success-login
                             :hide-recovery-link true}]
          [:div {:class (stl/css :login-links)}
           [:div
            [:a {:on-click set-section
                 :data-value "recovery-request"}
             (tr "auth.forgot-password")]]
           (when (contains? cf/flags :registration)
             [:div
              [:span
               (tr "auth.register") " "]
              [:a {:on-click set-section
                   :data-value "register"}
               (tr "auth.register-submit")]])]]

         :register
         [:div {:class (stl/css :login-form)}
          [:> register-methods* {:params params :on-success-callback success-register}]
          [:div {:class (stl/css :login-links)}
           [:div
            [:span (tr "auth.already-have-account") " "]
            [:a {:on-click set-section
                 :data-value "login"}
             (tr "auth.login-here")]]]]

         :register-validate
         [:div {:class (stl/css :login-form)}
          [:> register-validate-form* {:params {:token @register-token}
                                       :on-success-callback success-email-sent}]
          [:div {:class (stl/css :login-links)}
           [:div
            [:a {:on-click set-section
                 :data-value "register"}
             (tr "labels.go-back")]]]]

         :recovery-request
         [:> recovery-request-page* {:go-back-callback go-back-to-login
                                     :on-success-callback success-email-sent}]

         :email-sent
         [:div {:class (stl/css :login-form)}
          [:> register-success-page* {:params {:email @user-email}}]])

       (when main-section
         [:div {:class (stl/css :login-links)}
          [:> terms-service-privacy-policy*]])]]]))
