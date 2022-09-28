;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.login
  (:require
   [app.common.logging :as log]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.auth :refer [terms-login]]
   [app.main.ui.auth.login :refer [login-methods]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-methods register-validate-form register-success-page]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :refer [storage]]
   [rumext.v2 :as mf]))

(log/set-level! :warn)

(mf/defc login-register-modal
  {::mf/register modal/components
   ::mf/register-as :login-register}
  [_]
  (let [uri (. (. js/document -location) -href)
        user-email (mf/use-state "")
        register-token (mf/use-state "")
        current-section (mf/use-state :login)
        set-current-section (mf/use-fn #(reset! current-section %))
        main-section (or
                      (= @current-section :login)
                      (= @current-section :register)
                      (= @current-section :register-validate))
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
    (mf/with-effect []
      (swap! storage assoc :redirect-url uri))

    [:div.modal-overlay
     [:div.modal-container.login-register
      [:div.title
       [:div.modal-close-button {:on-click close :title (tr "labels.close")}
        i/close]
       (when main-section
         [:h2 (tr "labels.continue-with-penpot")])]

      [:div.modal-bottom.auth-content

       (case @current-section
         :login
         [:div.generic-form.login-form
          [:div.form-container
           [:& login-methods {:on-success-callback success-login}]
           [:div.links
            [:div.link-entry
             [:a {:on-click #(set-current-section :recovery-request)}
              (tr "auth.forgot-password")]]
            [:div.link-entry
             [:span (tr "auth.register") " "]
             [:a {:on-click #(set-current-section :register)}
              (tr "auth.register-submit")]]]]]

         :register
         [:div.form-container
          [:& register-methods {:on-success-callback success-register}]
          [:div.links
           [:div.link-entry
            [:span (tr "auth.already-have-account") " "]
            [:a {:on-click #(set-current-section :login)}
             (tr "auth.login-here")]]]]

         :register-validate
         [:div.form-container
          [:& register-validate-form {:params {:token @register-token}
                                      :on-success-callback success-email-sent}]
          [:div.links
           [:div.link-entry
            [:a {:on-click #(set-current-section :register)}
             (tr "labels.go-back")]]]]

         :recovery-request
         [:& recovery-request-page {:go-back-callback #(set-current-section :login)
                                    :on-success-callback success-email-sent}]
         :email-sent
         [:div.form-container
          [:& register-success-page {:params {:email @user-email}}]])]

      (when main-section
        [:div.modal-footer.links
         [:& terms-login]])]]))
