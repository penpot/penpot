;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.viewer.login
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-dialog*]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page*]]
   [app.main.ui.auth.register :refer [register-methods* register-success-page*
                                      register-validate-form* terms-service-privacy-policy*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(log/set-level! :warn)

(mf/defc login-register-modal
  {::mf/register modal/components
   ::mf/register-as :login-register}
  [_]
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
          (st/emit! (modal/hide)))

        success-email-sent
        (fn [email]
          (reset! user-email email)
          (set-current-section :email-sent))

        success-login
        (fn []
          (.reload js/window.location true))

        success-register
        (fn [data]
          (reset! register-token (:token data))
          (set-current-section :register-validate))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-header-title)} (tr "labels.continue-with-penpot")]
       [:> icon-button* {:variant "ghost"
                         :class (stl/css :modal-close)
                         :aria-label (tr "labels.close")
                         :on-click close
                         :icon i/close}]]

      [:div  {:class (stl/css :modal-content)}
       (case current-section
         :login
         [:div {:class (stl/css :login-form)}
          [:> login-dialog* {:on-success-callback success-login
                             :origin :viewer}]
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
          [:> register-methods* {:on-success-callback success-register}]
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
